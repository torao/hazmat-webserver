package at.hazm.webserver

import java.io.File
import java.net.URL

/**
  * 生成元ファイルの更新を確認するために静的コンテンツの依存関係を表します。URL にはローカルディレクトリを指定することもできます。
  *
  * @param urls 依存している URL
  */
case class Dependency(urls: URL*) {
  private[this] val files: List[File] = urls.map(_.toURI).filter(_.getScheme.toLowerCase == "file").map(uri => new File(uri)).toList

  private[this] val filesLastModified = lm()

  private[this] def lm(): Seq[Long] = files.map { f =>
    if (f.isDirectory) {
      Option(f.listFiles()).getOrElse(Array.empty) match {
        case Array() => 0
        case fs => fs.map(_.lastModified()).max
      }
    } else f.lastModified()
  }

  val lastModified: Long = if (filesLastModified.isEmpty) -1 else filesLastModified.max

  def isUpdated: Boolean = filesLastModified.zip(lm()).exists { case (a, b) => a != b }

  def +(dep: Dependency): Dependency = Dependency((urls ++ dep.urls).distinct: _*)
}
