package at.hazm.webserver.handler

import java.io.{BufferedInputStream, File, FileInputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.script.{ScriptContext, ScriptEngine, ScriptEngineManager}

import at.hazm.using
import at.hazm.webserver._
import com.twitter.finagle.http._
import com.twitter.io.{Bufs, Reader}
import jdk.nashorn.api.scripting.JSObject
import jdk.nashorn.internal.runtime.Undefined
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, _}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/**
  * ドキュメントルート上に配置されている JavaScript ファイルをサーバサイドで実行するためのリクエストハンドラ。
  *
  * @param docroot ドキュメントルート
  * @param timeout 実行タイムアウト (ミリ秒)
  * @param exts 対象とするファイルの拡張子 (`".xjs"` など)
  */
class ScriptHandler(docroot:Path, timeout:Long, exts:Seq[String]) extends RequestHandler(docroot) {
  private[ScriptHandler] val logger = LoggerFactory.getLogger(getClass)

  private[this] val manager = new ScriptEngineManager()

  override def apply(request:Request):Option[Response] = None

  override def applyAsync(request:Request)(implicit context:ExecutionContext):Future[Option[Response]] = {
    FileHandler.mapLocalFile(docroot, request.uri) match {
      case Some(file) =>
        if (exts.exists(ext => file.getName.endsWith(ext))) {
          if (file.isFile) {
            exec(request, file).map(Some.apply)
          } else {
            Future.successful(Some(getErrorResponse(request, Status.NotFound)))
          }
        } else Future.successful(None)
      case None =>
        Future.successful(Some(getErrorResponse(request, Status.BadRequest)))
    }
  }

  /**
    * 指定されたスクリプトを非同期実行する。
    *
    * @param request リクエスト
    * @param file    スクリプトファイル
    * @param _c      スレッドプール
    * @return レスポンス
    */
  private[this] def exec(request:Request, file:File)(implicit _c:ExecutionContext):Future[Response] = {
    val engine = manager.getEngineByName("JavaScript")
    engine.put(ScriptEngine.FILENAME, request.path)

    // JavaScript 処理に渡すリクエスト情報を評価
    val query = queryToJson(request)
    engine.eval("var request = " + Json.stringify(Json.obj(
      "method" -> request.method.name,
      "url" -> request.originalURL,
      "remote" -> request.proxiedRemoteHost,
      "path" -> request.path,
      "query" -> query
    )) + ";")

    // Binding に設定するものは今のところ特にない
    val bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE)
    engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE)

    // スクリプト処理の実行とレスポンスの構築
    val start = System.currentTimeMillis()
    Server.scheduler.runWithTimeout(timeout, onTimeout(request, start, query)) {
      val json = using(new InputStreamReader(new BufferedInputStream(new FileInputStream(file)), StandardCharsets.UTF_8)) { in =>
        try {
          toJSON(engine.eval(in))
        } catch {
          case ex:Throwable if !ex.isInstanceOf[ThreadDeath] =>
            Json.obj("error" -> "script_error", "description" -> ex.toString)
        }
      }
      val jsonStr = Json.stringify(json)
      if (logger.isDebugEnabled) {
        val tm = System.currentTimeMillis() - start
        logger.debug(f"${request.path}%s: $tm%,dms: ${Json.stringify(query)} => $jsonStr")
      }
      jsonResponse(jsonStr)
    }
  }

  /**
    * 指定された JSON オブジェクトからレスポンスを作成する。
    *
    * @param json JSON
    * @return レスポンス
    */
  private[this] def jsonResponse(json:JsValue):Response = jsonResponse(Json.stringify(json))

  /**
    * 指定された JSON 文字列からレスポンスを作成する。
    *
    * @param json JSON 文字列
    * @return レスポンス
    */
  private[this] def jsonResponse(json:String):Response = {
    val jsonBinary = json.getBytes(StandardCharsets.UTF_8)
    val res = Response(Version.Http11, Status.Ok, Reader.fromBuf(Bufs.sharedBuf(jsonBinary:_*)))
    res.contentType = "text/json; charset=UTF-8"
    res.contentLength = jsonBinary.length
    res.cacheControl = "no-cache"
    res
  }

  /**
    * リクエストされたクエリー文字列や JSON を JavaScript しょりに渡すために JsValue に変換する。
    *
    * @param request リクエスト
    * @return JsValue
    */
  private[this] def queryToJson(request:Request):JsValue = {
    if (request.method == Method.Post && request.mediaType.exists(c => c == "text/json" || c == "application/json")) {
      Json.parse(request.getContentString())
    } else if (request.method == Method.Get || request.mediaType.contains(MediaType.WwwForm)) {
      JsObject(request.getParams().asScala.map { e =>
        (e.getKey, JsString(e.getValue))
      }.groupBy(_._1).mapValues { i => JsArray(i.map(_._2)) })
    } else JsObject.empty
  }

  /**
    * JavaScript 処理からの返値を JSON オブジェクトに変換する。
    *
    * @param value 変換する値
    * @return JSON オブジェクト
    * @throws StackOverflowError 返値に循環参照が含まれている場合
    */
  private[this] def toJSON(value:AnyRef):JsValue = value match {
    case null => JsNull
    case o:JSObject =>
      if (o.isArray) {
        JsArray(o.values().asScala.map(toJSON).toSeq)
      } else {
        JsObject(o.keySet().asScala.map { key => (key, o.getMember(key)) }.toMap.mapValues(toJSON))
      }
    case s:String => JsString(s)
    case i:Integer => JsNumber(BigDecimal(i))
    case i:java.lang.Long => JsNumber(BigDecimal(i))
    case i:java.lang.Float => if(i.isNaN) JsNull else JsNumber(BigDecimal.decimal(i))
    case i:java.lang.Double => if(i.isNaN) JsNull else JsNumber(BigDecimal(i))
    case i:java.math.BigDecimal => JsNumber(i)
    case i:java.lang.Boolean => JsBoolean(i)
    case _:Undefined => JsNull
    case unknown => JsString(unknown.toString)
  }

  /**
    * スクリプト処理でタイムアウトが発生した場合の処理。
    *
    * @param request リクエスト
    * @param thread  タイムアウトしたスレッド
    * @return タイムアウト時のレスポンス
    */
  private[this] def onTimeout(request:Request, start:Long, query:JsValue)(thread:Thread):Future[Response] = {
    // 無限ループ等になっている可能性もあるので割り込みで停止しなかければ 3 秒後に強制停止　
    // ※Scala では @SurppressWarnings("deprecation") が使用できないためリフレクションでコンパイル警告を抑止
    thread.interrupt()
    Server.scheduler.at(3 * 1000) {
      if (thread.isAlive) {
        val stop = thread.getClass.getMethod("stop")
        stop.setAccessible(true)
        stop.invoke(thread)
      }
    }
    val tm = System.currentTimeMillis() - start
    logger.warn(f"${request.path}: $tm%,dms: ${Json.stringify(query)} => timeout")
    val json = Json.obj("error" -> "timeout", "description" -> f"script was not return for $tm%,dms")
    Future.successful(jsonResponse(json))
  }

}
