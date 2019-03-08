package at.hazm.webserver.templates.xml

import java.io.File

import at.hazm.webserver.Dependency
import org.w3c.dom.{Document, Element, Node, ProcessingInstruction}

/**
  * 特定の Processing Instruction に対して加工処理を行うプロセッサーです。
  *
  * @param target このインスタンスが処理する PI の名前
  */
abstract class PIProcessor(val target:String) extends DocumentProcessor {

  override def process(doc:Document, location:File):Dependency = {
    val pis = doc.getChildNodes.toList.flatMap(getProcessingInstruction)
    process(pis, location)
  }

  protected def process(pis:Seq[ProcessingInstruction], location:File):Dependency

  /**
    * 指定された要素以下をトラバースしてこのインスタンスが定義する名前空間を持つ要素を参照します。階層構造の上位要素から、同一階層であれば
    * 先に現れた要素から順に並んだ構造となる。
    *
    * @param node 要素を検索する要素
    * @return 名前空間の一致した要素
    */
  private[this] def getProcessingInstruction(node:Node):Seq[ProcessingInstruction] = node match {
    case elem:Element =>
      val nl = elem.getChildNodes.toList
      nl.collect {
        case pi:ProcessingInstruction => pi
      }.filter(_.getTarget == target) ++ nl.collect {
        case e:Element => getProcessingInstruction(e)
      }.flatten
    case pi:ProcessingInstruction => Seq(pi)
    case _ => Seq.empty
  }
}
