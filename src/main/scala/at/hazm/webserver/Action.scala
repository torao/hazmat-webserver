package at.hazm.webserver

import java.io.File

import com.twitter.finagle.http.{Request, Response}

trait Action {
  private[this] var _docroot:File = _

  def docroot_=(docroot:File):Unit = this._docroot = docroot

  def docroot:File = _docroot

  def uriPrefix:String

  def apply(request:Request, suburi:String):Option[Response]

}
