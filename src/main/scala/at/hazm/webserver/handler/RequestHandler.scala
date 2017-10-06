package at.hazm.webserver.handler

import java.io._
import java.nio.file.Path
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.{StreamResult, StreamSource}

import at.hazm.using
import at.hazm.util.Cache
import at.hazm.util.XML._
import at.hazm.webserver.{Config, TemplateEngine}
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.io.Reader
import org.slf4j.LoggerFactory
import org.w3c.dom.Document

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.Elem

abstract class RequestHandler(docroot:Path) {

  import RequestHandler._

  private[this] var _engine:Option[TemplateEngine] = None
  private[this] var _config:Cache[Config] = _

  def errorTemplateEngine_=(engine:TemplateEngine):Unit = _engine = Some(engine)

  def config_=(config:Cache[Config]):Unit = _config = config

  def apply(request:Request):Option[Response]

  def getErrorResponse(request:Request, status:Status):Response = {
    // XSL 変換用のエラーメッセージ
    val error = defaultErrorMessages.getOrElse(status.code, <html>
      <head>
        <title>
          {status.code}{status.reason}
        </title>
      </head> <body>
        <h1>
          {status.code}{status.reason}
        </h1> <p>
          {status.reason}
        </p>
      </body>
    </html>.asScalaDocument)
    val reader = _config.get.error.find(_._1.matcher(request.path).matches()) match {
      case Some((_, path)) =>
        val xsl = FileHandler.mapLocalFile(docroot, path).get
        transform(request, error, xsl)
      case None =>
        val dir = new File(docroot.toFile, "error")
        val file = new File(dir, s"${status.code}.html")
        val xsl = new File(dir, "error.xsl")
        if(file.isFile) {
          Reader.fromFile(file)
        } else if(xsl.isFile) {
          transform(request, error, xsl)
        } else {
          transformDefault(request, error)
        }
    }
    val response = Response(Version.Http11, status, reader)
    response.cacheControl = "no-cache"
    response.contentType = "text/html"
    response
  }

  /**
    * ローカルファイル上の XSL テンプレートを使用してエラーメッセージの変換処理を行います。
    *
    * @param request リクエスト
    * @param xml     エラー情報
    * @param xsl     ローカルファイルシステム上の XSL テンプレート
    * @return レスポンス内容
    */
  private[this] def transform(request:Request, xml:Document, xsl:File):Reader = {

    // XSLT 指定を追加
    val doc = xml.cloneNode(true).asInstanceOf[Document]
    doc.insertBefore(
      doc.createProcessingInstruction("xml-stylesheet", " href=\"" + xsl.toURI + "\""),
      doc.getDocumentElement
    )

    // ストリーム処理のためにエラー情報を一度シリアライズ
    val errorMessage = {
      val os = new ByteArrayOutputStream()
      val factory = TransformerFactory.newInstance()
      val transformer = factory.newTransformer()
      transformer.setOutputProperty("method", "xml")
      transformer.transform(new DOMSource(doc), new StreamResult(os))
      os.flush()
      os.toByteArray
    }

    val param = TemplateHandler.makeParameters(request)
    val out = new ByteArrayOutputStream()
    FileHandler.mapLocalFile(docroot, request.uri) match {
      case Some(uri) =>
        _engine.get.transform(uri, new ByteArrayInputStream(errorMessage), out, param)
        out.flush()
      case None =>
        logger.error(s"unexpected request uri: ${request.uri}")
        throw new IllegalArgumentException(s"unexpected request uri: ${request.uri}")
    }
    Reader.fromStream(new ByteArrayInputStream(out.toByteArray))
  }

  /**
    * XML テンプレートが用意されていないサイト用にデフォルトの表示を行います。
    *
    * @param request リクエスト
    * @param doc     エラー情報
    * @return レスポンスの内容
    */
  private[this] def transformDefault(request:Request, doc:Document):Reader = {
    val param = TemplateHandler.makeParameters(request)
    val out = new ByteArrayOutputStream()
    val transformer = defaultErrorTemplate.newTransformer()
    param.foreach { case (key, value) =>
      transformer.setParameter(key, value)
    }
    transformer.transform(new DOMSource(doc), new StreamResult(out))
    out.flush()
    Reader.fromStream(new ByteArrayInputStream(out.toByteArray))
  }

  def applyAsync(request:Request)(implicit context:ExecutionContext):Future[Option[Response]] = Future(apply(request))
}

object RequestHandler {
  private[RequestHandler] val logger = LoggerFactory.getLogger(classOf[RequestHandler])

  private[RequestHandler] val defaultErrorTemplate = {
    val url = getClass.getResource("/at/hazm/error.xsl")
    using(url.openStream()) { in =>
      val src = new StreamSource(in)
      src.setSystemId(url.toString)
      TransformerFactory.newInstance().newTemplates(src)
    }
  }

  private[RequestHandler] val defaultErrorMessages = {
    (scala.xml.XML.load(getClass.getResource("/at/hazm/errors.xml")) \ "error").collect {
      case error:Elem =>
        val code = (error \ "code").text.trim().toInt
        code -> error
    }
  }.toMap.mapValues { elem =>
    val code = elem.childs.find(_.label == "code").get.text.toInt
    val phrase = elem.childs.find(_.label == "phrase").get.text
    val message = elem.childs.find(_.label == "message").get.text
    <html>
      <head>
        <title>
          {code}{phrase}
        </title>
      </head>
      <body>
        <h1>
          {code}{phrase}
        </h1> <p>
        {message}
      </p>
      </body>
    </html>
  }.mapValues(_.asScalaDocument)

}