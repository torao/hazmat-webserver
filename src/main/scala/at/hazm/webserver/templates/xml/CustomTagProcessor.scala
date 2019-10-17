package at.hazm.webserver.templates.xml

import java.util.ServiceLoader

import at.hazm.webserver.CustomTag
import org.slf4j.LoggerFactory
import org.w3c.dom.Element

import scala.collection.JavaConverters._

class CustomTagProcessor extends DocumentProcessor {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] val tags = ServiceLoader.load(classOf[CustomTag]).asScala.map { tag =>
    logger.debug(s"using custom tag: [${tag.namespaceURI}] ${tag.getClass.getSimpleName}")
    tag
  }

  override def process(context:DocumentProcessor.Context):Unit = if(tags.isEmpty) Unit else {
    traverse(context, context.doc.getDocumentElement)
  }

  /**
    * 全ての要素に対してカスタムタグと一致する名前空間を持つ要素に対してコールバックを行う。
    *
    * @param context コンテキスト
    * @param elem    要素
    */
  private[this] def traverse(context:DocumentProcessor.Context, elem:Element):Unit = {
    val xmlns = Option(elem.getNamespaceURI).getOrElse("")
    val localName = Option(elem.getLocalName).getOrElse {
      val name = elem.getTagName
      val sep = name.indexOf(':')
      if(sep < 0) name else name.substring(sep + 1)
    }
    tags.filter(_.namespaceURI == xmlns).foreach { tag =>
      tag.apply(xmlns, localName, elem, context)
    }

    val ns = elem.getChildNodes
    (0 until ns.getLength).map(ns.item).collect { case child:Element => child }.foreach { child =>
      traverse(context, child)
    }
  }

}
