package at.hazm.webserver

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import at.hazm.util.Cache
import at.hazm.using
import com.twitter.finagle.{Http, ListeningServer}
import com.twitter.util.{Await, Duration}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.Try


class Server {

  import Server.logger

  private[this] var server:ListeningServer = _
  private[this] val closed = new AtomicBoolean(true)

  /**
    * サーバを開始する。
    *
    * @param context サーバ設定
    */
  def startup(context:Context):Unit = if (closed.compareAndSet(true, false)) {
    logger.debug(s"starting server...")
    context.init()
    val config = context.config.server.get

    val service = new HazmatService(context)

    server = Http.server
      .withStreaming(true)
      .withRequestTimeout(Duration.fromMilliseconds(config.server.requestTimeout))
      .withCompressionLevel(config.server.compressionLevel)
      .withMaxRequestSize(config.server.maxRequestSize)
      .serve(config.server.bindAddress, service)
    logger.info(s"listening on ${config.server.bindAddress.getHostName}:${config.server.bindAddress.getPort}")
    Await.result(server)
  }

  /**
    * サーバを終了する。
    */
  def shutdown():Unit = if (closed.compareAndSet(false, true)) {
    logger.debug("shutting-down server...")
    server.close().onSuccess { _ =>
      logger.info(s"shutdown complete")
    }.onFailure { ex =>
      logger.error(s"shutdown failure", ex)
    }
    server = null
  }

}

object Server {
  initLogging()
  private[Server] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  def main(args:Array[String]):Unit = {

    val context = new Context(if (args.nonEmpty) args.head else ".", 2 * 1000L)
    val server = new Server()

    while(true){
      context.config.server.get
      context.config.server.onUpdate { (_, config) =>
        server.shutdown()
      }
      server.startup(context)
    }
  }

  /**
    * サーバのバージョン文字列。`Server` レスポンスヘッダに使用する。
    */
  val Version:String = {
    val finagleVersion = {
      val candidates = Seq("finagle-core", "finagle-core_2.11", "finagle-core_2.12").map { c =>
        s"/com/twitter/$c/build.properties"
      }
      candidates.flatMap { path =>
        Try {
          val prop = new Properties()
          using(Http.getClass.getResourceAsStream(path)) { in =>
            prop.load(in)
          }
          prop
        }.toOption.map { prop =>
          Option(prop.getProperty("version")).map { version =>
            Option(prop.getProperty("build_name")).map { revision => s"${version}_$revision" }.getOrElse(version)
          }.getOrElse {
            logger.warn(s"finagle version and build revision are not specified: $path")
            "???"
          }
        }
      }.headOption.getOrElse {
        logger.warn(s"finagle version resource is not found on: [${candidates.mkString(", ")}]")
        "???"
      }
    }
    s"HazMat Server/1.0 (Finagle/$finagleVersion)"
  }

  private[this] def initLogging():Unit = {
    import com.twitter.logging.{Formatter, Handler, LoggerFactory => TWLoggerFactory, Level}
    class Log4jHandler(formatter:Formatter = new Formatter(), level:Option[Level] = None) extends Handler(formatter, level) {

      import java.util.{logging => jlog}

      def publish(record:jlog.LogRecord):Unit = {
        val logger = LoggerFactory.getLogger(record.getLoggerName)
        record.getLevel.intValue() match {
          case l if l >= jlog.Level.SEVERE.intValue() => logger.error(record.getMessage, record.getThrown)
          case l if l >= jlog.Level.WARNING.intValue() => logger.warn(record.getMessage, record.getThrown)
          case l if l >= jlog.Level.INFO.intValue() => logger.info(record.getMessage, record.getThrown)
          case l if l >= jlog.Level.FINE.intValue() => logger.debug(record.getMessage, record.getThrown)
          case _ => logger.info(record.getMessage, record.getThrown)
        }
      }

      def close():Unit = {}

      def flush():Unit = {}
    }

    val factory = TWLoggerFactory(
      node = "",
      level = Some(Level.ALL),
      handlers = { () => new Log4jHandler() } :: Nil
    )
    com.twitter.logging.Logger.configure(factory :: Nil)
  }

}
