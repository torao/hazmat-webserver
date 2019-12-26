package at.hazm.webserver

import java.io.File
import java.net.URLDecoder

import com.twitter.finagle.http.{Method, Request, Response, Status}
import play.api.libs.json._

import scala.language.implicitConversions

trait Action {
  private[this] var _docroot:File = _

  def docroot_=(docroot:File):Unit = this._docroot = docroot

  def docroot:File = _docroot

  def uriPrefix:String

  def apply(request:Request, suburi:String):Option[Response]

  /**
    * サブクラスの Action がリクエストの操作を行いやすくするためのユーティリティクラス。
    */
  implicit class _Request(req:Request) {

    /**
      * リクエストとして送信されてる HTML フォーム形式 (GET/POST) または JSON 形式 (POST/PUT) のデータをアプリケーション
      * が参照するための JSON 形式に変換します。POST や PUT メソッドの場合、このメソッドが呼び出せるのは 1 度限りです。
      *
      * @return リクエストされたデータの JSON 形式
      */
    def asJSON:Option[JsValue] = if (req.method == Method.Get) {
      val separator = req.uri.indexOf('?')
      if (separator < 0) {
        Some(Json.obj())
      } else {
        Some(formToJson(req.uri.substring(separator + 1)))
      }
    } else if (req.method == Method.Post || req.method == Method.Put) {
      req.contentType.map(_.toLowerCase.split(';').map(_.trim()).toList).collect {
        case "application/x-www-form-urlencoded" :: _ =>
          formToJson(req.contentString)
        case ("text/json" | "application/json") :: _ =>
          Json.parse(req.contentString)
      }
    } else None

    private[this] def formToJson(params:String):JsObject = JsObject(params.split('&')
      .filter(_.nonEmpty)
      .map(_.split("=", 2))
      .collect {
        case Array(key, value) => (key, value)
        case Array(key) => (key, "")
      }
      .map(x => (URLDecoder.decode(x._1, "UTF-8"), URLDecoder.decode(x._2, "UTF-8")))
      .groupBy(_._1).mapValues(_.map(_._2))
      .mapValues(values => if (values.length > 1) Json.arr(values) else JsString(values.head)))
  }

  implicit class _JsValue(jv:JsValue) {
    def asString:String = jv match {
      case JsString(value) => value
      case JsNumber(value) => value.toString
      case JsNull => ""
      case JsBoolean(value) => value.toString
      case unexpected => Json.stringify(unexpected)
    }

    def asInt:Int = asInt(0)

    def asInt(default:Int):Int = asLong(default).toInt

    def asLong:Long = asLong(0)

    def asLong(default:Long):Long = jv match {
      case JsNumber(value) => value.toLong
      case JsString(value) => value.toLong
      case JsNull => default
      case JsBoolean(value) => if (value) 1 else 0
      case _ => default
    }

    def asDouble:Double = asDouble(0.0)

    def asDouble(default:Double):Double = jv match {
      case JsNumber(value) => value.toDouble
      case JsString(value) => value.toDouble
      case JsNull => default
      case JsBoolean(value) => if (value) 1 else 0
      case _ => default
    }

    def asBoolean:Boolean = jv match {
      case JsBoolean(value) => value
      case JsString(value) => value.toBoolean
      case JsNumber(value) => value != 0
      case JsNull => false
      case _ => false
    }

    def asSeq:Seq[JsValue] = jv match {
      case JsArray(arr) => arr
      case unexpected => Seq(unexpected)
    }

    def asMap:Map[String, JsValue] = jv match {
      case JsObject(value) => value.toMap
      case _ => Map.empty
    }
  }

  def makeJsonResponse(code:Status, value:JsValue):Response = {
    val res = Response(code)
    res.contentString = Json.stringify(value)
    res.contentType = "text/json; charset=UTF-8"
    res
  }

  def makeJsonResponse(value:JsValue):Response = makeJsonResponse(Status.Ok, value)

}
