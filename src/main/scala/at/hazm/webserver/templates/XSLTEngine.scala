package at.hazm.webserver.templates

import java.io._
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.{StreamResult, StreamSource}

import at.hazm.webserver.TemplateEngine
import at.hazm.webserver.TemplateEngine.Dependency
import org.w3c.dom.{Document, Element, ProcessingInstruction}
import org.xml.sax.InputSource

class XSLTEngine extends TemplateEngine {
  override def extensionMap:Map[String, String] = Map("xml" -> "html", "xhtml" -> "html")

  override def transform(file:File, in:InputStream, out:OutputStream, param: => Map[String, String]):Dependency = {

    // XML のロード
    val rawDOM = {
      val factory = DocumentBuilderFactory.newInstance()
      val builder = factory.newDocumentBuilder()
      builder.parse(in, file.toURI.toString)
    }

    // XInclude が含まれているかを評価
    val xincludes = getXInclude(file, rawDOM.getDocumentElement)

    // XInclude が含まれているなら再読み込み
    val dom = if(xincludes.isEmpty) rawDOM
    else {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setXIncludeAware(true)
      val builder = factory.newDocumentBuilder()
      val is = new InputSource(new StringReader(serialize(rawDOM)))
      is.setSystemId(file.toURI.toString)
      builder.parse(is)
    }

    // XSL スタイルシートの取得と適用
    getStylesheet(dom) match {
      case Some(href) =>
        val path = file.toURI.resolve(href)
        val xsl = new StreamSource(path.toURL.openStream())
        val factory = TransformerFactory.newInstance()
        val transformer = factory.newTransformer(xsl)
        param.foreach { case (key, value) =>
          transformer.setParameter(key, value)
        }
        transformer.transform(new DOMSource(dom), new StreamResult(out))
        Dependency(file +: new File(path) +: xincludes:_*)
      case None =>
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(dom), new StreamResult(out))
        Dependency(file +: xincludes:_*)
    }
  }

  private[this] val HREF = ".*href\\s*=\\s*[\"\'](.*?)[\"\'].*".r

  /**
    * 指定されたドキュメント直下に含まれる <?xsl-stylesheet?> 処理命令から href 属性値を取得する。
    */
  private[this] def getStylesheet(doc:Document):Option[String] = {
    val items = doc.getChildNodes
    val nodes = for(i <- 0 until items.getLength) yield items.item(i)
    nodes.collect {
      case pi:ProcessingInstruction if pi.getTarget == "xml-stylesheet" => pi.getData
    }.collectFirst { case HREF(uri) => uri }
  }

  /**
    * 指定された要素以下をトラバースして要素内に含まれている XInclude で示されるパスを取得する。
    */
  private[this] def getXInclude(file:File, elem:Element):Seq[File] = {
    val items = elem.getChildNodes
    val elems = (for(i <- 0 until items.getLength) yield items.item(i)).collect { case e:Element => e }
    val hrefs = elems.filter { elem =>
      elem.getNamespaceURI == "http://www.w3.org/2001/XInclude" && elem.getLocalName == "include"
    }.map(_.getAttribute("href")).filter(_.nonEmpty).flatMap { path =>
      val uri = URI.create(path)
      if(!uri.isAbsolute) {
        Some(new File(file.toURI.resolve(uri)))
      } else if(uri.getScheme == "file") Some(new File(uri)) else None
    }
    elems.flatMap { e => getXInclude(file, e) }
  }

  /**
    * 指定された DOM を文字列にシリアライズする。
    */
  private[this] def serialize(doc:Document):String = {
    val out = new StringWriter()
    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(out))
    out.toString
  }
}
