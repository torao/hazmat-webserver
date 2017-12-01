package at.hazm.webserver.templates.xml

import java.io._
import java.net.{URI, URL}
import java.nio.charset.StandardCharsets
import javax.script.{ScriptEngine, ScriptEngineManager}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.{XPathConstants, XPathFactory}

import at.hazm.using
import at.hazm.webserver.Dependency
import jdk.nashorn.api.scripting.ScriptObjectMirror
import org.slf4j.LoggerFactory
import org.w3c.dom.{Document, Element, Node, NodeList}

import scala.collection.mutable

class ScriptProcessor(scripts:File, docroot:URL) extends DocumentProcessor {

  override def process(doc:Document, location:URL):Dependency = {
    val dep = if(scripts.isDirectory) {
      val manager = new ScriptEngineManager()
      scripts.listFiles().flatMap { f =>
        val sep = f.getName.lastIndexOf('.')
        val ext = if(sep < 0) "" else f.getName.substring(sep + 1)
        Option(manager.getEngineByExtension(ext)).map(engine => (engine, f))
      }.map { case (engine, f) =>
        engine.put(ScriptEngine.FILENAME, f.toString)
        using(new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) { in =>
          val context = new ScriptProcessor.Context(doc, docroot.toURI.normalize(), location)
          engine.put("doc", doc)
          engine.put("docroot", docroot.toString)
          engine.put("location", "/" + docroot.toURI.relativize(location.toURI).toString)
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

  class Context(doc:Document, docroot:URI, location:URL) {
    private[this] var dependencies = Dependency()
    private[this] lazy val xpath = XPathFactory.newInstance().newXPath()

    def getDependency:Dependency = dependencies

    /**
      * 指定されたノードを基準に指定された名前空間、要素名を持つ要素を参照します。
      *
      * @param node 基準とするノード
      * @param ns   名前空間
      * @param name 要素のローカル名
      */
    def findElements(node:Node, ns:String, name:String):Array[Element] = {
      val buf = mutable.Buffer[Element]()
      node match {
        case doc:Document => _find(buf, doc.getDocumentElement, ns, Seq.empty, name)
        case elem:Element => _find(buf, elem, ns, Seq.empty, name)
      }
      buf.toArray
    }

    /**
      * 指定された URI を処理対象のドキュメントを基準にした URI に変換します。URI が相対パスの場合、ドキュメントルートからのパスに変換され
      * ます。URI が絶対パスの場合はそのまま返されます。
      *
      * @param uri 変換する URI
      * @return 変換した URI
      */
    def resolve(uri:String):String = {
      val url = docroot.resolve(toAbsoluteURI(uri).toString.dropWhile(_ == '/')).normalize().toString
      val prefix = docroot.toString
      if(url.startsWith(prefix)) {
        (if(prefix.endsWith("/")) "/" else "") + url.substring(prefix.length)
      } else url
    }

    private[this] def toAbsoluteURI(uri:String):URI = if(uri.startsWith("/")) {
      docroot.resolve(uri.dropWhile(_ == '/')).normalize()
    } else {
      location.toURI.resolve("./").resolve(uri).normalize()
    }

    /**
      * 処理対象となっているドキュメントの場所を基準に指定された相対 URI で指定された XML ドキュメントをロードします。このメソッドで参照した
      * URL は自動的に依存関係に追加されます。
      *
      * @param uri ロードする XML 文書の URI
      * @return ロードした XML 文書
      */
    def loadXML(uri:String):Document = {
      val url = toAbsoluteURI(uri).toURL
      dependencies = dependencies + Dependency(url)
      DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.toString)
    }

    /**
      * 指定されたノードを基準に XPath で指定された文字列を取得します。
      *
      * @param node  文字列を取得するノード
      * @param xpath 文字列の XPath
      * @return 文字列
      */
    def getString(node:Node, xpath:String):String = {
      this.xpath.evaluate(xpath, node, XPathConstants.STRING).asInstanceOf[String]
    }

    /**
      * 指定されたノードを基準に XPath で指定されたブーリアン値を取得します。
      *
      * @param node  ブール値を取得するノード
      * @param xpath ブール値 XPath
      * @return ブール値
      */
    def getBoolean(node:Node, xpath:String):Boolean = {
      this.xpath.evaluate(xpath, node, XPathConstants.BOOLEAN).asInstanceOf[Boolean]
    }

    /**
      * 指定されたノードを基準に XPath で指定された文字列配列を取得します。
      *
      * @param node  文字列配列を取得するノード
      * @param xpath 文字列配列の XPath
      * @return 文字列配列
      */
    def getStrings(node:Node, xpath:String):Array[String] = {
      val nl = this.xpath.evaluate(xpath, node, XPathConstants.NODESET).asInstanceOf[NodeList]
      (for(i <- 0 until nl.getLength) yield nl.item(i).getTextContent).toArray
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