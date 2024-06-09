package at

import java.io.File
import scala.language.reflectiveCalls

package object hazm {
  def using[R <: {def close(): Unit}, U](r: R)(f: (R) => U): U = try {
    f(r)
  } finally {
    r.close()
  }

  def on[T](t: T)(f: (T) => Unit): T = {
    f(t); t
  }

  implicit class _File(file: File) {
    /**
      * 指定されたファイル名の最後のピリオドより後の拡張子部分を返す。ファイル名がピリオドを含まない場合は "" を返す。
      * 大文字小文字は変換されない。
      */
    def getExtension: String = {
      val fileName = file.getName
      val dot = fileName.lastIndexOf('.')
      if (dot < 0) "" else fileName.substring(dot + 1)
    }
  }
}
