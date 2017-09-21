package at.hazm.webserver.templates

import java.io._
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import at.hazm.webserver.{Dependency, TemplateEngine}
import at.hazm.webserver.templates.xml.{DocumentProcessor, XMLLoader}

class XSLTEngine extends TemplateEngine {

  private[this] val processors = List[DocumentProcessor]()

  override def extensionMap:Map[String, String] = Map("xml" -> "html", "xhtml" -> "html")

  override def transform(file:File, in:InputStream, out:OutputStream, param: => Map[String, String]):Dependency = {

    val (doc, dependency) = XMLLoader.load(in, file.toURI.toURL, param)

    val dependencies = processors.map{ proc =>
      proc.process(doc, file.toURI.toURL)
    }

    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.transform(new DOMSource(doc), new StreamResult(out))
    Dependency(file.toURI.toURL) + dependency + dependencies.reduceLeftOption(_+_).getOrElse(Dependency())
  }

}
