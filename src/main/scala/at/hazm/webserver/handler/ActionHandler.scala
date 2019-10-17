package at.hazm.webserver.handler

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.ServiceLoader

import at.hazm.webserver._
import com.twitter.finagle.http._
import com.twitter.io.{Bufs, Reader}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/**
  * リクエスト URL に対するレスポンスをマッピングするためのハンドラ。
  *
  * @param docroot ドキュメントルート
  */
class ActionHandler[T](docroot:Path, timeout:Long) extends RequestHandler(docroot) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] val actions = ServiceLoader.load(classOf[Action]).asScala.toSeq.map { action =>
    logger.debug(s"using action: [${action.uriPrefix}] ${action.getClass.getCanonicalName}")
    action.docroot = docroot.toFile
    action
  }

  override def apply(request:Request):Option[Response] = None

  override def applyAsync(request:Request)(implicit context:ExecutionContext):Future[Option[Response]] = {
    val path = request.path
    val targets = actions.filter { action =>
      path.startsWith(action.uriPrefix) && {
        val postfix = path.substring(action.uriPrefix.length)
        postfix == "" || postfix.charAt(0) == '/'
      }
    }.sortBy(_.uriPrefix)
    if(targets.isEmpty) {
      Future.successful(None)
    } else {
      val start = System.currentTimeMillis()
      Server.scheduler.runWithTimeout(timeout, onTimeout(request, start)) {
        @tailrec
        def _call(i:Int):Option[Response] = if(i < targets.length) {
          val target = targets(i)
          val suburi = request.uri.substring(target.uriPrefix.length)
          val result = try {
            target.apply(request, suburi)
          } catch {
            case ex:Throwable if !ex.isInstanceOf[ThreadDeath] =>
              logger.error(s"[${target.uriPrefix}] ${target.getClass.getName}", ex)
              None
          }
          if(logger.isDebugEnabled) {
            val tm = System.currentTimeMillis() - start
            logger.debug(f"${request.path}%s: $tm%,dms: ${request.uri} => $result")
          }
          if(result.isDefined) result else _call(i + 1)
        } else None

        _call(0)
      }
    }
  }

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
    * スクリプト処理でタイムアウトが発生した場合の処理。
    *
    * @param request リクエスト
    * @param thread  タイムアウトしたスレッド
    * @return タイムアウト時のレスポンス
    */
  private[this] def onTimeout(request:Request, start:Long)(thread:Thread):Future[Option[Response]] = {
    // 無限ループ等になっている可能性もあるので割り込みで停止しなかければ 3 秒後に強制停止　
    // ※Scala では @SurppressWarnings("deprecation") が使用できないためリフレクションでコンパイル警告を抑止
    thread.interrupt()
    Server.scheduler.at(3 * 1000) {
      if(thread.isAlive) {
        val stop = thread.getClass.getMethod("stop")
        stop.setAccessible(true)
        stop.invoke(thread)
      }
    }
    val tm = System.currentTimeMillis() - start
    logger.warn(f"${request.path}: $tm%,dms: ${request.uri} => timeout")
    val json = Json.obj("error" -> "timeout", "description" -> f"script was not return for $tm%,dms")
    Future.successful(Some(jsonResponse(Json.stringify(json))))
  }

}
