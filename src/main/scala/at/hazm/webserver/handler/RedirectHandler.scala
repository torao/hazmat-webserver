package at.hazm.webserver.handler

import java.nio.file.Path

import at.hazm.util.Cache
import at.hazm.webserver.Config
import com.twitter.finagle.http.{Request, Response, Status}
import org.slf4j.LoggerFactory

/**
  * リクエスト URL が設定によってリダイレクトとなっている時にリダイレクトレスポンスを生成します。
  *
  * @param docroot ドキュメントルート
  * @param config  リダイレクト定義を持つサーバ設定
  */
class RedirectHandler(docroot:Path, config:Cache[Config]) extends RequestHandler(docroot) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  override def apply(request:Request):Option[Response] = {
    val map = config.get.redirect
    val path = request.path
    map.find(_._1.matcher(path).matches()).map { case (pattern, url) =>
      val permanent = url.startsWith("!")

      // ※ $1 等の置換を有効にするために replaceAll() を使う
      val newURL = pattern.matcher(path).replaceAll(if(permanent) url.drop(1) else url)
      logger.debug(s"redirecting $path -> $newURL")
      val response = Response(if(permanent) Status.MovedPermanently else Status.TemporaryRedirect)
      response.location = newURL
      response.cacheControl = "no-cache"
      response
    }
  }

}
