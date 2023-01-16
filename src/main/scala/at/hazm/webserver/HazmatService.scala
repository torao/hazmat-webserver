package at.hazm.webserver

import at.hazm.on
import at.hazm.webserver.handler._
import at.hazm.webserver.templates.XSLTEngine
import com.twitter.finagle.http._
import com.twitter.finagle.{Service => TFService}
import com.twitter.io.{Buf, Reader}
import com.twitter.util.{Future, Promise}

import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future => SFuture}
import scala.util.{Failure, Success}

class HazmatService(context: Context) extends TFService[Request, Response] {
  private[this] implicit val _context: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool((r: Runnable) => {
    val t = new Thread(r, "HazMat")
    t.setDaemon(true)
    t
  }))

  private[this] val serverConfig = context.config.server.get

  Thread.currentThread().setContextClassLoader(serverConfig.script.libs(context.dir))

  /** 非同期で実行するリクエストハンドラ */
  private[this] val asyncHandlers = {
    val shell = if (serverConfig.cgi.enabled) {
      Seq(new CommandHandler(context.docroot.toPath, serverConfig.cgi.timeout, serverConfig.cgi.prefix, serverConfig.shell.interpreters))
    } else {
      Seq.empty
    }
    shell ++ Seq(
      new ActionHandler(context.docroot.toPath, serverConfig.script.timeout),
      new JavaHandler(context.docroot.toPath, serverConfig.script.timeout, serverConfig.script.javaExtensions, serverConfig.script.libs(context.dir)),
      new ScriptHandler(context.docroot.toPath, serverConfig.script.timeout, serverConfig.script.extensions, serverConfig.script.libs(context.dir))
    )
  }

  /** 同期で実行するリクエストハンドラ。 */
  private[this] val handlers = Seq(
    new RedirectHandler(context.docroot.toPath, context.config.server),
    new TemplateHandler(context.docroot.toPath, context.cache.toPath, context.config.mime,
      new TemplateEngine.Manager(context.dir, serverConfig.template.updateCheckInterval)
    ),
    new FileHandler(context.docroot.toPath, serverConfig.server.sendBufferSize, context.config.mime)
  )

  (asyncHandlers ++ handlers).foreach { h =>
    val xslt = new XSLTEngine()
    xslt.setRoot(context.dir)
    h.config_=(context.config.server)
    h.errorTemplateEngine_=(xslt)
  }

  def apply(request: Request): Future[Response] = {
    val promise = Promise[Response]()
    SFuture.sequence(asyncHandlers.map(_.applyAsync(request))).map(_.flatten.headOption).map {
      case Some(res) => res
      case None =>
        try {
          handlers.view.map(_.apply(request)).collectFirst { case Some(res) => res }.getOrElse {
            Response(Version.Http11, Status.NotFound)
          }
        } catch {
          case ex: Throwable =>
            context.report(s"unexpected error: ${request.proxiedRemoteHost}: ${request.method.name} ${request.uri}${request.userAgent.map { ua => s": $ua" }.getOrElse("")}", ex)
            on(Response(Version.Http11, Status.InternalServerError, Reader.fromBuf(
              Buf.ByteArray.Shared(serverError)))) { res =>
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