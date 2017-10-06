package at.hazm.webserver

import java.io.File
import java.net.{InetSocketAddress, URI, URL}
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

import com.twitter.util.StorageUnit
import com.typesafe.config.{ConfigFactory, ConfigUtil}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

class Config(val source:URL, val config:com.typesafe.config.Config) {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] def _get[T](key:String, default: => T)(implicit converter:(String) => Either[String, T]):T = {
    Try(config.getString(s"$key")).toOption match {
      case Some(value) =>
        converter(value) match {
          case Right(result) =>
            logger.debug(s"$key = $result")
            result
          case Left(message) =>
            logger.warn(s"$key = $value; $message (apply $default)")
            default
        }
      case None =>
        logger.debug(s"$key = $default (default)")
        default
    }
  }

  private[this] def getMap(key:String):Map[String,String] = Try(config.getConfig(key)).toOption.map{ cs =>
    cs.entrySet().asScala.map(x => x.getKey ).toSeq.map{ name =>
      (ConfigUtil.splitPath(name).get(0), cs.getString(name))
    }.toMap
  }.getOrElse(Map.empty)

  private[this] def resolve(path:String):File = {
    (if (source.getProtocol == "file") {
      new File(source.toURI).getParentFile
    } else new File(".")).getAbsoluteFile.toPath.resolve(path).toFile
  }

  private[this] class ExceptableConverter[T](converter:(String) => T) extends Function[String, Either[String, T]] {
    override def apply(value:String):Either[String, T] = Try(converter(value)) match {
      case Success(result) => Right(result)
      case Failure(ex) => Left(ex.toString)
    }
  }

  private[this] implicit val _StringConverter = { value:String => Right(value) }

  private[this] implicit object _IntConverter extends ExceptableConverter[Int](_.toInt)

  private[this] implicit object _LongConverter extends ExceptableConverter[Long](_.toLong)

  private[this] implicit val _StorageUnitConverter = Config.storageSize _

  object server {
    private[this] def get[T](key:String, default:T)(implicit converter:(String) => Either[String, T]):T = _get[T](s"server.$key", default)(converter)

    val requestTimeout:Long = get("request-timeout", 30) * 1000L
    val compressionLevel:Int = get("compression-level", 0)
    val maxRequestSize:StorageUnit = get("max-request-size", StorageUnit.fromKilobytes(500))
    val bindAddress:InetSocketAddress = get("bind-address", new InetSocketAddress(80)) { value:String =>
      val HostPort = "(.*):(\\d+)".r
      try {
        value match {
          case HostPort("*", port) => Right(new InetSocketAddress(port.toInt))
          case HostPort(host, port) => Right(new InetSocketAddress(host, port.toInt))
          case host => Right(new InetSocketAddress(host, 80))
        }
      } catch {
        case _:NumberFormatException => Left(s"invalid port number")
      }
    }
    val docroot:File = get("docroot", resolve(".")) { path => Right(resolve(path)) }
    val sendBufferSize:Int = get("send-buffer-size", 4 * 1024)
  }

  object template {
    private[this] def get[T](key:String, default:T)(implicit converter:(String) => Either[String, T]):T = _get[T](s"template.$key", default)(converter)

    val updateCheckInterval:Long = get("update-check-interval", 2) * 1000L
  }

  object script {
    private[this] def get[T](key:String, default:T)(implicit converter:(String) => Either[String, T]):T = _get[T](s"script.$key", default)(converter)
    private[this] def getArray[T](key:String, default:String)(implicit converter:(String) => Either[String, T]):Seq[T] = {
      get(key, default).split(",").filter(_.nonEmpty).map(converter).collect{ case Right(value) => value }
    }

    val timeout:Long = get("timeout", 10 * 1000L)
    val extensions:Seq[String] = getArray("extensions", ".xjs")
  }

  /**
    * リダイレクト URI のパターンとそのリダイレクト先。
    */
  val redirect:Seq[(Pattern,String)] = getMap("redirect").toSeq.map{ case (pattern, url) =>
    (Pattern.compile(pattern), url)
  }

  /**
    * エラーの発生したパスと対応するテンプレート (XSL ファイル)。
    */
  val error:Seq[(Pattern,String)] = getMap("error").toSeq.map{ case (pattern, path) =>
    (Pattern.compile(pattern), path)
  }

}

object Config {

  object Builder extends at.hazm.util.Cache.Builder[Config] {
    override def compile(uri:URI, binary:Option[Array[Byte]]):Config = {
      val base = binary match {
        case Some(b) =>
          ConfigFactory.parseString(new String(b, StandardCharsets.UTF_8))
        case None =>
          ConfigFactory.load()
      }
      new Config(uri.toURL, base)
    }
  }

  def storageSize(value:String):Either[String, StorageUnit] = {
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