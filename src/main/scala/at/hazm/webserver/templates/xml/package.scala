package at.hazm.webserver.templates

import java.io.StringWriter
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import org.w3c.dom._

package object xml {

  implicit class _NodeList(nl:NodeList) {
    def toList:List[Node] = (for(i <- 0 until nl.getLength) yield nl.item(i)).toList
  }

  implicit class _Document(doc:Document) {
    def dump():String = {
      val sw = new StringWriter()
      TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(sw))
      sw.toString
    }

    def foreach(f:(Element) => Unit):Unit = {
      def _callback(e:Element):Unit = {
        f(e)
        e.getChildNodes.toList.collect { case e:Element => e }.foreach(_callback)
      }

      _callback(doc.getDocumentElement)
    }
  }

}
