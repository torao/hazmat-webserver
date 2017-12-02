package at.hazm.webserver.templates.xml

import java.io.File

import at.hazm.webserver.Dependency
import org.w3c.dom.Document

/**
  * ロードされた DOM を加工するためのトレイト。
  */
trait DocumentProcessor {
  def process(doc:Document, location:File):Dependency
}
