package at.hazm.webserver.templates.xml

import java.io.{BufferedReader, File, FileReader}
import java.net.URL
import javax.script.{ScriptEngine, ScriptEngineManager}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.{XPathConstants, XPathFactory}

import at.hazm.using
import at.hazm.webserver.Dependency
import jdk.nashorn.api.scripting.ScriptObjectMirror
import org.slf4j.LoggerFactory
import org.w3c.dom.{Document, Element, Node}

import scala.collection.mutable

class ScriptProcessor(scripts:File) extends DocumentProcessor {

  override def process(doc:Document, location:URL):Dependency = {
    val dep = if(scripts.isDirectory) {
      val manager = new ScriptEngineManager()
      scripts.listFiles().flatMap { f =>
        val sep = f.getName.lastIndexOf('.')
        val ext = if(sep < 0) "" else f.getName.substring(sep + 1)
        Option(manager.getEngineByExtension(ext)).map(engine => (engine, f))
      }.map { case (engine, f) =>
        engine.put(ScriptEngine.FILENAME, f.toString)
        using(new BufferedReader(new FileReader(f))) { in =>
          val context = new ScriptProcessor.Context(doc, location)
          engine.put("doc", doc)
          engine.put("location", location.toString)
          engine.put("context", context)
          val deps = (engine.eval(in) match {
            case _:Void => Seq.empty
            case null => Seq.empty
            case uris:Array[_] => uris.toSeq
            case uri:String => Seq(uri)
            case arr:ScriptObjectMirror =>
              if(arr.isArray) {
                for(i <- 0 until arr.size()) yield arr.getSlot(i)
              } else {
                ScriptProcessor.logger.warn(s"the return value '$arr' of script ${f.getName} should be array of string that means dependency url")
                Seq.empty
              }
            case unexpected =>
              ScriptProcessor.logger.warn(s"the return value '$unexpected' of script ${f.getName} should be array of string that means dependency url")
              Seq.empty
          }).collect {
            case uri:String => uri
          }
          context.getDependency + Dependency(deps.map(uri => location.toURI.resolve(uri).normalize().toURL):_*)
        }
      }.reduceLeftOption(_ + _).getOrElse(Dependency())
    } else Dependency()
    Dependency(scripts.toURI.toURL) + dep
  }

}

object ScriptProcessor {
  private[ScriptProcessor] val logger = LoggerFactory.getLogger(classOf[ScriptProcessor])
  private[this] val XMLNS = "xmlns:(.*)".r

  class Context(doc:Document, location:URL) {
    private[this] var dependencies = Dependency()
    private[this] lazy val xpath = XPathFactory.newInstance().newXPath()

    def getDependency:Dependency = dependencies

    def findElements(node:Node, ns:String, name:String):Array[Element] = {
      val buf = mutable.Buffer[Element]()
      node match {
        case doc:Document => _find(buf, doc.getDocumentElement, ns, Seq.empty, name)
        case elem:Element => _find(buf, elem, ns, Seq.empty, name)
      }
      buf.toArray
    }

    def loadXML(uri:String):Document = {
      val url = location.toURI.resolve("./").resolve(uri).toURL
      dependencies = dependencies + Dependency(url)
      // logger.debug(s"loading external xml: $url from $location ($uri)")
      DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.toString)
    }

    /**
      * 指定されたノードに対して XPath で指定された文字列を取得します。
      *
      * @param node  文字列を取得するノード
      * @param xpath 文字列の XPath
      * @return 文字列
      */
    def getString(node:Node, xpath:String):String = {
      this.xpath.evaluate(xpath, node, XPathConstants.STRING).asInstanceOf[String]
    }

    private[this] def _find(buf:mutable.Buffer[Element], elem:Element, ns:String, prefix:Seq[String], name:String):Unit = {
      val attrs = elem.getAttributes
      val currentPrefixes = (for(i <- 0 until attrs.getLength) yield attrs.item(i)).map(a => (a.getNodeName, a.getNodeValue)).collect {
        case (XMLNS(newPrefix), namespace) if namespace == ns => newPrefix
      } ++ prefix

      if(currentPrefixes.exists(p => elem.getTagName == s"$p:$name")) {
        buf.append(elem)
      }

      val nl = elem.getChildNodes
      (for(i <- 0 until nl.getLength) yield nl.item(i)).collect { case e:Element => e }.foreach { e =>
        _find(buf, e, ns, currentPrefixes, name)
      }
    }
  }

}