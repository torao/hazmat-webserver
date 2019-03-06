package at.hazm.webserver.templates

import java.io._
import java.net.URI
import java.nio.charset.StandardCharsets

import at.hazm.webserver.{Dependency, TemplateEngine}
import com.vaadin.sass.internal.{ScssContext, ScssStylesheet}
import org.slf4j.LoggerFactory
import org.w3c.css.sac.InputSource

class SASSEngine extends TemplateEngine {
  private[SASSEngine] val logger = LoggerFactory.getLogger(classOf[SASSEngine])

  override def extensionMap:Map[String, String] = Map("sass" -> "css", "scss" -> "css")

  override def transform(file:File, in:InputStream, out:OutputStream, param: => Map[String, String]):Dependency = {
    val parent = new ScssStylesheet()
    parent.addResolver((parentStylesheet:ScssStylesheet, identifier:String) => {
      val f = new File(identifier)
      val uri = if(f.isAbsolute) f.toURI else {
        val u = URI.create(identifier)
        if(u.isAbsolute) u else new File(file.getParentFile, identifier).toURI
      }
      val url = if(uri.isAbsolute) uri else file.toURI.resolve(identifier)
      val src = new InputSource(url.toString)
      src.setByteStream(uri.toURL.openStream())
      src
    })
    val css = ScssStylesheet.get(file.toString, parent)
    if(css == null) {
      throw new FileNotFoundException(s"sass or scss file not found: ${file.getAbsolutePath}")
    }
    css.compile(ScssContext.UrlMode.MIXED)
    val w = new OutputStreamWriter(out, StandardCharsets.UTF_8)
    css.write(w, true)
    w.flush()
    Dependency(file.toURI.toURL)
  }

}
