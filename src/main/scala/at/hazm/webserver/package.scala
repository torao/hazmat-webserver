package at.hazm

import java.net.URI
import java.text.SimpleDateFormat
import java.util.{Date, Locale, TimeZone}

import com.twitter.finagle.http.Request

import scala.util.Try

package object webserver {

  implicit class _Request(request:Request) {
    def proxiedRemoteHost:String = request.xForwardedFor match {
      case Some(xForwardedFor) => xForwardedFor
      case None => request.remoteHost
    }

    def requestedProto:String = request.headerMap.getOrElse("X-Forwarded-Proto", "http")

    def requestedHost:Option[String] = request.headerMap.get("X-Forwarded-Host").orElse(
      request.headerMap.get("Host")
    )

    def originalURL:Option[String] = {
      request.headerMap.get("X-Forwarded-Host").orElse(request.host).flatMap { host =>
        val scheme = request.headerMap.get("X-Forwarded-Proto").getOrElse("http")
        val uri = URI.create(request.uri).normalize().toString
        if(uri.startsWith("/")) Some(s"$scheme://$host$uri") else None
      }
    }

    def getDateHeader(key:String):Option[Date] = request.headerMap.get(key).flatMap { date =>
      Seq(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE MMM d HH:mm:ss Z yyyy",
        "EEEE, dd-MMM-yyyy HH:mm:ss Z"
      ).view.map { f =>
        val fmt = new SimpleDateFormat(f, Locale.US)
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"))
        Try(fmt.parse(date)).toOption
      }.collectFirst { case Some(x) => x }
    }
  }

}
