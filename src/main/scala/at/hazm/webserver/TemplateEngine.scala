package at.hazm.webserver

import java.io._
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import at.hazm.using
import at.hazm.util.Cache
import org.slf4j.LoggerFactory

/**
  * テンプレートエンジンはリクエストされたファイル拡張子に基づいてその内容を変換する処理を行います。
  */
trait TemplateEngine {

  /**
    * このテンプレートエンジンが変換可能な拡張子のマップ。例えば SVG ファイルから PNG ファイルに変換するテンプレート
    * エンジンの場合 `svg` -> `png` のエントリを持つ。
    */
  def extensionMap:Map[String, String]

  def transform(file:File, in:InputStream, out:OutputStream, param: => Map[String, String]):Dependency

  def setRoot(dir:File):Unit = None

  def setConfig(config:Cache[Config]):Unit = None
}

object TemplateEngine {
  private[TemplateEngine] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  private[this] class MetaInfo {
    var verifiedAt = 0L
    var dependency:Option[Dependency] = None
  }

  class Manager(root:File, interval:Long, config:Cache[Config]) {
    private[this] val docroot = Context.docroot(root)

    private[this] val engines = ServiceLoader.load(classOf[TemplateEngine]).asScala.map{ sp =>
      sp.setRoot(root)
      sp.setConfig(config)
      logger.debug(s"using template engine: ${sp.getClass.getSimpleName}")
      sp
    }

    /** テンプレート適用のメタ情報をメモリ上に保持するためのマップ。 */
    private[this] val meta = new ConcurrentHashMap[File, MetaInfo]()

    /**
      * 要求されたファイルからテンプレートを選択し適用する。メソッドが Some を返した場合、テンプレートの適用結果を cache
      * から読みだすことができる。None を返した場合、対象のファイルはテンプレート処理の対象外、あるいはテンプレート処理
      * できなかったことを意味する。
      *
      * @param specified 要求されたファイル
      * @param cache     テンプレート適用後の出力先
      * @return キャッシュファイル cache からテンプレート適用後のデータを読み出し可能な場合にその Some(lastModified)
      */
    def transform(specified:File, cache:File, param:Map[String, String], force:Boolean):Option[Long] = {
      val info = meta.computeIfAbsent(cache, { _ => new MetaInfo() })
      val tm = System.currentTimeMillis()
      info.synchronized {
        if( {
          // DoS 攻撃回避のため強制再構築要求でもでも interval が経過していなければ構築しない
          if(info.verifiedAt + interval < tm) {
            info.verifiedAt = tm
            info.dependency.forall(_.isUpdated) || force
          } else false
        } || !cache.exists()) {
          cache.delete()
          val start = System.currentTimeMillis()
          rebuild(specified, cache, param).foreach { dependency =>
            val tm = System.currentTimeMillis() - start
            if(logger.isDebugEnabled) {
              val prefix = specified.getParentFile.toURI
              val items = dependency.urls.map(_.toURI).map(i => prefix.relativize(i)).map{ i =>
                if(i.isAbsolute) "/" + docroot.toURI.relativize(i) else i
              }
              logger.debug(f"compiled: [${items.mkString(", ")}%s] -> ${cache.getName} ($tm%,dms)")
            }
            info.dependency = Some(dependency)
          }
        }
      }
      if(cache.exists()) info.dependency.map(_.lastModified) else None
    }

    /**
      * 要求されたファイルから適切なテンプレートエンジンを選択しキャッシュを再構築する。
      *
      * @param specified 要求されたファイル
      * @param output    テンプレート適用後の出力先
      * @return テンプレートの適用に成功した場合、その依存ファイル
      */
    private[this] def rebuild(specified:File, output:File, param:Map[String, String]):Option[Dependency] = {
      val extension = getExtension(specified).toLowerCase
      engines.view.flatMap { engine =>
        // 要求されたファイルへ変換するためのソースファイルとテンプレートエンジンを選択
        engine.extensionMap.filter { case (_, dist) => dist == extension }.map { case (src, dist) =>
          new File(specified.getParentFile, if(specified.getName.contains('.')) {
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
              engine.transform(src, in, out, param)
            }
            if(!temp.renameTo(output)) {
              output.delete()
              if(!temp.renameTo(output)) {
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
    if(sep < 0) "" else file.getName.substring(sep + 1)
  }
}