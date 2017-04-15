package at.hazm.webserver.handler

import java.io._
import java.nio.file.Path
import java.util.Date

import at.hazm.on
import at.hazm.webserver.{_}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import org.slf4j.LoggerFactory

class FileHandler(docroot:Path, sendBufferSize:Int) extends RequestHandler(docroot) {
  assert(docroot.toString == docroot.toAbsolutePath.toString)

  import FileHandler._

  override def apply(request:Request):Option[Response] = Some(mapLocalFile(docroot, request.uri) match {
    case Some(file) =>
      getResource(request, file) match {
        case Right(resource) =>
          resource.lastModified.flatMap{ lastModified =>
            ifModifiedSince(request, lastModified)
          }.getOrElse {
            on(Response(Version.Http11, Status.Ok, resource.reader)) { res =>
              resource.lastModified.foreach { lastModified => res.headerMap.add("Last-Modified", new Date(lastModified)) }
              resource.length.foreach { length => res.contentLength = length }
            }
          }
        case Left(response) => response
      }
    case None =>
      // ローカルファイルシステム上のパスにマッピングできなければエラー
      logger.debug(s"request-uri cannot map to local file path: ${request.uri}")
      getErrorResponse(Status.BadRequest)
  })

  protected def getResource(request:Request, realPath:File):Either[Response, Resource] = if (realPath.isFile) {
    if (realPath.canRead) {
      Right(LocalFile(realPath))
    } else {
      logger.debug(s"resource don't have read permission: $realPath")
      Left(getErrorResponse(Status.Forbidden))
    }
  } else if (realPath.isDirectory) {
    val indexFile = "index.html"
    if (new File(realPath, indexFile).isFile) {
      request.originalURL match {
        case Some(location) =>
          Left(on(getErrorResponse(Status.Found)) { res =>
            res.location = s"$location${if (location.endsWith("/")) "" else "/"}$indexFile"
            logger.debug(s"directory redirect to: ${res.location.getOrElse("???")}")
          })
        case None =>
          logger.debug(s"original request is not available: $request")
          Left(getErrorResponse(Status.Forbidden))
      }
    } else {
      logger.debug(s"directory index not allowed: $realPath")
      Left(getErrorResponse(Status.Forbidden))
    }
  } else if (!docroot.toFile.exists()) {
    // docroot ディレクトリが存在しない場合はデフォルトのページを表示
    val path = "/at/hazm/index.html"
    Option(getClass.getResource(path)) match {
      case Some(url) =>
        logger.warn(s"docroot is not exist: $docroot, showing default page")
        Right(URLResource(url))
      case None =>
        logger.error(s"default resource not found on class-path: $path")
        Left(getErrorResponse(Status.NotFound))
    }
  } else {
    logger.debug(s"file not found: $realPath")
    Left(getErrorResponse(Status.NotFound))
  }
}

object FileHandler {
  private[FileHandler] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  /**
    * 指定されたリクエスト URI をローカルファイルにマッピングする。URI が docroot 内のファイルを示さない場合は `None`
    * を返す。この場合、不正なパスのリクエストを示すため 400 を返すべきである。
    *
    * @param uri リクエスト URL
    * @return ローカルファイル
    */
  def mapLocalFile(docroot:Path, uri:String):Option[File] = {
    val path = uri.takeWhile{ ch => ch != '?' && ch != '#' }
    val requestPath = docroot.resolve(path.dropWhile(_ == '/')).toAbsolutePath
    if (!requestPath.toString.startsWith(docroot.toString)) {
      logger.debug(s"invalid uri: $requestPath isn't start with $docroot")
      None
    } else Some(requestPath.toFile)
  }

  def ifModifiedSince(request:Request, lastModified:Long):Option[Response] = request.getDateHeader("If-Modified-Since").flatMap { ifModifiedSince =>
    if((ifModifiedSince.getTime - lastModified) / 1000 >= 0){
      Some(on(Response(Version.Http11, Status.NotModified)) { res =>
        res.cacheControl = "no-cache"
      })
    } else None
  }

}
