package at.hazm.webserver

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.LogRecord

import at.hazm.util.Cache
import at.hazm.util.Cache.FileSource
import at.hazm.webserver.handler.MimeType
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

import scala.annotation.tailrec

class Context private[this](val dir:File, timestampCheckInterval:Long) {
  private[this] val logger = LoggerFactory.getLogger(getClass)
  private[this] val confDir = new File(dir, "conf")

  locally {
    val delay = 15 * 1000
    val xml = new File(confDir, "log4j.xml")
    if(xml.isFile){
      org.apache.log4j.xml.DOMConfigurator.configureAndWatch(xml.getAbsolutePath, delay)
      logger.debug(f"initiate logger: $xml ($delay%,dms)")
    } else {
      val prop = new File(confDir, "log4j.properties")
      if(prop.isFile) {
        org.apache.log4j.PropertyConfigurator.configureAndWatch(prop.getAbsolutePath, delay)
        logger.debug(f"initiate logger: $prop ($delay%,dms)")
      }
    }
  }

  logger.debug(s"application context: ${dir.getAbsolutePath}")

  def this(dir:String, timestampCheckInterval:Long) = this(new File(dir).getCanonicalFile, timestampCheckInterval)

  val docroot = new File(dir, "docroot")
  if(! docroot.exists()){
    logger.warn(s"docroot directory is not exist: ${docroot.getAbsolutePath}")
  }

  val cache = new File(dir, "cache")

  object config {
    if(! confDir.exists()){
      logger.warn(s"configuration directory is not exist: ${confDir.getAbsolutePath} (not exists)")
    }

    private[this] val source = new FileSource(confDir)
    private[this] val cache = new Cache.Manager(source, Config.Builder, timestampCheckInterval)
    val server:Cache[Config] = cache.cache("server.conf")
    val mime:Cache[MimeType] = new Cache("mime.conf", source, MimeType.Builder, timestampCheckInterval)
  }

  def init():Unit = {
    reportedError.set(Set.empty)
  }

  private[this] val reportedError = new AtomicReference[Set[Long]](Set.empty)

  /**
    * 指定されたエラーを報告する。この機能はエラーが既に報告済みの場合はスタックトレースを省略する。報告済みとは、
    * サーバが起動してからパスが同一のエラーが既に報告されているかで判断する。
    *
    * @param message エラーメッセージ
    * @param ex      報告する例外
    */
  def report(message:String, ex:Throwable):Unit = {
    // この例外のスタックトレースをハッシュ化 (同一の例外は同じハッシュ値になるはず)
    val hash = ex.getStackTrace.foldLeft(MessageDigest.getInstance("SHA-1")) { case (md, st) =>
      val stackFrame = s"${st.getClassName}.${st.getMethodName}(${st.getFileName}:${st.getLineNumber})"
      md.update(stackFrame.getBytes(StandardCharsets.UTF_8))
      md
    }.digest().take(8).zipWithIndex.map { case (b, i) => (b & 0xFF).toLong << (i * 8) }.reduceLeft(_ | _)

    @tailrec
    def _report():Unit = {
      val reported = reportedError.get()
      if (reported.contains(hash)) {
        logger.error(message)
      } else if (reportedError.compareAndSet(reported, reported + hash)) {
        logger.error(message, ex)
      } else _report()
    }

    _report()
  }
}