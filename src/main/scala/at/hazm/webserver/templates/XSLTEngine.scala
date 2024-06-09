package at.hazm.webserver.templates

import at.hazm.webserver.templates.XSLTEngine._
import at.hazm.webserver.templates.xml.{DocumentProcessor, DocumentWriter, PrettifyProcessor, XMLLoader}
import at.hazm.webserver.{Dependency, TemplateEngine}
import org.slf4j.LoggerFactory

import java.io._
import java.nio.charset.StandardCharsets
import java.util.ServiceLoader
import scala.collection.JavaConverters._

/**
  * XML ファイルに XSL を適用するテンプレートエンジンです。
  */
class XSLTEngine extends TemplateEngine {
  private[this] var processors: Seq[DocumentProcessor] = Seq.empty

  override def setRoot(root: File): Unit = {
    // PrettyPrint は最後に行う (toSeq では遅延処理で再読込可能にならない)
    val services = ServiceLoader.load(classOf[DocumentProcessor]).asScala.toList
    this.processors = (services :+ new PrettifyProcessor()).map { proc =>
      logger.debug(s"using document processor: ${proc.getClass.getSimpleName} @ ${Thread.currentThread().getName}")
      proc.setRoot(root)
      proc
    }
  }

  override def extensionMap: Map[String, String] = Map("xml" -> "html", "xhtml" -> "html")

  override def transform(file: File, in: InputStream, out: OutputStream, param: => Map[String, String]): Dependency = {

    // XSL を適用した DOM をロード
    val (doc, dependency) = XMLLoader.load(in, file.toURI.toURL, param)

    // プロセッサーの適用
    val dependencies = processors.map(_.process(doc, file))

    val o = new OutputStreamWriter(new BufferedOutputStream(out), StandardCharsets.UTF_8)
    DocumentWriter.write(o, StandardCharsets.UTF_8, doc)
    o.flush()

    Dependency(file.toURI.toURL) + dependency + dependencies.reduceLeftOption(_ + _).getOrElse(Dependency())
  }

}

object XSLTEngine {
  private[XSLTEngine] val logger = LoggerFactory.getLogger(classOf[XSLTEngine])
}