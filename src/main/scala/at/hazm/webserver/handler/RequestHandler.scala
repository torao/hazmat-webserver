package at.hazm.webserver.handler

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.{StreamResult, StreamSource}

import at.hazm.using
import at.hazm.util.XML._
import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.io.Reader

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.Elem

abstract class RequestHandler(docroot:Path) {
  import RequestHandler._
  def apply(request:Request):Option[Response]

  def getErrorResponse(request:Request, status:Status):Response = {
    val dir = new File(docroot.toFile, "error")
    val file = new File(dir, s"${status.code}.html")
    val reader = if (file.isFile) {
      Reader.fromFile(file)
    } else {
      val error = defaultErrorMessages.getOrElse(status.code, <error>
        <code>
          {status.code}
        </code> <phrase>
          {status.reason}
        </phrase> <message></message>
      </error>)
      val xsl = new File(dir, "error.xsl")
      val template = if (xsl.isFile) {
        TransformerFactory.newInstance().newTransformer(new StreamSource(xsl))
      } else defaultErrorTemplate.newTransformer()
      val baos = new ByteArrayOutputStream()
      val out = new OutputStreamWriter(new BufferedOutputStream(baos), StandardCharsets.UTF_8)
      template.transform(new DOMSource(error.asScalaDocument), new StreamResult(out))
      out.flush()
      Reader.fromStream(new ByteArrayInputStream(baos.toByteArray))
    }
    val response = Response(Version.Http11, status, reader)
    response.cacheControl = "no-cache"
    response.contentType = "text/html"
    response
  }

  def applyAsync(request:Request)(implicit context:ExecutionContext):Future[Option[Response]] = Future(apply(request))
}

object RequestHandler {

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
  }.toMap
}