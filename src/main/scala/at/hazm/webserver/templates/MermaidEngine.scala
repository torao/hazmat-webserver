package at.hazm.webserver.templates

import at.hazm.util.Cache
import at.hazm.webserver.{Config, Dependency, TemplateEngine}
import org.slf4j.LoggerFactory

import java.io._
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.util.Try

/**
  * グラフやクラス図を記述する Mermaid 書式のファイルを PNG 画像に変換します。
  */
class MermaidEngine extends TemplateEngine {
  private[MermaidEngine] val logger = LoggerFactory.getLogger(classOf[MermaidEngine])

  override def extensionMap: Map[String, String] = Map("mmd" -> "png")

  override def transform(file: File, in: InputStream, out: OutputStream, param: => Map[String, String]): Dependency = {

    // Mermaid 内部で使用している Chrome が使用する Puppeteer の設定を保存する
    val puppeteer = File.createTempFile("puppeteer", ".json")
    val ppt = new FileWriter(puppeteer)
    try {
      ppt.write("{\"args\": [\"--no-sandbox\"]}")
    } finally {
      ppt.close()
    }

    val width = param.get("w").flatMap(w => Try(w.toInt).toOption).map(w => s" -w $w").getOrElse("")
    val height = param.get("h").flatMap(h => Try(h.toInt).toOption).map(h => s" -H $h").getOrElse("")
    val temp = File.createTempFile("mmd", ".png")
    val cmd = s"""mmdc -p $puppeteer -i $file -o $temp $width$height"""
    try {
      // 画像ファイルの生成
      logger.debug(s"mermaid.cli: $cmd; [$file] ${file.length()}B")
      val proc = Runtime.getRuntime.exec(cmd)
      proc.waitFor(30, TimeUnit.SECONDS)
      val exitCode = proc.exitValue()
      if (exitCode != 0) {
        for ((msg, is) <- Array(("stdout", proc.getInputStream), ("stderr", proc.getErrorStream))) {
          val in = new BufferedReader(new InputStreamReader(is))
          var line = in.readLine()
          while (line != null) {
            logger.error(s"mermaid.cli: $msg: $line")
            line = in.readLine()
          }
        }
        throw new IOException(s"failed to execute mermaid.cli; exit code=$exitCode")
      }
      logger.debug(s"  ==> ${temp.length()} bytes PNG generated")

      // 出力されたファイルをコピー
      Files.copy(temp.toPath, out)
    } catch {
      case ex: IOException =>
        logger.error(s"failed to execute: $cmd")
        logger.error(s"PATH=${System.getenv("PATH")}")
        logger.error(s"java.library.path=${System.getProperty("java.library.path")}")
        throw ex
    } finally {
      puppeteer.delete()
      temp.delete()
    }

    Dependency(file.toURI.toURL)
  }

}
