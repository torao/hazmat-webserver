package at.hazm.webserver.handler

import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.Date

import at.hazm.on
import at.hazm.webserver.{RequestHandler, TemplateEngine}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.io.Reader
import org.slf4j.LoggerFactory

class TemplateHandler(docroot:Path, cachedir:Path, manager:TemplateEngine.Manager) extends RequestHandler(docroot) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  override def apply(request:Request):Option[Response] = FileHandler.mapLocalFile(docroot, request.uri) match {
    case Some(file) =>
      if (file.exists()) None else {
        val cache = FileHandler.mapLocalFile(cachedir, request.uri).get
        try {
          manager.transform(file, cache).flatMap { lastModified =>
            FileHandler.ifModifiedSince(request, lastModified).orElse{
              Some(on(Response(Version.Http11, Status.Ok, Reader.fromFile(cache))) { res =>
                res.headerMap.add("Last-Modified", new Date(lastModified))
                res.contentLength = cache.length()
              })
            }
          }
        } catch {
          case ex:FileNotFoundException =>
            logger.error(s"template file not found: requested file: $file", ex)
            Some(getErrorResponse(Status.InternalServerError))
          case ex:Exception =>
            logger.error(s"unexpected error: $file", ex)
            Some(getErrorResponse(Status.InternalServerError))
        }
      }
    case None =>
      Some(getErrorResponse(Status.BadRequest))
  }
}
