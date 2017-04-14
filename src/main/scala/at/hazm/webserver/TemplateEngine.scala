package at.hazm.webserver

import java.io._
import java.util.concurrent.ConcurrentHashMap

import at.hazm.using
import at.hazm.webserver.TemplateEngine.Dependency
import org.slf4j.LoggerFactory

trait TemplateEngine {
  /**
    * このテンプレートエンジンが変換可能な拡張子のマップ。例えば SVG ファイルから PNG ファイルに変換するテンプレートエンジンの場合、
    * `svg` -> `png` のエントリを持つ。
    *
    * @return
    */
  def extensionMap:Map[String, String]

  def transform(file:File, in:InputStream, out:OutputStream):Dependency
}

object TemplateEngine {
  private[TemplateEngine] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  case class Dependency(files:File*) {
    assert(files.nonEmpty)
    private[this] val filesLastModified = lm()

    private[this] def lm():Seq[Long] = files.map(_.lastModified())

    val lastModified = filesLastModified.max

    def isUpdated:Boolean = filesLastModified.zip(lm()).exists { case (a, b) => a != b }
  }

  private[this] class MetaInfo {
    var verifiedAt = 0L
    var dependency:Option[Dependency] = None
  }

  class Manager(interval:Long, engines:TemplateEngine*) {

    /** テンプレート適用のメタ情報をメモリ上に保持するためのマップ。 */
    private[this] val meta = new ConcurrentHashMap[File, MetaInfo]()

    /**
      * 要求されたファイルからテンプレートを選択し適用する。メソッドが true を返した場合、テンプレートの適用結果を cache から読みだす
      * ことができる。
      *
      * @param specified 要求されたファイル
      * @param cache     テンプレート適用後の出力先
      * @return テンプレートが適用された場合に Some(lastModified)
      */
    def transform(specified:File, cache:File):Option[Long] = {
      val info = meta.computeIfAbsent(cache, { _ => new MetaInfo() })
      val tm = System.currentTimeMillis()
      info.synchronized {
        if (info.verifiedAt + interval < tm) {
          info.verifiedAt = tm
          if (info.dependency.forall(_.isUpdated)) {
            cache.delete()
            val start = System.currentTimeMillis()
            rebuild(specified, cache).foreach { dependency =>
              val tm = System.currentTimeMillis() - start
              logger.debug(f"compiled: [${dependency.files.map(_.getName).mkString(", ")}%s] -> ${cache.getName} ($tm%,dms)")
              info.dependency = Some(dependency)
            }
          }
        }
      }
      if (cache.exists()) info.dependency.map(_.lastModified) else None
    }

    /**
      * 要求されたファイルから適切なテンプレートエンジンを選択しキャッシュを再構築する。
      *
      * @param specified 要求されたファイル
      * @param output    テンプレート適用後の出力先
      * @return テンプレートの適用に成功した場合、その依存ファイル
      */
    private[this] def rebuild(specified:File, output:File):Option[Dependency] = {
      val extension = getExtension(specified).toLowerCase
      engines.view.flatMap { engine =>
        // 要求されたファイルへ変換するためのソースファイルとテンプレートエンジンを選択
        engine.extensionMap.filter { case (_, dist) => dist == extension }.map { case (src, dist) =>
          new File(specified.getParentFile, if (specified.getName.contains('.')) {
            specified.getName.substring(0, specified.getName.length - dist.length) + src
          } else specified.getName + "." + src)
        }.filter(_.exists()).map((_, engine))
      }.headOption.map { case (src, engine) =>
        // テンプレートの適用
        using(new BufferedInputStream(new FileInputStream(src))) { in =>
          val parent = output.getParentFile
          parent.mkdirs()
          val temp = File.createTempFile("building-", ".tmp", parent)
          try {
            val dependency = using(new BufferedOutputStream(new FileOutputStream(temp))) { out =>
              engine.transform(src, in, out)
            }
            if (!temp.renameTo(output)) {
              output.delete()
              if (!temp.renameTo(output)) {
                throw new IOException(s"cannot rename ${temp.getName} to ${output.getName}")
              }
            }
            dependency
          } catch {
            case ex:Exception =>
              output.delete()
              temp.delete()
              throw ex
          }
        }
      }
    }
  }

  /**
    * 指定されたファイルから拡張子を取得する。
    *
    * @param file 拡張子を取得するファイル
    * @return `html` のような拡張子
    */
  def getExtension(file:File):String = {
    val sep = file.getName.lastIndexOf('.')
    if (sep < 0) "" else file.getName.substring(sep + 1)
  }
}