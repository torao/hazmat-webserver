package at.hazm.webserver.templates

import at.hazm.webserver.{Dependency, TemplateEngine}
import org.apache.batik.transcoder.image.JPEGTranscoder
import org.apache.batik.transcoder.{TranscoderInput, TranscoderOutput}
import org.slf4j.LoggerFactory

import java.io._
import java.util.zip.GZIPInputStream

/**
  * SVG/SVGZ を画像ファイルへ変換します。
  *
  * @see https://xmlgraphics.apache.org/batik/using/transcoder.html
  */
class SVGEngine extends TemplateEngine {
  private[SVGEngine] val logger = LoggerFactory.getLogger(classOf[SVGEngine])

  override def extensionMap: Map[String, String] = Map("svg" -> "jpg", "svgz" -> "jpg")

  override def transform(file: File, in: InputStream, out: OutputStream, param: => Map[String, String]): Dependency = {
    // val transcoder = new PNGTranscoder()
    val transcoder = new JPEGTranscoder()
    val ti = new TranscoderInput(if (file.getName.endsWith(".svgz")) new GZIPInputStream(in) else in)
    val to = new TranscoderOutput(out)
    transcoder.transcode(ti, to)

    Dependency(file.toURI.toURL)
  }

}
