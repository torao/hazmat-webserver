package at.hazm.webserver.templates.xml

import java.io.File
import java.net.{URI, URL}

import at.hazm.webserver.templates.xml
import at.hazm.webserver.{Context, Dependency}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.{XPathConstants, XPathFactory}
import org.slf4j.LoggerFactory
import org.w3c.dom.{Document, Element, Node, NodeList}

import scala.collection.mutable

/**
  * ロードされた DOM を加工するためのトレイト。
  */
trait DocumentProcessor {
  def process(doc: Document, location: File): Dependency = {
    val context = DocumentProcessor.Context(doc, getDocroot, location)
    process(context)
    context.getDependency
  }

  def process(context: DocumentProcessor.Context): Unit = None

  private[this] var root: File = _
  private[this] var docroot: File = _

  def setRoot(root: File): Unit = {
    this.root = root
    this.docroot = Context.docroot(root)
  }

  def getRoot: File = root

  def getDocroot: File = docroot

  def helper(doc: Document, docroot: File, location: File) = new xml.ScriptProcessor.Context(doc, docroot, location)
}


object DocumentProcessor {
  private[this] val logger = LoggerFactory.getLogger(classOf[DocumentProcessor])
  private[this] val XMLNS = "xmlns:(.*)".r

  /**
    *
    * @param doc      location からロードされ XSLT 処理された XML ドキュメント
    * @param docroot  ドキュメントルートのディレクトリ
    * @param location doc のローカルファイル
    */
  case class Context(doc: Document, docroot: File, location: File) {
    assert(docroot.isAbsolute && docroot.isDirectory)
    assert(location.isAbsolute) // ※動的に生成された XML かもしれない
    private[this] var dependencies = Dependency()
    private[this] lazy val xpath = XPathFactory.newInstance().newXPath()
    private[this] val docrootURI = docroot.toURI
    private[this] val variable = mutable.HashMap[String, String]()

    def getDependency: Dependency = dependencies

    /**
      * 指定されたノードを基準に指定された名前空間、要素名を持つ要素を参照します。
      *
      * @param node 基準とするノード
      * @param ns   名前空間
      * @param name 要素のローカル名
      */
    def findElements(node: Node, ns: String, name: String): Seq[Element] = {
      val buf = mutable.Buffer[Element]()
      node match {
        case doc: Document => _find(buf, doc.getDocumentElement, ns, Seq.empty, name)
        case elem: Element => _find(buf, elem, ns, Seq.empty, name)
      }
      buf
    }

    /**
      * 指定された URI を処理対象のドキュメントを基準にした URI に変換します。URI が相対パスの場合、ドキュメントルートからのパスに変換され
      * ます。URI が絶対パスの場合はそのまま返されます。
      *
      * @param href 変換する URI
      * @return docroot からのパス
      */
    def resolve(href: String): String = toAbsoluteFile(href) match {
      case Some(file) => "/" + docrootURI.relativize(file.toURI).normalize()
      case None => href
    }

    def addDependency(uri: String): URL = {
      val url = toAbsoluteFile(uri) match {
        case Some(file) => file.toURI.toURL
        case None => new URI(uri).toURL
      }
      dependencies = dependencies + Dependency(url)
      url
    }

    def addDependency(dep: Dependency): Dependency = {
      dependencies = dependencies + dep
      dependencies
    }

    /**
      * 指定された URI をローカルファイルシステムのパスにマッピングします。
      *
      * @param href ローカルのパスに変換する URI
      * @return ローカルのパス。href が絶対 URI や docroot より外を示す場合は None
      */
    private[this] def toAbsoluteFile(href: String): Option[File] = {
      val uri = new URI(href).normalize()
      if (uri.isAbsolute) {
        None
      } else if (uri.toString.startsWith("/")) {
        Some(new File(docroot, uri.toString.dropWhile(_ == '/')))
      } else {
        Some(new File(location.toURI.resolve(href)))
      }
    }.filter { uri =>
      if (uri.getAbsolutePath.startsWith(docroot.getAbsolutePath)) {
        true
      } else {
        logger.warn(s"uri specifies out of public directory: $uri")
        false
      }
    }.map(_.getAbsoluteFile)

    /**
      * 処理対象となっているドキュメントの場所を基準に指定された相対 URI で指定された XML ドキュメントをロードします。このメソッドで参照した
      * URL は自動的に依存関係に追加されます。
      *
      * @param uri ロードする XML 文書の URI
      * @return ロードした XML 文書
      */
    def loadXML(uri: String): Document = {
      val url = addDependency(uri)
      DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.toString)
    }

    /**
      * 指定されたノードを基準に XPath で指定された文字列を取得します。
      *
      * @param node  文字列を取得するノード
      * @param xpath 文字列の XPath
      * @return 文字列
      */
    def getString(node: Node, xpath: String): String = {
      this.xpath.evaluate(xpath, node, XPathConstants.STRING).asInstanceOf[String]
    }

    /**
      * 指定されたノードを基準に XPath で指定されたブーリアン値を取得します。
      *
      * @param node  ブール値を取得するノード
      * @param xpath ブール値 XPath
      * @return ブール値
      */
    def getBoolean(node: Node, xpath: String): Boolean = {
      this.xpath.evaluate(xpath, node, XPathConstants.BOOLEAN).asInstanceOf[Boolean]
    }

    /**
      * 指定されたノードを基準に XPath で指定された文字列配列を取得します。
      *
      * @param node  文字列配列を取得するノード
      * @param xpath 文字列配列の XPath
      * @return 文字列配列
      */
    def getStrings(node: Node, xpath: String): Array[String] = {
      val nl = this.xpath.evaluate(xpath, node, XPathConstants.NODESET).asInstanceOf[NodeList]
      (for (i <- 0 until nl.getLength) yield nl.item(i).getTextContent).toArray
    }

    /**
      * このコンテキストに関連づけられた変数を設定します。
      *
      * @param name  変数名
      * @param value 変数の値
      */
    def setVariable(name: String, value: String): Unit = {
      this.variable += ((name, value))
    }

    /**
      * このコンテキストに関連づけられた変数を参照します。
      *
      * @param name 変数名
      * @return 変数の値
      */
    def getVariable(name: String): Option[String] = {
      this.variable.get(name)
    }

    private[this] def _find(buf: mutable.Buffer[Element], elem: Element, ns: String, prefix: Seq[String], name: String): Unit = {
      val attrs = elem.getAttributes
      val currentPrefixes = (for (i <- 0 until attrs.getLength) yield attrs.item(i)).map(a => (a.getNodeName, a.getNodeValue)).collect {
        case (XMLNS(newPrefix), namespace) if namespace == ns => newPrefix
      } ++ prefix

      if (currentPrefixes.exists(p => elem.getTagName == s"$p:$name")) {
        buf.append(elem)
      }

      val nl = elem.getChildNodes
      (for (i <- 0 until nl.getLength) yield nl.item(i)).collect { case e: Element => e }.foreach { e =>
        _find(buf, e, ns, currentPrefixes, name)
      }
    }
  }

}