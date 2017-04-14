package at.hazm.util

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import scala.xml._
import org.w3c.dom.{Document => JDocument, Node => JNode}

object XML {

  private[this] val transformerFactory = TransformerFactory.newInstance()

  implicit class _Elem(elem:Elem) {
    def asScalaDocument:JDocument = {
      val doc = DocumentBuilderFactory.newInstance.newDocumentBuilder.newDocument()
      def build(node:Node, parent:JNode):Unit = {
        val jnode:JNode = node match {
          case e:Elem =>
            val jn = doc.createElement(e.label)
            e.attributes foreach { a => jn.setAttribute(a.key, a.value.mkString) }
            jn
          case a:Atom[_] => doc.createTextNode(a.text)
          case c:Comment => doc.createComment(c.commentText)
          case er:EntityRef => doc.createEntityReference(er.entityName)
          case pi:ProcInstr => doc.createProcessingInstruction(pi.target, pi.proctext)
        }
        parent.appendChild(jnode)
        node.child.foreach(build(_, jnode))
      }
      build(elem, doc)
      doc
    }
  }

}
