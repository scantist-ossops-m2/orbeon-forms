/**
  * Copyright (C) 2017 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.xforms.upload

import org.apache.commons.fileupload.disk.DiskFileItem
import org.apache.commons.fileupload.{FileItem, FileItemHeaders, FileItemHeadersSupport, UploadContext}
import org.orbeon.datatypes.MaximumSize
import org.orbeon.datatypes.MaximumSize.{LimitedSize, UnlimitedSize}
import org.orbeon.exception.OrbeonFormatter
import org.orbeon.io.IOUtils.runQuietly
import org.orbeon.io.LimiterInputStream
import org.orbeon.oxf.common.Version
import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.externalcontext.ExternalContext.{Request, Session}
import org.orbeon.oxf.http.Headers
import org.orbeon.oxf.processor.generator.RequestGenerator
import org.orbeon.oxf.util.*
import org.orbeon.oxf.util.CoreUtils.OptionOps
import org.orbeon.oxf.util.Multipart.UploadItem
import org.orbeon.oxf.util.SLF4JLogging.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.xforms.XFormsContainingDocumentSupport.*
import org.orbeon.oxf.xforms.XFormsGlobalProperties
import org.orbeon.oxf.xforms.control.XFormsValueControl
import org.orbeon.oxf.xforms.upload.api.java.{FileScan2, FileScanProvider2, FileScanResult as JFileScanResult}
import org.orbeon.oxf.xforms.upload.api.{FileScan, FileScanProvider, FileScanStatus}
import org.orbeon.xforms.Constants
import org.slf4j
import shapeless.syntax.typeable.*

import java.net.URI
import scala.collection.mutable as m
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


object UploaderServer {

  import Private.*

  def processUpload(request: Request): (List[(String, UploadItem, Option[FileScanAcceptResult])], Option[Throwable]) = {

    // Session is required to communicate with the XForms document
    val session = request.sessionOpt getOrElse (throw new IllegalStateException("upload requires a session"))

    val trustedUploadContext = new UploadContext {

      private val outerLimiterInputStream = new LimiterInputStream(
        request.getInputStream,
        LimitedSize(Multipart.DefaultBufferSize), // should be enough to read headers and `$uuid` (this is updated once first file is detected)
        Multipart.throwSizeLimitExceeded
      )

      val getContentType       = request.getContentType
      val getCharacterEncoding = request.getCharacterEncoding
      val getInputStream       = outerLimiterInputStream

      // Set to -1 because we want to be able to read at least `$uuid`
      val contentLength        = -1L
      def getContentLength     = -1 // this won't be used anyway
    }

    Multipart.parseMultipartRequest(
      uploadContext  = trustedUploadContext,
      lifecycleOpt   = Some(
        new UploadProgressMultipartLifecycle(
          requestContentLengthOpt  = request.contentLengthOpt,
          requestAcceptLanguageOpt = request.getFirstHeaderIgnoreCase(Headers.AcceptLanguage),
          outerLimiterInputStream  = trustedUploadContext.getInputStream,
          session                  = session
        ) {
          def getUploadConstraintsForControl(uuid: String, controlEffectiveId: String): Try[((MaximumSize, AllowedMediatypes), URI)] =
            withDocumentAcquireLock(
              uuid    = uuid,
              timeout = XFormsGlobalProperties.uploadXFormsAccessTimeout)
            { doc =>
              doc.getUploadConstraintsForControl(controlEffectiveId).map(_ -> doc.getRequestUri)
            } .flatten
        }
      ),
      maxSize        = MaximumSize.UnlimitedSize, // because we use our own limiter
      maxFiles       = Some(RequestGenerator.getMaxFilesProperty.toLong).filter(_ >= 0), // probably not really needed
      headerEncoding = ExternalContext.StandardHeaderCharacterEncoding,
      maxMemorySize  = -1 // make sure that the `FileItem`s returned always have an associated file
    )
  }

  def getUploadProgressForTests(
    session  : Session,
    uuid     : String,
    fieldName: String
  ): Option[UploadProgress[DiskFileItem]] =
    getUploadProgress(session, getProgressSessionKey(uuid, fieldName))

  private def getUploadProgress(
    session   : Session,
    sessionKey: String
  ): Option[UploadProgress[DiskFileItem]] =
    session.getAttribute(sessionKey)
      .flatMap(_.cast[UploadProgress[DiskFileItem]])

  private def setUploadProgress(
    session        : Session,
    uuid           : String,
    fieldName      : String,
    expectedSizeOpt: Option[Long],
    fileName       : Option[String]
  ): (String, UploadProgress[DiskFileItem]) = {
    val progress      = UploadProgress[DiskFileItem](fieldName, expectedSizeOpt)
    val newSessionKey = getProgressSessionKey(uuid, fieldName)
    session.setAttribute(newSessionKey, progress)
    newSessionKey -> progress
  }

  def getUploadProgress(
    request  : Request,
    uuid     : String,
    fieldName: String
  ): Option[UploadProgress[DiskFileItem]] =
    request.sessionOpt.flatMap(getUploadProgress(_, getProgressSessionKey(uuid, fieldName)))

  def removeUploadProgress(request: Request, control: XFormsValueControl): Unit = {
    request
      .sessionOpt
      .foreach(_.removeAttribute(getProgressSessionKey(control.containingDocument.uuid, control.effectiveId)))
  }

  // Public for tests
  abstract class UploadProgressMultipartLifecycle(
    requestContentLengthOpt  : Option[Long],
    requestAcceptLanguageOpt : Option[String],
    outerLimiterInputStream  : LimiterInputStream,
    session                  : Session
  ) extends MultipartLifecycle {

    import Private.*

    // Mutable state
    private var uuidOpt     : Option[String]                       = None
    private var progressOpt : Option[UploadProgress[DiskFileItem]] = None
    private var fileScanOpt : Option[Either[FileScan2, FileScan]]  = None

    def fieldReceived(fieldName: String, value: String): Unit =
      if (fieldName == Constants.UuidFieldName) {
        require(value ne null)
        require(uuidOpt.isEmpty, "more than one document UUID provided")

        uuidOpt = Some(value)
      }

    // Session keys created, for cleanup
    private val sessionKeys = m.ListBuffer[String]()

    def getUploadConstraintsForControl(uuid: String, controlEffectiveId: String): Try[((MaximumSize, AllowedMediatypes), URI)]

    // Can throw
    def fileItemStarting(fieldName: String, fileItem: FileItem): Option[MaximumSize] = {

      val uuid =
        uuidOpt getOrElse (throw new IllegalStateException("missing document UUID"))

      if (progressOpt.isDefined)
        throw new IllegalStateException("more than one file provided")

      val ((maxUploadSizeForControl, allowedMediatypeRangesForControl), requestUri) =
        getUploadConstraintsForControl(uuid, fieldName).get // TODO: will throw if this is a `Failure`

      val headersOpt =
        for {
          support <- fileItem.cast[FileItemHeadersSupport]
          headers <- Option(support.getHeaders)
        } yield
          headers

      def findHeaderValue(name: String) =
        for {
          headers <- headersOpt
          value   <- Option(headers.getHeader(name))
        } yield
          value

      // Handle max size
      locally {
        // This is `None` with Chrome and Firefox at least (2019-10-18: confirmed at least for Chrome)
        val untrustedPartContentLengthOpt: Option[Long] =
          findHeaderValue(Headers.ContentLength) flatMap NumericUtils.parseLong

        val untrustedExpectedSizeOpt = untrustedPartContentLengthOpt orElse requestContentLengthOpt

        // So that the XFCD is aware of progress information
        // Do this before checking size so that we can report the interrupted upload
        locally {
          val (newSessionKey, progress) = UploaderServer.setUploadProgress(session, uuid, fieldName, untrustedExpectedSizeOpt, fileItem.getName.trimAllToOpt)
          sessionKeys += newSessionKey
          progressOpt = Some(progress)
        }

        // As of 2017-03-22: part `Content-Length` takes precedence if provided (but browsers don't set it).
        // Browsers do set the outer `Content-Length` though. Again we assume that the overhead of the
        // entire request vs. the part is small so it's ok, for progress purposes, to use the outer size.
        untrustedExpectedSizeOpt foreach { untrustedExpectedSize =>
          checkSizeLimitExceeded(maxSize = maxUploadSizeForControl, currentSize = untrustedExpectedSize)
        }

        // Otherwise update the outer limiter to support enough additional bytes
        // This is an approximation as there is overhead for `$uuid` and the part's headers.
        // The assumption is that the content of the upload is typically much larger than
        // the overhead.
        (outerLimiterInputStream.maxBytes, maxUploadSizeForControl) match {
          case (_,                    UnlimitedSize)        => outerLimiterInputStream.maxBytes = UnlimitedSize
          case (UnlimitedSize,        LimitedSize(_))       => throw new IllegalStateException
          case (LimitedSize(current), LimitedSize(control)) => outerLimiterInputStream.maxBytes = LimitedSize(current + control)
        }
      }

      // Handle mediatypes
      locally {
        allowedMediatypeRangesForControl match {
          case AllowedMediatypes.AllowedAnyMediatype =>
          case AllowedMediatypes.AllowedSomeMediatypes(allowedMediatypeRanges) =>
            Mediatypes.fromHeadersOrFilename(findHeaderValue, fileItem.getName.trimAllToOpt) match {
              case None =>
                throw DisallowedMediatypeException(fileItem.getName, allowedMediatypeRanges, None)
              case Some(untrustedPartMediatype) =>
                if (! (allowedMediatypeRanges exists untrustedPartMediatype.is))
                  throw DisallowedMediatypeException(fileItem.getName, allowedMediatypeRanges, Some(untrustedPartMediatype))
            }
        }
      }

      // https://github.com/orbeon/orbeon-forms/issues/5516
      if (Version.isPE)
        fileScanProviderOpt foreach {
          case Left(fileScanProviderV2) =>
            fileScanOpt =
              Try(
                fileScanProviderV2.startStream(
                  filename  = fileItem.getName,
                  headers   = FileScanProvider.convertHeadersToJava(headersOpt map convertFileItemHeaders getOrElse Nil),
                  language  = requestAcceptLanguageOpt.getOrElse("en"),
                  extension = Map[String, Any]("request.uri" -> requestUri).asJava
                )
              ) match {
                case Success(fs) => Some(Left(fs))
                case Failure(t)  => throw FileScanException(fieldName, FileScanErrorResult(Option(t.getMessage), Option(t)))
              }
          case Right(fileScanProvider) =>
            fileScanOpt = fileScanProvider.startStream(fileItem.getName, headersOpt map convertFileItemHeaders getOrElse Nil) match {
              case Success(fs) => Some(Right(fs))
              case Failure(t)  => throw FileScanException(fieldName, FileScanErrorResult(Option(t.getMessage), Option(t)))
            }
        }

      Some(maxUploadSizeForControl)
    }

    def updateProgress(b: Array[Byte], off: Int, len: Int): Option[FileScanResult] = {

      progressOpt foreach (_.receivedSize += len)

      fileScanOpt map {
        case Left(fileScan2) => withFileScanCall2(fileScan2.bytesReceived(b, off, len))
        case Right(fileScan) => withFileScanCall(fileScan.bytesReceived(b, off, len))
      }
    }

    def fileItemState(state: UploadState[DiskFileItem]): Option[FileScanResult] = {

      progressOpt foreach (_.state = state)

      state match {
        case UploadState.Completed(fileItem) =>

          val file = FileItemSupport.fileFromFileItemCreateIfNeeded(fileItem)

          fileScanOpt map {
            case Left(fileScan2) => withFileScanCall2(fileScan2.complete(file))
            case Right(fileScan) => withFileScanCall(fileScan.complete(file))
          }
        case UploadState.Interrupted(_) =>
          try
            fileScanOpt foreach {
              case Left(fileScan2) => fileScan2.abort()
              case Right(fileScan) => fileScan.abort()
            }
          catch {
            case NonFatal(t) =>
              info(s"error thrown by file scan provider while calling `abort()`: ${OrbeonFormatter.getThrowableMessage(t)}")
          }
          None
        case UploadState.Started =>
          None
      }
    }

    def interrupted(): Unit =
      // - don't remove `UploadProgress` objects from the session
      // - instead mark all entries added so far as being in state `Interrupted` if not already the case
      for (sessionKey <- sessionKeys)
        runQuietly {
          UploaderServer.getUploadProgress(session, sessionKey)
          .collect { case p @ UploadProgress(_, _, _, UploadState.Started | UploadState.Completed(_) ) => p }
          .foreach (_.state = UploadState.Interrupted(None))
        }
  }

  private object Private {

    implicit val logger: slf4j.Logger = LoggerFactory.createLogger("org.orbeon.xforms.upload").logger

    private val UploadProgressSessionKey = "orbeon.upload.progress."

    def getProgressSessionKey(uuid: String, fieldName: String): String =
      UploadProgressSessionKey + uuid + "." + fieldName

    def checkSizeLimitExceeded(maxSize: MaximumSize, currentSize: Long): Unit = maxSize match {
      case UnlimitedSize        =>
      case LimitedSize(maxSize) =>
        if (currentSize > maxSize)
          Multipart.throwSizeLimitExceeded(maxSize, currentSize)
    }

    def convertFileItemHeaders(headers: FileItemHeaders): List[(String, List[String])] =
      for (name <- headers.getHeaderNames.asScala.toList)
        yield name -> headers.getHeaders(name).asScala.toList

    def withFileScanCall(block: => FileScanStatus): FileScanResult =
      Try(block) match {
        case Success(FileScanStatus.Accept) => FileScanAcceptResult(None)
        case Success(FileScanStatus.Reject) => FileScanRejectResult(Some("File scan rejected uploaded content"))
        case Success(FileScanStatus.Error)  => FileScanErrorResult(Some("File scan returned an error"), None)
        case Failure(t)                     => FileScanErrorResult(Option(t.getMessage), Option(t))
      }

    def withFileScanCall2(block: => JFileScanResult): FileScanResult =
      Try(block) match {
        case Success(fileScanResponse) => fileScanResultFromJavaApi(fileScanResponse)
        case Failure(t)                => FileScanErrorResult(message = Option(t.getMessage), throwable = Option(t))
      }

    lazy val fileScanProviderOpt: Option[Either[FileScanProvider2, FileScanProvider]] =
      ServiceProviderSupport.loadProvider[FileScanProvider2]("file scan", checkVersion).kestrel(_.init()).map(Left.apply)
        .orElse(ServiceProviderSupport.loadProvider[FileScanProvider]("file scan", checkVersion).kestrel(_.init()).map(Right.apply))

    private def checkVersion(): Unit =
      Version.instance.requirePEFeature("File scan API")

    // The Java API uses its own ADT. Here we convert from that to our native Scala ADT.
    private def fileScanResultFromJavaApi(jfsr: JFileScanResult): FileScanResult =
      jfsr match {
        case r: JFileScanResult.FileScanAcceptResult =>
          FileScanAcceptResult(
            message   = Option(r.message)  .flatMap(_.trimAllToOpt),
            mediatype = Option(r.mediatype).flatMap(_.trimAllToOpt),
            filename  = Option(r.filename) .flatMap(_.trimAllToOpt),
            content   = Option(r.content),
            extension = Option(r.extension).map(_.asScala.toMap)
          )
        case r: JFileScanResult.FileScanRejectResult =>
          FileScanRejectResult(
            message   = Option(r.message).flatMap(_.trimAllToOpt)
          )
        case r: JFileScanResult.FileScanErrorResult  =>
          FileScanErrorResult(
            message   = Option(r.message).flatMap(_.trimAllToOpt),
            throwable = Option(r.throwable)
          )
      }
  }
}
