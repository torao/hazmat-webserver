package at.hazm.webserver.templates.xml

import java.io.{InputStream, InputStreamReader}
import java.net.{URI, URL}
import java.nio.charset.{Charset, StandardCharsets}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.{DOMResult, DOMSource}
import javax.xml.xpath.{XPathConstants, XPathFactory}

import at.hazm.using
import at.hazm.webserver.Dependency
import org.slf4j.LoggerFactory
import org.w3c.dom._

import scala.annotation.tailrec
import scala.util.Try

/**
  * ソースの URL から XInclude を解決し XSL を適用したドキュメントをロードします。これらは依存関係を参照するために JAXP の機能は使用せず
  * 独自で行っています。
  */
object XMLLoader {
  private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  /**
    * 指定された URL からプレーンテキストを読み出します。
    *
    * @param url     ロードする URL
    * @param charset 読み込み時の文字セット
    * @return 読み込んだ文字列
    */
  private[this] def loadPlainText(url:URL, charset:Charset = StandardCharsets.UTF_8):String = using(url.openStream()) { is =>
    val in = new InputStreamReader(is, charset)
    val buffer = new Array[Char](1024)
    val text = new StringBuilder()

    @tailrec
    def _read():String = {
      val len = in.read(buffer)
      if(len < 0) text.toString() else {
        text.appendAll(buffer)
        _read()
      }
    }

    _read()
  }

  /**
    * 指定された URL から XInclude を解決した状態のドキュメントを読み出し、依存情報と共に返します。
    *
    * @param url ロードするドキュメントの URL
    * @return (ドキュメント, 依存先*)
    */
  def load(url:URL, param:Map[String, String]):(Document, Dependency) = using(url.openStream())(in => load(in, url, param))

  /**
    * 指定された入力ストリームから XInclude を解決した状態のドキュメントを読み出し、依存情報と共に返します。
    *
    * @param in  入力ストリーム
    * @param url ロードするドキュメントの URL
    * @return (ドキュメント, 依存先*)
    */
  def load(in:InputStream, url:URL, param:Map[String, String]):(Document, Dependency) = {

    // NOTE: この処理は XInclude の依存関係も収集するため JAXP の XInclude は使用しない
    val doc = {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setNamespaceAware(true)
      factory.setXIncludeAware(false)
      val builder = factory.newDocumentBuilder()
      builder.parse(in, url.toString)
    }

    resolve(doc, url, param)
  }

  /**
    * 指定されたドキュメント内の XInclude を解決し XSL を適用します。
    *
    * @param doc XInclude を解決するドキュメント
    * @param url ドキュメントの URL
    * @return ドキュメントの依存先
    */
  def resolve(doc:Document, url:URL, param:Map[String, String]):(Document, Dependency) = {
    val deps = getIncludes(doc.getDocumentElement).map { include =>
      resolve(include, url, param)
    }.reduceLeftOption(_ + _).getOrElse(Dependency())
    val (doc2, dep2) = resolveStylesheet(doc, url, param)
    (doc2, dep2 + deps)
  }

  /**
    * 指定された &lt;xi:include&gt; 要素を解決して依存関係を参照します。
    *
    * @param include XInclude を解決する要素
    * @param url     ドキュメントの URL
    * @return XInclude の依存先
    */
  private[this] def resolve(include:Element, url:URL, param:Map[String, String]):Dependency = {

    def href2url(e:Element):URL = {
      val href = new URI(e.getAttribute("href"))
      if(href.isAbsolute) href.toURL else url.toURI.resolve(href).toURL
    }

    lazy val xpath = XPathFactory.newInstance().newXPath()

    val (nodes, deps) = try {
      val href = href2url(include)
      val parse = include.getAttribute("parse")
      if(parse == "xml" || parse.isEmpty) {
        // 再帰的に XML の挿入
        val (dom, deps) = load(href, param)
        if(include.hasAttribute("xpath")) { // NOTE: 本当は xpointer を使うが Java で実装されていないため
          val nl = xpath.evaluate(include.getAttribute("xpath"), dom, XPathConstants.NODESET).asInstanceOf[NodeList]
          (nl.toList, deps)
        } else (List(dom.getDocumentElement), deps)
      } else {
        // 単純なテキスト挿入
        val charset = if(include.hasAttribute("encoding")) Charset.forName(include.getAttribute("encoding")) else StandardCharsets.UTF_8
        (List(include.getOwnerDocument.createTextNode(loadPlainText(href, charset))), Dependency())
      }
    } catch {
      case ex:Throwable =>
        // コメントのエラーメッセージと fallback 内の要素を挿入
        (include.getOwnerDocument.createComment(s" ERROR[$url] $ex ") :: getFallback(include).map { fallback =>
          fallback.getChildNodes.toList
        }.getOrElse(List.empty)) -> Dependency()
    }

    // この include 要素の依存先
    val dep = Try(href2url(include)).toOption.map(f => Dependency(f)).getOrElse(Dependency())

    // <xi:include> 位置の要素を置き換え
    val parent = include.getParentNode
    nodes.foreach { node =>
      parent.insertBefore(include.getOwnerDocument.importNode(node, true), include)
    }
    parent.removeChild(include)

    Dependency(url) + dep + deps
  }

  /**
    * 指定されたドキュメントに定義されている XSL を適用します。
    *
    * @param doc      XSL を適用するドキュメント
    * @param location ドキュメントの URL
    * @param param    テンプレート用のパラメータ
    */
  private[this] def resolveStylesheet(doc:Document, location:URL, param:Map[String, String]):(Document, Dependency) = {
    getStyleSheet(doc) match {
      case Some(uri) =>
        val url = if(uri.isAbsolute) uri.toURL else location.toURI.resolve(uri).toURL
        val (stylesheet, deps) = load(url, param)

        val factory = TransformerFactory.newInstance()
        val transformer = factory.newTransformer(new DOMSource(stylesheet))
        param.foreach { case (key, value) =>
          transformer.setParameter(key, value)
        }

        val src = new DOMSource(doc)
        val out = new DOMResult()
        transformer.transform(src, out)
        val result = out.getNode.asInstanceOf[Document]
        (result, Dependency(location, url) + deps)
      case None => (doc, Dependency())
    }
  }

  /**
    * 指定された要素以下をトラバースして &lt;xi:include&gt; 要素を参照する。
    *
    * @param elem &lt;xi:include&gt; 要素を参照する要素
    * @return 検出した &lt;xi:include&gt; 要素
    */
  private[this] def getIncludes(elem:Element):Seq[Element] = getXInclude(_.getLocalName == "include", elem)

  /**
    * 指定された要素以下をトラバースして &lt;xi:fallback&gt; 要素を参照する。
    *
    * @param elem &lt;xi:fallback&gt; 要素を参照する要素
    * @return 検出した &lt;xi:fallback&gt; 要素
    */
  private[this] def getFallback(elem:Element):Option[Element] = getXInclude(_.getLocalName == "fallback", elem).headOption

  /**
    * 指定された要素以下をトラバースして条件に一致する XInclude 要素を参照する。階層構造の上位要素、同一階層であれば先に現れた要素から順に
    * 並んだ構造となる。
    *
    * @param eval 条件
    * @param elem 条件に一致した要素
    * @return 検出した &lt;xi:include&gt; 要素
    */
  private[this] def getXInclude(eval:(Element) => Boolean, elem:Element):Seq[Element] = {
    val items = elem.getChildNodes
    val elems = (for(i <- 0 until items.getLength) yield items.item(i)).collect { case e:Element => e }
    elems.filter { e =>
      e.getNamespaceURI == "http://www.w3.org/2001/XInclude" && eval(e)
    } ++ elems.flatMap(e => getXInclude(eval, e))
  }

  private[this] val HREF = ".*href\\s*=\\s*[\"\'](.*?)[\"\'].*".r

  /**
    * 指定された要素以下をトラバースして xml-stylesheet の URI を参照します。
    *
    * @param node スタイルシートを検索するノード
    * @return スタイルシートの URI
    */
  private[this] def getStyleSheet(node:Node):Option[URI] = node match {
    case elem if elem.getChildNodes.getLength > 0 =>

      def _find(nl:List[Node], i:Int):Option[URI] = if(i < nl.length) {
        getStyleSheet(nl(i)).orElse(_find(nl, i + 1))
      } else None

      _find(elem.getChildNodes.toList, 0)
    case pi:ProcessingInstruction if pi.getTarget == "xml-stylesheet" =>
      pi.getData match {
        case HREF(href) => Some(new URI(href))
        case _ => None
      }
    case _ => None
  }

}
