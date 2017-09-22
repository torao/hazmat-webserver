package at.hazm.webserver

import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Properties, Timer, TimerTask}

import at.hazm.using
import com.twitter.finagle.{Http, ListeningServer}
import com.twitter.util.{Await, Duration}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future, Promise}
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
    logger.info(s"starting server: ${context.dir} (directory ${if(context.dir.exists()) "available" else "not available"})")
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
    logger.debug(s"${classOf[Server].getName}.main(${args.mkString("\"", "\", \"", "\"")})")

    val dir = args.headOption.getOrElse(".")
    val context = new Context(dir, 2 * 1000L)
    val server = new Server()

    while (true) {
      context.config.server.get
      context.config.server.onUpdate { (_, _) =>
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
    s"HazMat Server/1.0 Finagle/$finagleVersion"
  }

  private[this] def initLogging():Unit = {
    import com.twitter.logging.{Formatter, Handler, Level, LoggerFactory => TWLoggerFactory}
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

  object scheduler {
    /** サーバ内で使用するタイマー。 */
    private[this] val timer = new Timer("HazMat Timer", true)

    trait Cancelable {
      def cancel():Unit
    }

    /**
      * このスケジューラにおいて処理がタイムアウトした時に発生する例外。
      *
      * @param msg 例外メッセージ
      */
    case class TimeoutException(msg:String) extends Exception(msg)

    /**
      * 指定された時刻後に処理を実行する。
      *
      * @param delay 遅延時間 (ミリ秒)
      * @param f     実行する処理
      * @tparam T 処理結果の型
      * @return (処理結果, 処理キャンセル用のコールバック)
      */
    def at[T](delay:Long)(f: => T):(Future[T], Cancelable) = {
      val promise = Promise[T]()
      val task = new TimerTask() {
        override def run():Unit = {
          val result = Try(f)
          promise.synchronized {
            if (!promise.isCompleted) promise.complete(result)
          }
        }
      }
      timer.schedule(task, delay)
      (promise.future, () => {
        task.cancel()
        promise.synchronized {
          if(!promise.isCompleted) promise.failure(TimeoutException("task canceled"))
        }
      })
    }

    private[this] def defaultOnTimeout(t:Thread):Future[_] = Future.failed(TimeoutException("execution timed-out"))

    /**
      * タイムアウト処理付きの非同期実行。
      * 指定された処理を非同期で起動するとともに、タイムアウト時間になったら割り込みを行いコールバックする。
      *
      * @param interval  処理タイムアウトまでの時間 (ミリ秒)
      * @param onTimeout 処理がタイムアウトした時のコールバック。返値で Future の結果を置き換える。
      * @param f         非同期で実行する処理
      * @param _context  スレッドプール
      * @tparam T 非同期処理の結果の型
      * @return 処理結果
      */
    def runWithTimeout[T](interval:Long, onTimeout:(Thread) => Future[T] = defaultOnTimeout _)(f: => T)(implicit _context:ExecutionContext):Future[T] = {
      val promise = Promise[T]()
      Future {
        val current = Thread.currentThread()
        val (_, call) = at(interval) {
          promise.synchronized {
            current.interrupt()
            promise.completeWith(onTimeout(current))
          }
        }
        try {
          val result = f
          promise.synchronized {
            if (!promise.isCompleted) promise.success(result)
          }
        } catch {
          case ex:Throwable if !ex.isInstanceOf[ThreadDeath] =>
            logger.warn("unhandled exception", ex)
            promise.synchronized {
              if (!promise.isCompleted) promise.failure(ex)
            }
        } finally {
          call.cancel()
        }
      }
      promise.future
    }
  }

}
