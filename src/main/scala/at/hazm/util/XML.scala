package at.hazm.util

import org.w3c.dom.{Document => JDocument, Node => JNode}

import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import scala.xml._

object XML {

  private[this] val transformerFactory = TransformerFactory.newInstance()

  implicit class _Elem(elem: Elem) {
    def childs: List[Elem] = elem.child.collect { case e: Elem => e }.toList

    def asScalaDocument: JDocument = {
      val doc = DocumentBuilderFactory.newInstance.newDocumentBuilder.newDocument()

      def build(node: Node, parent: JNode): Unit = {
        val jnode: JNode = node match {
          case e: Elem =>
            val jn = doc.createElement(e.label)
            e.attributes foreach { a => jn.setAttribute(a.key, a.value.mkString) }
            jn
          case a: Atom[_] => doc.createTextNode(a.text)
          case c: Comment => doc.createComment(c.commentText)
          case er: EntityRef => doc.createEntityReference(er.entityName)
          case pi: ProcInstr => doc.createProcessingInstruction(pi.target, pi.proctext)
        }
        parent.appendChild(jnode)
        node.child.foreach(build(_, jnode))
      }

      build(elem, doc)
      doc
    }

    def dump(): String = {
      val out = new StringWriter()
      TransformerFactory.newInstance().newTransformer().transform(new DOMSource(elem.asScalaDocument), new StreamResult(out))
      out.flush()
      out.toString
    }
  }

}
