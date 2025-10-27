package at.hazm.webserver.templates

import at.hazm.webserver.{Dependency, TemplateEngine}

import de.larsgrefer.sass.embedded.SassCompilerFactory
import java.io._
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

class SASSEngine extends TemplateEngine {
  private[SASSEngine] val logger = LoggerFactory.getLogger(classOf[SASSEngine])

  override def extensionMap: Map[String, String] = Map("sass" -> "css", "scss" -> "css")

  override def transform(file: File, in: InputStream, out: OutputStream, param: => Map[String, String]): Dependency = {
    val sassCompiler = SassCompilerFactory.bundled()
    val css = try {
      val compileSuccess = sassCompiler.compile(file.toURI.toURL)
      compileSuccess.getCss
    } finally if (sassCompiler != null) sassCompiler.close()

    val w = new OutputStreamWriter(out, StandardCharsets.UTF_8)
    w.write(css)
    w.flush()

    Dependency(file.toURI.toURL)
  }

}
