package at.hazm.webserver

import at.hazm.webserver.templates.xml.DocumentProcessor
import org.w3c.dom.Element

/**
  * XHTML 内で特定のタグを検出したときに呼び出されるクラスです。
  */
trait CustomTag {

  /**
    * このカスタムタグがコールバックを受ける要素の名前空間 URI。
    */
  def namespaceURI:String

  def apply(namespace:String, localName:String, elem:Element, context:DocumentProcessor.Context):Unit
}

