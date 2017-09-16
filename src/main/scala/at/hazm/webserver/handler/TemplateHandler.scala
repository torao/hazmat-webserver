package at.hazm.webserver.handler

import java.io.{File, FileNotFoundException}
import java.nio.file.Path
import java.util.Date

import at.hazm._
import at.hazm.util.Cache
import at.hazm.webserver.TemplateEngine
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.io.Reader
import org.slf4j.LoggerFactory

class TemplateHandler(docroot:Path, cachedir:Path, mime:Cache[MimeType], manager:TemplateEngine.Manager) extends RequestHandler(docroot) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  override def apply(request:Request):Option[Response] = FileHandler.mapLocalFile(docroot, request.uri) match {
    case Some(file) =>
      if (file.exists()) None else transform(request, file)
    case None =>
      Some(getErrorResponse(Status.BadRequest))
  }

  private[this] def transform(request:Request, file:File):Option[Response] = {
    val cache = FileHandler.mapLocalFile(cachedir, request.uri).get
    try {
      manager.transform(file, cache).map { lastModified =>
        FileHandler.ifModifiedSince(request, lastModified).getOrElse{
          on(Response(Version.Http11, Status.Ok, Reader.fromFile(cache))) { res =>
            res.headerMap.add("Last-Modified", new Date(lastModified))
            res.contentLength = cache.length()
            res.contentType = mime.get.apply(cache.getExtension.toLowerCase)
          }
        }
      }
    } catch {
      case ex:FileNotFoundException =>
        logger.error(s"template file not found: requested file: $file", ex)
        Some(getErrorResponse(Status.InternalServerError))
      case ex:Exception =>
        logger.error(s"template compile error: ${request.path} -> $file", ex)
        Some(getErrorResponse(Status.InternalServerError))
    }
  }
}
