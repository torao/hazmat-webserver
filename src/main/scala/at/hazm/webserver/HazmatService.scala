package at.hazm.webserver

import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.Executors

import at.hazm.on
import at.hazm.webserver.handler.{FileHandler, ScriptHandler, TemplateHandler}
import at.hazm.webserver.templates.{SASSEngine, TypeScriptEngine, XSLTEngine}
import com.twitter.finagle.http._
import com.twitter.finagle.{Service => TFService}
import com.twitter.io.{Bufs, Reader}
import com.twitter.util.{Future, Promise}

import scala.concurrent.{ExecutionContext, Future => SFuture}
import scala.util.{Failure, Success}

class HazmatService(context:Context) extends TFService[Request, Response] {
  private[this] implicit val _context = ExecutionContext.fromExecutor(Executors.newCachedThreadPool((r:Runnable) => {
    val t = new Thread(r, "HazMat")
    t.setDaemon(true)
    t
  }))

  private[this] val serverConfig = context.config.server.get

  /** 非同期で実行するリクエストハンドラ */
  private[this] val asyncHandlers = Seq(
    new ScriptHandler(context.docroot.toPath, serverConfig.script.timeout, serverConfig.script.extensions)
  )

  /** 同期で実行するリクエストハンドラ。 */
  private[this] val handlers = Seq(
    new TemplateHandler(context.docroot.toPath, context.cache.toPath, context.config.mime,
      new TemplateEngine.Manager(serverConfig.template.updateCheckInterval,
        new XSLTEngine(), new SASSEngine(), new TypeScriptEngine())
    ),
    new FileHandler(context.docroot.toPath, serverConfig.server.sendBufferSize, context.config.mime)
  )

  def apply(request:Request):Future[Response] = {
    val promise = Promise[Response]()
    SFuture.sequence(asyncHandlers.map(_.applyAsync(request))).map(_.flatten.headOption).map {
      case Some(res) => res
      case None =>
        try {
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
    }.onComplete {
      case Success(response) =>
        response.headerMap.add(Fields.Date, new Date())
        response.server = Server.Version
        promise.setValue(response)
      case Failure(ex) =>
        promise.setException(ex)
    }
    promise
  }

  private[this] val serverError =
    """<html>
      |<body>
      |<h1>500 Internal Server Error</h1>
      |</body>
      |</html>""".stripMargin.getBytes(StandardCharsets.UTF_8)
}