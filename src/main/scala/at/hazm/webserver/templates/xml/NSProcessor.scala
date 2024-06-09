package at.hazm.webserver.templates.xml

import at.hazm.webserver.Dependency
import org.w3c.dom.{Document, Element}

import java.io.File

/**
  * 特定の Namespace を持つ要素に対して加工処理を行うプロセッサーです。
  *
  * @param namespace このインスタンスが処理する名前空間
  */
abstract class NSProcessor(val namespace: String) extends DocumentProcessor {

  override def process(doc: Document, location: File): Dependency = {
    process(getElements(doc.getDocumentElement), location)
  }

  protected def process(elems: Seq[Element], location: File): Dependency

  /**
    * 指定された要素以下をトラバースしてこのインスタンスが定義する名前空間を持つ要素を参照します。階層構造の上位要素から、同一階層であれば
    * 先に現れた要素から順に並んだ構造となる。
    *
    * @param elem 要素を検索する要素
    * @return 名前空間の一致した要素
    */
  private[this] def getElements(elem: Element): Seq[Element] = {
    val items = elem.getChildNodes
    val elems = (for (i <- 0 until items.getLength) yield items.item(i)).collect { case e: Element => e }
    elems.filter(_.getNamespaceURI == namespace) ++ elems.flatMap(getElements)
  }
}
