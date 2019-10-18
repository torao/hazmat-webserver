package at.hazm.webserver.templates.xml

import org.w3c.dom.{Element, Node, Text}

import scala.annotation.tailrec

class PrettifyProcessor extends DocumentProcessor {

  /**
    * 内容を変更してはいけない要素 (小文字)。
    */
  private[this] val ShouldIgnore = Set("pre", "script", "style", "textarea")

  /**
    * ブロック (コンテナ) 型の要素 (小文字)。
    */
  private[this] val Container = Set(
    "html", "head", "body", "nav", "footer", "div", "ul", "ol", "dl", "dd", "table", "tr", "thead", "tbody", "tfoot",
    "form", "blockquote", "select")

  override def process(context:DocumentProcessor.Context):Unit = {
    // doc.getDocumentElement.setAttribute("xmlns", "http://www.w3.org/1999/xhtml")
    concatJapanese(context.doc.getDocumentElement)
    setIndent(context.doc.getDocumentElement, 0)
    context.doc.foreach(_.removeAttribute("xmlns"))
  }

  private[this] def setIndent(elem:Element, i:Int):Unit = {
    // ノードの前方の空白を削除
    def _removePrevSpace(node:Node):Unit = Option(node.getPreviousSibling) match {
      case Some(prev:Text) if prev.getData.trim.isEmpty =>
        prev.getParentNode.removeChild(prev)
        _removePrevSpace(node)
      case _ => ()
    }

    // ノードの後方空白を削除
    def _removePostSpace(node:Node):Unit = Option(node.getNextSibling) match {
      case Some(next:Text) if next.getData.trim.isEmpty =>
        next.getParentNode.removeChild(next)
        _removePostSpace(node)
      case _ => ()
    }

    // コンテナ型要素であれば子のノードを縦に並べるインデントを追加
    if(Container.contains(elem.getTagName.toLowerCase)) {
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
    elem.getChildNodes.toList.collect { case t:Text => t }.foreach { t =>
      val org = t.getData
      val buffer = new StringBuilder(org
        .replaceAll("\\s+", " ")
        .replaceAll("([。、．，」]) ", "$1")
        .replaceAll(" ([「])", "$1")
      )

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

      val rep = buffer.toString()
      if(org != rep) {
        val n = t.getOwnerDocument.createTextNode(rep)
        elem.replaceChild(n, t)
      }
    }
    elem.getChildNodes.toList.collect { case t:Element => t }.foreach(concatJapanese)
  }

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
