package org.orbeon.connection

import org.log4s
import org.orbeon.oxf.http.{DateHeaders, HttpStatusCodeException, StatusCode, Headers => HttpHeaders}
import org.orbeon.oxf.util.Logging._
import org.orbeon.oxf.util.{ContentTypes, IndentedLogger}

import java.io._
import java.{lang => jl}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


case class ConnectionResult(
  url               : String,
  statusCode        : Int,
  headers           : Map[String, List[String]],
  content           : StreamedContent,
  hasContent        : Boolean,
  dontHandleResponse: Boolean // TODO: Should be outside of ConnectionResult.
) {

  val lastModified: Option[Long] = DateHeaders.firstDateHeaderIgnoreCase(headers, HttpHeaders.LastModified)

  def lastModifiedJava: jl.Long = lastModified map (_.asInstanceOf[jl.Long]) orNull

  def close(): Unit = content.close()

  def isSuccessResponse: Boolean = StatusCode.isSuccessCode(statusCode)

  val mediatype: Option[String] =
    content.contentType flatMap (ct => ContentTypes.getContentTypeMediaType(ct))

  val charset: Option[String] =
    content.contentType flatMap (ct => ContentTypes.getContentTypeCharset(ct))

  def mediatypeOrDefault(default: String): String =
    mediatype getOrElse default

  def charsetJava: String =
    charset.orNull

  def getHeaderIgnoreCase(name: String): List[String] = {
    val nameLowercase = name.toLowerCase
    headers collectFirst { case (k, v) if k.toLowerCase == nameLowercase => v } getOrElse Nil
  }

  def getFirstHeaderIgnoreCase(name: String): Option[String] = {
    val nameLowercase = name.toLowerCase
    (headers collectFirst { case (k, v) if k.toLowerCase == nameLowercase => v.headOption }).flatten
  }

  private var _didLogResponseDetails = false

  // See https://github.com/orbeon/orbeon-forms/issues/1900
  def logResponseDetailsOnce(logLevel: log4s.LogLevel)(implicit logger: IndentedLogger): Unit = {
    if (! _didLogResponseDetails) {
      log(logLevel, "response", Seq("status code" -> statusCode.toString))
      if (headers.nonEmpty) {

        val headersToLog =
          for ((name, values) <- headers; value <- values)
            yield name -> value

        log(logLevel, "response headers", headersToLog.toList)
      }
      _didLogResponseDetails = true
    }
  }

  // See https://github.com/orbeon/orbeon-forms/issues/1900
  def logResponseBody(logLevel: log4s.LogLevel, logBody: Boolean)(implicit logger: IndentedLogger): Unit =
    if (hasContent)
      log(logLevel, "response has content")
    else
      log(logLevel, "response has no content")
}

object ConnectionResult {

  def apply(
    url               : String,
    statusCode        : Int,
    headers           : Map[String, List[String]],
    content           : StreamedContent,
    dontHandleResponse: Boolean = false // TODO: Should be outside of ConnectionResult.
  ): ConnectionResult = {

    val (hasContent, resetInputStream) = {

      val bis =
        if (content.inputStream.markSupported)
          content.inputStream
        else
          new BufferedInputStream(content.inputStream)

      def hasContent(bis: InputStream) = {
        bis.mark(1)
        val result = bis.read != -1
        bis.reset()
        result
      }

      (hasContent(bis), bis)
    }

    ConnectionResult(
      url                = url,
      statusCode         = statusCode,
      headers            = headers,
      content            = content.copy(inputStream = resetInputStream),
      hasContent         = hasContent,
      dontHandleResponse = dontHandleResponse
    )
  }

  def trySuccessConnection(cxr: ConnectionResult): Try[ConnectionResult] =
    cxr match {
      case ConnectionResult(_, _, _, _, _, _) if cxr.isSuccessResponse =>
        Success(cxr)
      case ConnectionResult(_, statusCode, _, _, _, _) =>
        cxr.close()
        Failure(HttpStatusCodeException(if (statusCode != StatusCode.Ok) statusCode else StatusCode.InternalServerError))
    }

  def tryBody[T](cxr: ConnectionResult, closeOnSuccess: Boolean)(body: InputStream => T): Try[T] = Try {
    try {
      val result = body(cxr.content.inputStream)
      if (closeOnSuccess)
        cxr.close() // this eventually calls `InputStream.close()`
      result
    } catch {
      case NonFatal(t) =>
        cxr.close()
        throw t
    }
  }

  def withSuccessConnection[T](cxr: ConnectionResult, closeOnSuccess: Boolean)(body: InputStream => T): T =
    tryWithSuccessConnection(cxr, closeOnSuccess)(body).get

  def tryWithSuccessConnection[T](cxr: ConnectionResult, closeOnSuccess: Boolean)(body: InputStream => T): Try[T] =
    trySuccessConnection(cxr) flatMap (tryBody(_, closeOnSuccess)(body))
}
