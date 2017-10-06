package at.hazm.webserver.handler

import java.io.{File, FileNotFoundException}
import java.nio.file.Path
import java.util.Date

import at.hazm._
import at.hazm.util.Cache
import at.hazm.webserver.{TemplateEngine, _}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.io.Reader
import org.slf4j.LoggerFactory

/**
  * ローカルファイルシステム上のファイルに対してテンプレート処理を行うリクエストハンドラです。
  *
  * @param docroot  ドキュメントルート
  * @param cacheDir キャッシュファイルの保存先ディレクトリ
  * @param mime     MIME-Type
  * @param manager  テンプレートマネージャ
  */
class TemplateHandler(docroot:Path, cacheDir:Path, mime:Cache[MimeType], manager:TemplateEngine.Manager) extends RequestHandler(docroot) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  override def apply(request:Request):Option[Response] = FileHandler.mapLocalFile(docroot, request.uri) match {
    case Some(file) =>
      // リクエスト URI とマッピングしたローカルファイルに対して、ファイルが存在するのであれば FileHandler によって
      // 処理が行われるためテンプレートハンドラは何もしない
      if(file.exists()) None else {
        val cache = FileHandler.mapLocalFile(cacheDir, request.uri).get
        try {
          val force = request.headerMap.get("Cache-Control").orElse(request.headerMap.get("Pragma")).contains("no-cache")
          manager.transform(file, cache, Map(
            "method" -> request.method.name,
            "uri" -> request.uri,
            "path" -> request.path,
            "host" -> request.requestedHost.getOrElse("localhost"),
            "scheme" -> request.requestedProto
          ), force).flatMap { lastModified =>
            FileHandler.ifModifiedSince(request, lastModified).orElse {
              Some(on(Response(Version.Http11, Status.Ok, Reader.fromFile(cache))) { res =>
                res.headerMap.add("Last-Modified", new Date(lastModified))
                res.contentLength = cache.length()
                res.contentType = mime.get.apply(cache.getExtension.toLowerCase)
              })
            }
          }
        } catch {
          case ex:FileNotFoundException =>
            logger.error(s"template file not found: requested file: $file", ex)
            Some(getErrorResponse(request, Status.InternalServerError))
          case ex:Exception =>
            logger.error(s"template compile error: ${request.path} -> $file", ex)
            Some(getErrorResponse(request, Status.InternalServerError))
        }
      }
    case None =>
      logger.debug(s"request uri cannot map to local file system: ${request.path}")
      Some(getErrorResponse(request, Status.BadRequest))
  }

}
