package at.hazm.util

import java.io.{ByteArrayInputStream, File}
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.{Timer, TimerTask}

import at.hazm.using
import at.hazm.util.Cache.{Builder, Source}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

class Cache[T](path:String, source:Source, builder:Builder[T], verifyAfter:Long = 2 * 1000L) {

  /**
    * このキャッシュの URI
    */
  val uri:URI = source.uri.resolve(path)

  private[this] val value = new AtomicReference[T](builder.compile(uri, None))
  private[this] var version:Long = -1
  private[this] var verifiedAt:Long = -1
  private[this] var onUpdate:Option[(String, T) => Unit] = None

  def onUpdate(f:(String, T) => Unit):Unit = {
    this.onUpdate = Some(f)
    Cache.watchdog.register(this)
  }

  def get:T = {
    val tm = System.currentTimeMillis()
    if(tm - verifiedAt < verifyAfter) {
      value.get()
    } else {
      verifiedAt = tm
      source.getIfModified(path, version) match {
        case Some((v, binary)) =>
          version = v
          val newValue = builder.compile(uri, Some(binary))
          value.set(newValue)
          onUpdate.foreach {
            _.apply(path, newValue)
          }
          newValue
        case None => value.get()
      }
    }
  }
}

object Cache {
  private[Cache] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  private[Cache] lazy val watchdog = new WatchDog()

  private[Cache] class WatchDog {
    private[this] val scheduler = new Timer("CacheWatchdog", true)
    private[this] val watchdog = new mutable.WeakHashMap[Cache[_], Unit]()
    scheduler.scheduleAtFixedRate(new TimerTask {
      override def run():Unit = watchdog.keys.foreach {
        _.get
      }
    }, 2 * 1000L, 2 * 1000L)

    def register(cache:Cache[_]):Unit = watchdog.put(cache, ())
  }

  class Manager[T](source:Source, builder:Builder[T], verifyAfter:Long = 2 * 1000L) {
    private[this] val cache = new ConcurrentHashMap[String, Cache[T]]()

    def cache(path:String):Cache[T] = {
      cache.computeIfAbsent(path, { _ => new Cache[T](path, source, builder, verifyAfter) })
    }

    def get(path:String):T = cache(path).get
  }

  /**
    * メモリ上にキャッシュするオブジェクトのバイナリを取得する。このインスタンスは一つのキャッシュリソースのみを対象と
    * している。
    */
  trait Source {

    /**
      * このソースの場所を示す URI。
      */
    def uri:URI

    /**
      * 指定されたバージョンから対象のデータが更新されていた場合に現在のバージョンとバイナリデータを返す。バージョンは
      * 同一ミリ秒内の更新を考慮する必要がなければデータのタイムスタンプを使用することができる。バージョンに負の値が
      * 指定された場合はデータ更新を判断せず現在のデータを返す必要がある。データが存在しない場合は `None` を返す。
      *
      * @param version 更新を判断するバージョン
      * @return 現在のデータ
      */
    def getIfModified(path:String, version:Long):Option[(Long, Array[Byte])]
  }

  class FileSource(dir:File) extends Source {
    val uri:URI = dir.toURI

    def getIfModified(path:String, lastModified:Long):Option[(Long, Array[Byte])] = {
      val file = new File(dir, path)
      val local = file.lastModified()
      if(local != lastModified) {
        val binary = if(file.isFile) Files.readAllBytes(file.toPath) else Array.empty[Byte]
        Some((local, binary))
      } else None
    }

    override def toString:String = s"${dir.toString}/*"
  }

  /**
    * メモリ上にキャッシュするオブジェクトをバイナリから構築する。
    *
    * @tparam T 生成するオブジェクト
    */
  trait Builder[T] {
    /**
      * 指定されたバイナリからキャッシュ用のオブジェクトを生成する。
      *
      * @param binary オブジェクト生成元のバイナリ (None の場合はデータが存在しない場合のデフォルトを生成する)
      * @return バイナリから生成したオブジェクト
      */
    def compile(uri:URI, binary:Option[Array[Byte]]):T
  }

  class MapBuilder extends Builder[Map[String, String]] {
    override def compile(uri:URI, binary:Option[Array[Byte]]):Map[String, String] = {
      val prop = new java.util.Properties()
      binary.foreach { b =>
        using(new ByteArrayInputStream(b)) {
          prop load _
        }
      }
      prop.asScala.toMap
    }
  }

}