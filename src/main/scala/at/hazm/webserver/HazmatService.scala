package at.hazm.webserver

import java.nio.charset.StandardCharsets
import java.util.Date

import at.hazm.on
import at.hazm.webserver.handler.{FileHandler, TemplateHandler}
import at.hazm.webserver.templates.XSLTEngine
import com.twitter.finagle.http._
import com.twitter.finagle.{Service => TFService}
import com.twitter.io.{Buf, Bufs, Reader}
import com.twitter.util.Future

class HazmatService(context:Context) extends TFService[Request, Response] {

  private[this] val handlers = Seq(
    new TemplateHandler(context.docroot.toPath, context.cache.toPath,
      new TemplateEngine.Manager(context.config.server.get.template.updateCheckInterval, new XSLTEngine())),
    new FileHandler(context.docroot.toPath, context.config.server.get.server.sendBufferSize)
  )

  def apply(request:Request):Future[Response] = {
    val response = try {
      handlers.view.map(_.apply(request)).collectFirst { case Some(res) => res }.getOrElse {
        Response(Version.Http11, Status.NotFound)
      }
    } catch {
      case ex:Throwable =>
        context.report(s"unexpected error: ${request.proxiedRemoteHost}: ${request.method.name} ${request.uri}${request.userAgent.map { ua => s": $ua" }.getOrElse("")}", ex)
        on(Response(Version.Http11, Status.InternalServerError, Reader.fromBuf(Bufs.sharedBuf(serverError:_*)))) { res =>
          res.cacheControl = "no-cache"
          res.contentType = "text/html; charset=UTF-8"
        }
    }
    response.headerMap.add(Fields.Date, new Date())
    response.server = Server.Version
    Future.value(response)
  }

  private[this] val serverError =
    """<html>
      |<body>
      |<h1>500 Internal Server Error</h1>
      |</body>
      |</html>""".stripMargin.getBytes(StandardCharsets.UTF_8)
}