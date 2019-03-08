package at.hazm.webserver.handler

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import at.hazm.util.Cache
import at.hazm.webserver.handler.JavaHandler.EEBuilder
import com.twitter.finagle.http._
import org.codehaus.janino.ExpressionEvaluator
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
class JavaHandler(docroot:Path, timeout:Long, exts:Seq[String], libs:ClassLoader) extends ProcessHandler[ExpressionEvaluator](docroot, timeout, exts, libs, new EEBuilder())  {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  protected def exec(ee:ExpressionEvaluator, request:Request, query:JsValue):AnyRef = {
    ee.evaluate(Array(request))
  }

}

object JavaHandler {
  private[JavaHandler] class EEBuilder extends Cache.Builder[ExpressionEvaluator] {
    /**
      * 指定されたバイナリからキャッシュ用のオブジェクトを生成する。
      *
      * @param binary オブジェクト生成元のバイナリ (None の場合はデータが存在しない場合のデフォルトを生成する)
      * @return バイナリから生成したオブジェクト
      */
    override def compile(uri:URI, binary:Option[Array[Byte]]):ExpressionEvaluator = {
      val expression = binary.map(b => new String(b, StandardCharsets.UTF_8)).getOrElse("\"\"")
      new ExpressionEvaluator(expression, classOf[Object], Array("request"), Array(classOf[Request]))
    }
  }
}