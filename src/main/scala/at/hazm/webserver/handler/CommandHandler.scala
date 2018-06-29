package at.hazm.webserver.handler

import java.io.{ByteArrayOutputStream, File, InputStream, PushbackInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import at.hazm._File
import at.hazm.webserver.Server
import at.hazm.webserver.handler.CommandHandler._
import com.twitter.finagle.http._
import com.twitter.io.{Bufs, Reader}
import org.slf4j.{Logger, LoggerFactory}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Try}

/**
  * docroot 上の特定のディレクトリに配置されているコマンドをサーバサイドで実行するためのリクエストハンドラです。
  *
  * @param docroot      ドキュメントルート
  * @param timeout      実行タイムアウト (ミリ秒)
  * @param prefix       URL 上のプレフィクス
  * @param interpreters (`.js` などの拡張子, インタープリタ) のマップ
  */
class CommandHandler(docroot:Path, timeout:Long, prefix:String, interpreters:Map[String, String]) extends RequestHandler(docroot) {
  logger.info(s"""external script enabled for prefix "$prefix", extensions [${interpreters.keys.mkString("\"", "\", \"", "\"")}]""")

  override def apply(request:Request):Option[Response] = None

  override def applyAsync(request:Request)(implicit context:ExecutionContext):Future[Option[Response]] = if(request.uri.startsWith(prefix)) {
    FileHandler.mapLocalFile(docroot, request.uri).map { file =>
      interpreters.get("." + file.getExtension) match {
        case Some(interpreter) =>
          if(!file.isFile) {
            Future.successful(Some(getErrorResponse(request, Status.NotFound)))
          } else {
            startup(request, interpreter, file).map(x => Some(x))
          }
        case None =>
          Future.successful(Some(getErrorResponse(request, Status.Forbidden)))
      }
    }.getOrElse {
      Future.successful(Some(getErrorResponse(request, Status.BadRequest)))
    }
  } else Future.successful(None)

  /**
    * リクエストに応答するために指定されたコマンドを実行しレスポンスを作成します。
    *
    * @param request     リクエスト
    * @param interpreter 起動するインタープリタ
    * @param file        実行するファイル
    * @return レスポンス
    */
  private[this] def startup(request:Request, interpreter:String, file:File)(implicit context:ExecutionContext):Future[Response] = {
    val pipeRequestBody = request.method == Method.Post || request.method == Method.Put

    // プロセスの構築
    val builder = new ProcessBuilder()
      .command(interpreter, file.getName)
      .directory(file.getParentFile)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .redirectInput(ProcessBuilder.Redirect.PIPE)
      .redirectOutput(ProcessBuilder.Redirect.PIPE)

    // CGI と同様の環境変数を設定
    val env = builder.environment()
    env.put("DOCUMENT_ROOT", docroot.toString)
    env.put("GATEWAY_INTERFACE", "CGI/1.1")
    request.headerMap.keys.filter { key =>
      !key.equalsIgnoreCase("Content-Length") && !key.equalsIgnoreCase("Content-Type")
    }.foreach { key =>
      val e = key.toUpperCase.replace('-', '_').replaceAll("[\\x00-\\x1F\\x7F]", "_")
      env.put("HTTP_" + e, request.headerMap.get(key).getOrElse(""))
    }
    if(request.method == Method.Get) {
      val uri = request.uri
      val sep = uri.indexOf('?')
      if(sep >= 0) {
        env.put("QUERY_STRING", uri.substring(sep + 1))
      }
    } else if(pipeRequestBody) {
      env.put("CONTENT_LENGTH", request.contentLength.getOrElse(0L).toString)
      env.put("CONTENT_TYPE", request.contentType.getOrElse(""))
    }
    env.put("REMOTE_ADDR", request.remoteAddress.getHostAddress)
    env.put("REMOTE_PORT", request.remotePort.toString)
    env.put("REQUEST_METHOD", request.method.name)
    env.put("REQUEST_URI", request.uri)
    env.put("SCRIPT_FILENAME", file.toString)
    env.put("SCRIPT_NAME", request.path)

    val start = System.currentTimeMillis()

    def limitTime = timeout - (System.currentTimeMillis() - start)

    def error(status:Status) = getErrorResponse(request, status)

    Server.scheduler.runWithTimeout(limitTime, { _ =>
      logger.warn(f"${request.path}: ${System.currentTimeMillis() - start}%,dms => start timeout")
      Future.successful(Future.successful(error(Status.GatewayTimeout)))
    }) {
      // コマンド起動
      Try(builder.start()).map { proc =>
        // コマンド実行
        Server.scheduler.runWithTimeout(limitTime, { _ =>
          logger.warn(f"${request.path}: ${System.currentTimeMillis() - start}%,dms => execution timeout")
          proc.destroyForcibly()
          Future.successful(error(Status.GatewayTimeout))
        }) {
          try {
            // リクエストボディを標準入力経由でコマンドに渡す
            if(pipeRequestBody) {
              val out = proc.getOutputStream
              out.write(Bufs.ownedByteArray(request.content))
              out.flush()
            }
            val response = pipeResponse(request, proc.getInputStream)
            if(logger.isDebugEnabled) {
              val tm = System.currentTimeMillis() - start
              logger.debug(f"${request.path}%s: $tm%,dms => ${response.status.code} ${response.status.reason}")
            }
            response
          } catch {
            case ex:Throwable =>
              logger.error(s"fail to execute external command: $file", ex)
              error(Status.InternalServerError)
          }
        }
      }.recover { case ex =>
        logger.error(s"fail to start external command: $file", ex)
        Future.successful(error(Status.InternalServerError))
      }.get
    }.flatten
  }

  /**
    * 指定された入力ストリームから外部スクリプトのレスポンスを読み込んでレスポンスを構築します。
    *
    * @param request エラーメッセージ用のリクエスト
    * @param is      外部スクリプトの出力
    * @return レスポンス
    */
  private[this] def pipeResponse(request:Request, is:InputStream):Response = {
    val in = new PushbackInputStream(is)
    readHeader(in).flatMap { case (version, status, headers) =>
      Try {
        val reader = Reader.fromStream(in)
        val response = Response(version, status, reader)
        headers.foreach { case (name, value) =>
          response.headerMap.add(name, value)
        }
        response
      }.recoverWith { case ex =>
        logger.error(s"invalid response header from script: $version $status $headers; ${request.uri}", ex)
        Failure(ex)
      }.toOption
    }.getOrElse(getErrorResponse(request, Status.InternalServerError))
  }

}

private object CommandHandler {
  val logger:Logger = LoggerFactory.getLogger(classOf[CommandHandler])

  /** HTTP レスポンスのステータス行と一致する正規表現 */
  private[this] val statusLine:Regex =
    """HTTP/(\d+)\.(\d+)\s+(\d+)\s+(.*)""".r

  /**
    * 指定された入力ストリームからステータス行とヘッダを読み込みます。読み込みが終わる前に EOF を検出した場合は None を返します。
    *
    * @param in 入力ストリーム
    * @return ステータス行とヘッダ
    */
  def readHeader(in:PushbackInputStream):Option[(Version, Status, Seq[(String, String)])] = readLines(in).map { lines =>
    val (version, status, headers) = lines.headOption.collect {
      case statusLine(verMajor, verMinor, code, _) =>
        (Version(verMajor.toInt, verMinor.toInt), Status(code.toInt), lines.drop(1))
    }.getOrElse((Version.Http11, Status.Ok, lines))
    (version, status, buildHeaders(headers))
  }

  /**
    * 指定された入力ストリームから 1 行分の UTF-8 文字列を読み込んで返します。行末を検出する前に EOF に到達した場合は None を返します。
    *
    * @param in  入力ストリーム
    * @param out 読み込んだデータを保存するバッファ
    * @return 1 行分のデータ
    */
  @tailrec
  private[this] def readLine(in:PushbackInputStream, out:ByteArrayOutputStream = new ByteArrayOutputStream()):Option[Array[Byte]] = in.read() match {
    case '\r' =>
      in.read() match {
        case '\n' => Some(out.toByteArray)
        case eof if eof < 0 => None
        case ch =>
          in.unread(ch)
          Some(out.toByteArray)
      }
    case '\n' =>
      Some(out.toByteArray)
    case eof if eof < 0 => None
    case ch =>
      out.write(ch)
      readLine(in, out)
  }

  /**
    * 指定された入力ストリームからヘッダの終端までを読み込んで各行を返します。ヘッダの終端を読み込む前に EOF を検出した場合は None を返し
    * ます。
    *
    * @param in    入力ストリーム
    * @param lines 読み込んだステータス行とヘッダ
    * @return ステータス行とヘッダ
    */
  @tailrec
  private[this] def readLines(in:PushbackInputStream, lines:Seq[String] = Nil):Option[Seq[String]] = readLine(in) match {
    case None =>
      logger.error(f"premature end of header: [${lines.mkString(", ")}]")
      None
    case Some(bytes) =>
      if(bytes.isEmpty) Some(lines) else readLines(in, lines :+ new String(bytes, StandardCharsets.UTF_8))
  }

  /**
    * 指定されたヘッダ行から折り返しされているヘッダを連結し名前と値のフィールドに分解します。
    *
    * @param headers ヘッダ行
    * @return 認識したヘッダ
    */
  def buildHeaders(headers:Seq[String]):Seq[(String, String)] = {
    val buffer = headers.toBuffer
    for(i <- buffer.length - 1 until 0) {
      if(" \t".exists(_ == buffer(i).head)) {
        buffer(i - 1) = buffer(i - 1) + "\r\n" + buffer(i)
        buffer.remove(i)
      }
    }
    buffer.map { header =>
      val sep = header.indexOf(':')
      if(sep >= 0) {
        (header.substring(0, sep).trim(), header.substring(sep + 1).trim())
      } else {
        (header, "")
      }
    }
  }

}
