package at.hazm.webserver.handler

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.script.{ScriptContext, ScriptEngine, ScriptEngineManager}

import at.hazm.webserver._
import com.twitter.finagle.http._
import org.slf4j.LoggerFactory
import play.api.libs.json._

/**
  * ドキュメントルート上に配置されている JavaScript ファイルをサーバサイドで実行するためのリクエストハンドラ。
  *
  * @param docroot ドキュメントルート
  * @param timeout 実行タイムアウト (ミリ秒)
  * @param exts    対象とするファイルの拡張子 (`".xjs"` など)
  * @param libs    ライブラリディレクトリ
  */
class ScriptHandler(docroot:Path, timeout:Long, exts:Seq[String], libs:ClassLoader) extends ProcessHandler[String](docroot, timeout, exts, libs, (uri:URI, binary:Option[Array[Byte]]) => binary.map(b => new String(b, StandardCharsets.UTF_8)).getOrElse("")) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] val manager = new ScriptEngineManager(loader)

  protected def exec(data:String, request:Request, query:JsValue):AnyRef = {
    val engine = manager.getEngineByName("JavaScript")
    engine.put(ScriptEngine.FILENAME, request.path)

    // JavaScript 処理に渡すリクエスト情報を評価
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
    engine.eval(data)
  }

}
