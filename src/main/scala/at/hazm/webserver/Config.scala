package at.hazm.webserver

import com.twitter.util.StorageUnit
import com.typesafe.config._
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, JsString, Json}

import java.io.File
import java.net.{InetSocketAddress, URI, URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class Config(val source: URL, val config: com.typesafe.config.Config) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] def getByFQN[T](key: String, default: => T)(implicit converter: String => Either[String, T]): T = {
    Try(config.getString(key)) match {
      case Success(value) =>
        converter(value) match {
          case Right(result) =>
            logger.debug(s"$key = $result")
            result
          case Left(message) =>
            logger.warn(s"$key = $value; $message (apply $default)")
            default
        }
      case Failure(ex) =>
        logger.debug(s"$key = '$default' (default); $ex")
        default
    }
  }

  private[this] def getArrayByFQN[T](key: String, default: => T)(implicit converter: String => Either[String, T]): Seq[T] = {
    Try(config.getStringList(key)) match {
      case Success(list) =>
        val converted = list.asScala.map(value => converter(value))
        val results = list.asScala.map { value =>
          converter(value) match {
            case Right(result) =>
              logger.debug(s"$key = $result")
              result
            case Left(message) =>
              logger.warn(s"$key = $value; $message (apply $default)")
              default
          }
        }
        val log: String => Unit = if (converted.exists(_.isLeft)) logger.warn else logger.debug
        log(s"$key = ${
          results.zip(converted).zip(list.asScala).map {
            case ((result, Right(_)), _) => s"$result"
            case ((result, Left(message)), value) => s"$value; $message (apply $result)"
          }.mkString("[", ", ", "]")
        }")
        results
      case Failure(ex) =>
        logger.debug(s"$key = [] (default); $ex")
        Seq.empty
    }
  }

  private[this] def getMap(key: String): Map[String, String] = Try(config.getConfig(key)).toOption.map { cs =>
    cs.entrySet().asScala.map(x => x.getKey).toSeq.map { name =>
      (ConfigUtil.splitPath(name).get(0), cs.getString(name))
    }.toMap
  }.getOrElse(Map.empty)

  private[this] def resolve(path: String): File = {
    (if (source.getProtocol == "file") {
      new File(source.toURI).getParentFile
    } else new File(".")).getAbsoluteFile.toPath.resolve(path).toFile
  }

  private[this] class ExceptableConverter[T](converter: String => T) extends Function[String, Either[String, T]] {
    override def apply(value: String): Either[String, T] = Try(converter(value)) match {
      case Success(result) => Right(result)
      case Failure(ex) => Left(ex.toString)
    }
  }

  private[this] implicit val _StringConverter: String => Right[Nothing, String] = { value: String => Right(value) }

  private[this] implicit object _BooleanConverter extends ExceptableConverter[Boolean](_.toBoolean)

  private[this] implicit object _IntConverter extends ExceptableConverter[Int](_.toInt)

  private[this] implicit object _LongConverter extends ExceptableConverter[Long](_.toLong)

  private[this] implicit val _StorageUnitConverter: String => Either[String, StorageUnit] = Config.storageSize

  object server {
    private[this] def get[T](key: String, default: T)(implicit converter: String => Either[String, T]): T = getByFQN[T](s"server.$key", default)(converter)

    val DefaultPort = 8089

    val requestTimeout: Long = get("request-timeout", 30) * 1000L
    val compressionLevel: Int = get("compression-level", 0)
    val maxRequestSize: StorageUnit = get("max-request-size", StorageUnit.fromKilobytes(500))
    val bindAddress: InetSocketAddress = get("bind-address", new InetSocketAddress(DefaultPort)) { value: String =>
      val HostPort = "(.*):(\\d+)".r
      try {
        value match {
          case HostPort("*", port) => Right(new InetSocketAddress(port.toInt))
          case HostPort(host, port) => Right(new InetSocketAddress(host, port.toInt))
          case host => Right(new InetSocketAddress(host, DefaultPort))
        }
      } catch {
        case _: NumberFormatException => Left(s"invalid port number")
      }
    }
    val docroot: File = get("docroot", resolve(".")) { path => Right(resolve(path)) }
    val sendBufferSize: Int = get("send-buffer-size", 4 * 1024)
  }

  object template {
    private[this] def get[T](key: String, default: T)(implicit converter: String => Either[String, T]): T = getByFQN[T](s"template.$key", default)(converter)

    val updateCheckInterval: Long = get("update-check-interval", 2) * 1000L
  }

  /**
    * 外部コマンド/シェルスクリプト実行環境の設定。
    */
  object shell {

    /** Map[ドット付き拡張子, インタープリタ] */
    val interpreters: Map[String, String] = {
      val result = Try(config.getObject("shell.interpreters")).toOption.map { obj =>
        obj.entrySet().asScala.collect {
          case e if e.getValue.valueType() == ConfigValueType.STRING =>
            (e.getKey, e.getValue.unwrapped().toString)
        }.toMap
      }.getOrElse(Map.empty)
      logger.debug(s"shell.interpreters = ${Json.stringify(JsObject(result.mapValues(s => JsString(s))))}")
      result
    }

  }

  /**
    * 外部実行スクリプトの設定。
    */
  object cgi {
    private[this] def get[T](key: String, default: T)(implicit converter: String => Either[String, T]): T = getByFQN[T](s"cgi.$key", default)(converter)

    val enabled: Boolean = get("enabled", false)
    val timeout: Long = get("timeout", 10 * 1000L)
    val prefix: String = get("prefix", "/api/")
  }

  object script {
    private[this] def fqn(key: String): String = s"script.$key"

    private[this] def get[T](key: String, default: T)(implicit converter: String => Either[String, T]): T = getByFQN[T](fqn(key), default)(converter)

    private[this] def getArray[T](key: String, default: String)(implicit converter: String => Either[String, T]): Seq[T] = {
      get(key, default).split(",").filter(_.nonEmpty).map(converter).collect { case Right(value) => value }
    }

    val timeout: Long = get("timeout", 10 * 1000L)
    val extensions: Seq[String] = getArray("extensions", ".xjs")

    val javaExtensions: Seq[String] = getArray("extensions-java", ".java")

    def libs(root: File): ClassLoader = {
      val urls = getArrayByFQN(fqn("libs"), "").filter(_.nonEmpty).map { d =>
        val dir = new File(d)
        if (dir.isAbsolute) dir else new File(root, d)
      }.flatMap { base =>
        def findJars(dir: File): Seq[URL] = {
          dir.listFiles().filter(f => f.isFile && f.getName.endsWith(".jar")).map { file =>
            logger.debug(s"add script library: ${base.getName}/${base.toURI.relativize(file.toURI)}")
            file.toURI.toURL
          } ++ dir.listFiles().filter(_.isDirectory).flatMap(findJars)
        }

        if (base.isDirectory) findJars(base) else {
          logger.warn(s"script library directory is not exist: ${base.getAbsolutePath}")
          Seq.empty
        }
      }.toArray
      val defaultLoader = Thread.currentThread().getContextClassLoader
      if (urls.isEmpty) defaultLoader else new URLClassLoader(urls, defaultLoader)
    }
  }

  /**
    * リダイレクト URI のパターンとそのリダイレクト先。
    */
  val redirect: Seq[(Pattern, String)] = getMap("redirect").toSeq.map { case (pattern, url) =>
    (Pattern.compile(pattern), url)
  }

  /**
    * エラーの発生したパスと対応するテンプレート (XSL ファイル)。
    */
  val error: Seq[(Pattern, String)] = getMap("error").toSeq.map { case (pattern, path) =>
    (Pattern.compile(pattern), path)
  }

}

object Config {

  object Builder extends at.hazm.util.Cache.Builder[Config] {
    override def compile(uri: URI, binary: Option[Array[Byte]]): Config = {
      val base = binary match {
        case Some(b) =>
          ConfigFactory.parseString(new String(b, StandardCharsets.UTF_8))
        case None =>
          ConfigFactory.load()
      }
      new Config(uri.toURL, base)
    }
  }

  def storageSize(value: String): Either[String, StorageUnit] = {
    val unitSize = Seq("", "k", "M", "G", "T", "P").zipWithIndex.map { case (u, exp) => (u.toLowerCase, 1024 * exp) }.toMap
    val pattern = "(\\d+)([a-z]?)".r
    value.trim().toLowerCase match {
      case pattern(size, unit) if Try {
        size.toLong
      }.isSuccess && unitSize.contains(unit) =>
        Right(StorageUnit.fromBytes(size.toLong * unitSize(unit)))
      case _ =>
        Left(s"invalid size or unit: one of ${unitSize.keys.mkString(",")} can use for unit")
    }
  }
}