package at.hazm.webserver.templates.xml

import org.w3c.dom.{Element, Node, Text}

import scala.annotation.tailrec

class PrettifyProcessor extends DocumentProcessor {

  /**
    * 内容を変更してはいけない要素 (小文字)。
    */
  private[this] val ShouldIgnore = Set("pre", "script", "style", "textarea").map(_.toLowerCase)

  /**
    * ブロック (コンテナ) 型の要素 (小文字)。
    */
  private[this] val Container = Set(
    "html", "head", "body", "nav", "article", "footer", "div", "ul", "ol", "dl", "table", "tr", "thead",
    "tbody", "tfoot", "form", "blockquote", "select").map(_.toLowerCase)

  /**
    * 内容の前後に存在する空白文字を削除する要素。
    */
  private[this] val Trim = Set(
    "p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "caption", "figcaption"
  ).map(_.toLowerCase)

  /**
    * https://developer.mozilla.org/ja/docs/Web/HTML/Element#Inline_text_semantics
    */
  private[this] val Inlines = Set(
    "a", "abbr", "b", "bdi", "bdo", "br", "cite", "code", "data", "dfn", "em", "i", "kbd", "mark", "q", "rb", "rt",
    "rtc", "ruby", "s", "samp", "small", "span", "strong", "sub", "sup", "time", "tt", "u", "var", "wbr"
  ).map(_.toLowerCase)

  override def process(context:DocumentProcessor.Context):Unit = {
    // doc.getDocumentElement.setAttribute("xmlns", "http://www.w3.org/1999/xhtml")
    concatJapanese(context.doc.getDocumentElement)
    setIndent(context.doc.getDocumentElement, 0)
    context.doc.foreach(_.removeAttribute("xmlns"))
  }

  private[this] def setIndent(elem:Element, i:Int):Unit = {
    // ノードの前方の空白を削除
    @tailrec
    def _removePrevSpace(node:Node):Unit = Option(node.getPreviousSibling) match {
      case Some(prev:Text) if prev.getData.trim.isEmpty =>
        prev.getParentNode.removeChild(prev)
        _removePrevSpace(node)
      case _ => ()
    }

    // ノードの後方空白を削除
    @tailrec
    def _removePostSpace(node:Node):Unit = Option(node.getNextSibling) match {
      case Some(next:Text) if next.getData.trim.isEmpty =>
        next.getParentNode.removeChild(next)
        _removePostSpace(node)
      case _ => ()
    }

    // ノードの前方空白を削除
    @tailrec
    def _trimPrefixedSpace(nodes:List[Node]):Unit = nodes.headOption.collect {
      case t:Text => (t, t.getData, t.getData.dropWhile(Character.isWhitespace))
    } match {
      case Some((text, _, "")) =>
        val parent = text.getParentNode
        parent.removeChild(text)
        _trimPrefixedSpace(nodes.drop(1))
      case Some((text, t1, t2)) if t1.length != t2.length =>
        val parent = text.getParentNode
        val doc = text.getOwnerDocument
        val newText = doc.createTextNode(t2)
        parent.replaceChild(newText, text)
      case _ => ()
    }

    // ノードの後方空白を削除
    @tailrec
    def _trimPostfixedSpace(nodes:List[Node]):Unit = nodes.lastOption.collect {
      case t:Text => (t, t.getData, t.getData.reverse.dropWhile(Character.isWhitespace).reverse)
    } match {
      case Some((text, _, "")) =>
        val parent = text.getParentNode
        parent.removeChild(text)
        _trimPostfixedSpace(nodes.dropRight(1))
      case Some((text, t1, t2)) if t1.length != t2.length =>
        val parent = text.getParentNode
        val doc = text.getOwnerDocument
        val newText = doc.createTextNode(t2)
        parent.replaceChild(newText, text)
      case _ => ()
    }

    if(Container.contains(elem.getTagName.toLowerCase)) {
      // コンテナ型要素であれば子のノードを縦に並べるインデントを追加
      val doc = elem.getOwnerDocument
      elem.getChildNodes.toList.foreach { c =>
        if(elem.getChildNodes.toList.contains(c)) {
          _removePrevSpace(c)
          _removePostSpace(c)
          elem.insertBefore(doc.createTextNode("\n" + "  " * (i + 1)), c)
        }
      }
      if(elem.getChildNodes.getLength > 0) {
        elem.appendChild(doc.createTextNode("\n" + "  " * i))
      }
    } else if(Trim.contains(elem.getTagName.toLowerCase)) {
      // 要素が含む子ノードの前後空白を削除する
      _trimPrefixedSpace(elem.getChildNodes.toList)
      _trimPostfixedSpace(elem.getChildNodes.toList)
    }

    // 再帰的に実行
    // ※ JS による DOM 操作で NS 指定版を使わないと local-name が null になるがあるがそれは補助したい
    if(!ShouldIgnore.contains(Option(elem.getLocalName).getOrElse(elem.getTagName).toLowerCase)) {
      elem.getChildNodes.toList.collect { case e:Element => e }.foreach(e => setIndent(e, i + 1))
    }
  }

  /**
    * 全角文字列中に改行とインデントを検出した場合、それらを削除します。
    *
    * @param elem 日本語を連結する要素
    */
  private[this] def concatJapanese(elem:Element):Unit = if(!ShouldIgnore.contains(elem.getTagName.toLowerCase)) {
    // テキスト編集は階層構造を意識すると複雑なので、先に論理出現順に構造をフラット化したほうが良いかもしれない
    elem.getChildNodes.toList.collect { case t:Text => t }.foreach { t =>
      val org = t.getData
      val buffer = new StringBuilder(org
        .replaceAll("\\s+", " ")
        .replaceAll("([。、．，」]) ", "$1")
        .replaceAll(" ([「])", "$1")
      )

      // 前後を全角文字に囲まれた半角空白文字を削除 (e.g., "あいう えお" → "あいうえお")
      @tailrec
      def _replace(begin:Int):Unit = if(begin < buffer.length) {
        val i = buffer.indexOf(' ', begin)
        if(i >= 0) {
          if(i - 1 >= 0 && isJapanese(buffer.charAt(i - 1)) && i + 1 < buffer.length && isJapanese(buffer.charAt(i + 1))) {
            buffer.deleteCharAt(i)
            _replace(i)
          } else _replace(i + 1)
        }
      }

      _replace(0)

      // 要素の前後に配置された、全角文字に囲まれた半角空白を削除 (e.g., "あいう <a>え </a>お")
      if(buffer.length >= 2 && buffer.head == ' ' && isJapanese(buffer.charAt(1))
        && getPrevInSequence(t).exists(endsWithInlineJapaneseOrBegin)) {
        buffer.deleteCharAt(0)
      }
      if(buffer.length >= 2 && buffer.last == ' ' && isJapanese(buffer.charAt(buffer.length - 2))
        && getNextInSequence(t).exists(startsWithInlineJapaneseOrEnd)) {
        buffer.deleteCharAt(buffer.length - 1)
      }

      val rep = buffer.toString()
      if(org != rep) {
        val n = t.getOwnerDocument.createTextNode(rep)
        elem.replaceChild(n, t)
      }
    }

    elem.getChildNodes.toList.collect { case t:Element => t }.foreach(concatJapanese)
  }

  /**
    * 指定されたテキストノードが日本語文字の直後に配置されているかを判定します。
    *
    * @param node 判定するテキストノード
    * @return 論理的に直前に配置されている文字が日本語の場合 true
    */
  private[this] def endsWithInlineJapaneseOrBegin(node:Node):Boolean = node match {
    case text:Text if text.getData.trim().nonEmpty =>
      isJapanese(text.getData.trim().last)
    case elem:Element if !Inlines.contains(elem.getTagName.toLowerCase) => true
    case elem:Element if elem.getLastChild != null =>
      endsWithInlineJapaneseOrBegin(elem.getLastChild)
    case _ =>
      getPrevInSequence(node).exists(endsWithInlineJapaneseOrBegin)
  }

  /**
    * 指定されたテキストノードが日本語文字の直前に配置されているかを判定します。
    *
    * @param node 判定するテキストノード
    * @return 論理的に直前に配置されている文字が日本語の場合 true
    */
  private[this] def startsWithInlineJapaneseOrEnd(node:Node):Boolean = node match {
    case text:Text if text.getData.trim().nonEmpty =>
      isJapanese(text.getData.trim().head)
    case elem:Element if !Inlines.contains(elem.getTagName.toLowerCase) => true
    case elem:Element if elem.getFirstChild != null =>
      startsWithInlineJapaneseOrEnd(elem.getFirstChild)
    case _ =>
      getNextInSequence(node).exists(startsWithInlineJapaneseOrEnd)
  }

  /**
    * DOM ツリーを要素の並びとして前に評価されるノードを参照します。
    *
    * @param node 基準となるノード
    * @return 前に評価されるノード
    */
  private[this] def getPrevInSequence(node:Node):Option[Node] = Option(node.getPreviousSibling).orElse(
    Option(node.getParentNode).flatMap(parent => Option(parent.getPreviousSibling))
  )

  /**
    * DOM ツリーを要素の並びとして次に評価されるノードを参照します。
    *
    * @param node 基準となるノード
    * @return 次に評価されるノード
    */
  private[this] def getNextInSequence(node:Node):Option[Node] = Option(node.getNextSibling).orElse(
    Option(node.getParentNode).flatMap(parent => Option(parent.getNextSibling))
  )

  /**
    * 指定された文字が日本語かを判定します。
    *
    * @param ch 判定する文字
    * @return 日本語の場合 true
    */
  private[this] def isJapanese(ch:Char):Boolean = {
    // CJK Symbols and Punctuation / Range: 3000–303F
    (ch >= 0x3000 && ch <= 0x303F) ||
      // Hiragana / Range: 3040–309F
      (ch >= 0x3040 && ch <= 0x309F) ||
      // Katakana / Range: 30A0–30FF
      (ch >= 0x30A0 && ch <= 0x30FF) ||
      // CJK Unified Ideographs / Range: 4E00–9FCF
      (ch >= 0x4E00 && ch <= 0x9FCF) ||
      // Halfwidth and Fullwidth Forms / Range: FF00–FFEF
      (ch >= 0xFF00 && ch <= 0xFFFF)
  }
}
