package at.hazm.webserver.templates

import java.io._
import java.nio.charset.StandardCharsets

import at.hazm.webserver.{Dependency, TemplateEngine}
import com.mangofactory.typescript.TypescriptCompiler
import org.slf4j.LoggerFactory

class TypeScriptEngine extends TemplateEngine {
  private[TypeScriptEngine] val logger = LoggerFactory.getLogger(classOf[TypeScriptEngine])

  override def extensionMap:Map[String, String] = Map("ts" -> "js")

  override def transform(file:File, in:InputStream, out:OutputStream, param: => Map[String, String]):Dependency = {
    val compiler = new TypescriptCompiler()
    val script = compiler.compile(file)
    val w = new OutputStreamWriter(out, StandardCharsets.UTF_8)
    w.write(script)
    w.close()
    Dependency(file.toURI.toURL)
  }

}
