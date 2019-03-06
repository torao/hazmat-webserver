package at.hazm.webserver.templates

import java.io._
import java.nio.charset.StandardCharsets

import at.hazm.webserver.templates.xml.{DocumentProcessor, DocumentWriter, PrettifyProcessor, ScriptProcessor, XMLLoader}
import at.hazm.webserver.{Dependency, TemplateEngine}

/**
  * XML ファイルに XSL を適用するテンプレートエンジンです。
  */
class XSLTEngine extends TemplateEngine {
  private[this] var processors: List[DocumentProcessor] = List.empty

  override def setRoot(root: File): Unit = {
    this.processors = List[DocumentProcessor](
      new ScriptProcessor(new File(root, "scripts/xslt-postprocess").getCanonicalFile, new File(root, "docroot").getCanonicalFile),
      new PrettifyProcessor()
    )
  }

  override def extensionMap: Map[String, String] = Map("xml" -> "html", "xhtml" -> "html")

  override def transform(file: File, in: InputStream, out: OutputStream, param: => Map[String, String]): Dependency = {

    // XSL を適用した DOM をロード
    val (doc, dependency) = XMLLoader.load(in, file.toURI.toURL, param)

    // プロセッサーの適用
    val dependencies = processors.map { proc =>
      proc.process(doc, file)
    }

    val o = new OutputStreamWriter(new BufferedOutputStream(out), StandardCharsets.UTF_8)
    DocumentWriter.write(o, StandardCharsets.UTF_8, doc)
    o.flush()

    Dependency(file.toURI.toURL) + dependency + dependencies.reduceLeftOption(_ + _).getOrElse(Dependency())
  }

}
