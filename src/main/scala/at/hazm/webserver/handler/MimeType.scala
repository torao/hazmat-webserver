package at.hazm.webserver.handler

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.util.Properties

import at.hazm.util.Cache
import com.twitter.finagle.http.MediaType

import scala.collection.JavaConverters._

case class MimeType private(map:Map[String, String], parent:MimeType*) {

  def apply(ext:String):String = {
    val extension = ext.toLowerCase
    map.get(extension).orElse {
      parent.collectFirst { case e if e.map.contains(extension) => e.map.get(extension) }.flatten
    }.getOrElse(MediaType.OctetStream)
  }

}

object MimeType {

  val defaultMimeType:MimeType = load(getClass.getResourceAsStream("/at/hazm/mimetype.properties"))

  def load(in:InputStream, parent:MimeType*):MimeType = {
    val prop = new Properties()
    prop.load(in)
    MimeType(prop.asScala.toMap, parent:_*)
  }

  object Builder extends Cache.Builder[MimeType] {
    override def compile(uri:URI, binary:Option[Array[Byte]]):MimeType = binary.map { bin =>
      load(new ByteArrayInputStream(bin), defaultMimeType)
    }.getOrElse(defaultMimeType)
  }

}