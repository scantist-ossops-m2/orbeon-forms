/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.process

import cats.effect.IO
import org.orbeon.oxf.fr.process.ProcessInterpreter.*
import org.orbeon.oxf.fr.process.ProcessParser.*
import org.orbeon.oxf.fr.process.TestProcessInterpreter.ConstantProcessId
import org.orbeon.oxf.test.{DocumentTestBase, ResourceManagerSupport}
import org.orbeon.oxf.util.CoreCrossPlatformSupport.{executionContext, runtime}
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.util.{FunctionContext, IndentedLogger, LoggerFactory}
import org.orbeon.saxon.functions.FunctionLibrary
import org.orbeon.saxon.om.{Item, NodeInfo}
import org.orbeon.saxon.value.BooleanValue
import org.parboiled2.ParseError
import org.scalatest.funspec.AnyFunSpecLike

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}


object TestProcessInterpreter {
    val ConstantProcessId = "9fbcdd2e3caf46045e2d545c26d54ffd5b241e97"
}

trait TestProcessInterpreter extends ProcessInterpreter {

  implicit val logger: IndentedLogger = new IndentedLogger(LoggerFactory.createLogger(classOf[TestProcessInterpreter]), true)

  def currentXFormsDocumentId = ""
  def findProcessByName(scope: String, name: String): Option[String] = None
  def processError(t: Throwable) = ()
  var xpathContext: Item = null
  val xpathFunctionLibrary: FunctionLibrary = null
  def xpathFunctionContext: FunctionContext = null

  // Just store the continuation locally
  def clearSuspendedProcess(): Unit = _suspendedProcess = None
  def writeSuspendedProcess(processId: String, process: String): Unit = _suspendedProcess = Some(processId -> process)
  def readSuspendedProcess: Try[(String, String)] = Try(_suspendedProcess.get)

  def submitContinuation[T, U](message: String, computation: IO[T], continuation: Try[T] => Either[Try[U], Future[U]]): Future[U] = ???

  // Constant so that we can test properly
  override def createUniqueProcessId: String = ConstantProcessId

  def transactionStart(): Unit = ()
  def transactionRollback(): Unit = ()

  private var _suspendedProcess: Option[(String, String)] = None
  def savedProcess: Option[(String, String)] = _suspendedProcess

  def withRunProcess[T](scope: String, name: String)(body: => T): T = body

  // a1-a20 successful actions which log a trace of their execution
  override def extensionActions: Iterable[(String, Action)] =
    (1 to 20 map ("a" + _) map (name => name -> mySuccessAction(name) _))

  protected val _trace = ListBuffer[String]()
  def trace: String = _trace mkString " "

  def mySuccessAction(name: String)(params: ActionParams): ActionResult =
    ActionResult.trySync {
      _trace += name
    }
}

class SimpleProcessTest
extends DocumentTestBase
   with ResourceManagerSupport // access to resources is needed because `XPathCache` needs the default cache size
   with AnyFunSpecLike {


  def normalize(s: String) = "(" + s.trimAllToEmpty + ")"

  describe("serialization") {

    val processes = Seq(
      """save""",
      """save("draft")""",
      """visit-all then captcha then validate("error") recover (visit-all then expand-all then error-message("form-validation-error") then success)""",
      """if ("//secret = 42") then success-message(message = "yea") else error-message(message = "nay")"""
    )

    for (p <- processes)
      it(s"must pass with `$p`") {
        assert(normalize(p) === parse(p).serialize)
      }
  }

  describe("invalid") {
    val processes = Seq(
      """if ("xpath") then a1 then a2 else a3""",
      """if ("xpath") a1 then a2"""
    )

    for (p <- processes)
      it(s"must pass with `$p`") {
        intercept[ParseError](parse(p))
      }
  }

  describe("suspendResume") {

    val interpreter = new TestProcessInterpreter {

      override def beforeProcess(): Try[Any] = {
        _trace.clear()
        Success(())
      }

      val processes = Map(
        "p1" -> """a1 then a2 then suspend then a3""",
        "p2" -> """a1 then (a2 then (a3 then if (". = true()") then (a4 then suspend then (a5 then a6)) else (a7 then suspend then (a8 then a9)) then a10) then a11) then a12""",
        "p3" -> """a13 then if ("true()") then p2 else p1 then a14""",
        "p4" -> """a1 then suspend""",
        "p5" -> """a2 then p4 then a3"""
      )

      override def findProcessByName(scope: String, name: String) = processes.get(name)
    }

    val expected = Seq(
      ("p1", null,               """nop then a3""",                                               "a1 a2"),
      ("p2", BooleanValue.TRUE,  """nop then (a5 then a6) then a10 then a11 then a12""",          "a1 a2 a3 a4"),
      ("p2", BooleanValue.FALSE, """nop then (a8 then a9) then a10 then a11 then a12""",          "a1 a2 a3 a7"),
      ("p3", BooleanValue.TRUE,  """nop then (a5 then a6) then a10 then a11 then a12 then a14""", "a13 a1 a2 a3 a4"),
      ("p3", BooleanValue.FALSE, """nop then (a8 then a9) then a10 then a11 then a12 then a14""", "a13 a1 a2 a3 a7"),
      ("p5", null,               """nop then a3""",                                               "a2 a1")
    )

    for ((process, context, continuation, trace) <- expected) {
      it(s"must pass with `$process/$context`") {
        interpreter.xpathContext = context
        interpreter.runProcessByName("", process)
        assert(interpreter.savedProcess.contains((ConstantProcessId, continuation)))
        assert(trace == interpreter.trace)
        interpreter.runProcess("", "resume") match {
          case Left(t)  => t.get
          case Right(_) => throw new IllegalStateException
        }
      }
    }
  }

  describe("renderedFormatParametersSelection") {

    import FormRunnerRenderedFormat.*
    import org.orbeon.scaxon.NodeConversions.*

    val Tests = List[(String, NodeInfo, ActionParams, String, List[(String, String)])](
      (
        "no attachment",
        <attachments/>,
        Map.empty,
        "en",
        List(
          s"fr-$UsePdfTemplateParam" -> "false",
          "fr-remember-language"     -> "false",
          "fr-language"              -> "en"
        )
      ),
      (
        "legacy attachment with no name or language",
        <attachments>
          <pdf>data:</pdf>
        </attachments>,
        Map.empty,
        "en",
        List(
          s"fr-$UsePdfTemplateParam" -> "true"
        )
      ),
      (
        "empty name but languages, using default language",
        <attachments>
          <pdf name="" lang="en">data:</pdf>
          <pdf name="" lang="fr">data:</pdf>
        </attachments>,
        Map.empty,
        "fr",
        List(
          s"fr-$UsePdfTemplateParam"  -> "true",
          s"fr-$PdfTemplateLangParam" -> "fr"
        )
      ),
      (
        "empty name but languages, specifying language",
        <attachments>
          <pdf name="" lang="en">data:</pdf>
          <pdf name="" lang="fr">data:</pdf>
        </attachments>,
        Map(
          Some(PdfTemplateLangParam) -> "fr"
        ),
        "en",
        List(
          s"fr-$UsePdfTemplateParam"  -> "true",
          s"fr-$PdfTemplateLangParam" -> "fr"
        )
      ),
      (
        "names and languages, using default language",
        <attachments>
          <pdf name="foo" lang="en">data:</pdf>
          <pdf name="foo" lang="fr">data:</pdf>
          <pdf name="bar" lang="en">data:</pdf>
          <pdf name="bar" lang="fr">data:</pdf>
        </attachments>,
        Map(
          Some(PdfTemplateNameParam) -> "bar"
        ),
        "fr",
        List(
          s"fr-$UsePdfTemplateParam"  -> "true",
          s"fr-$PdfTemplateNameParam" -> "bar",
          s"fr-$PdfTemplateLangParam" -> "fr"
        )
      ),
      (
        "names and languages, specifying language",
        <attachments>
          <pdf name="foo" lang="en">data:</pdf>
          <pdf name="foo" lang="fr">data:</pdf>
          <pdf name="bar" lang="en">data:</pdf>
          <pdf name="bar" lang="fr">data:</pdf>
        </attachments>,
        Map(
          Some(PdfTemplateLangParam) -> "fr",
          Some(PdfTemplateNameParam) -> "bar"
        ),
        "en",
        List(
          s"fr-$UsePdfTemplateParam"  -> "true",
          s"fr-$PdfTemplateNameParam" -> "bar",
          s"fr-$PdfTemplateLangParam" -> "fr"
        )
      ),
      (
        "legacy empty attachment",
        <attachments>
          <pdf mediatype="application/pdf" filename="" size=""/>
        </attachments>,
        Map.empty,
        "en",
        List(
          s"fr-$UsePdfTemplateParam" -> "false",
          "fr-remember-language"     -> "false",
          "fr-language"              -> "en"
        )
      )
    )

    for ((description, elem, params, defaultLang, expected) <- Tests)
      it(s"must pass with $description") {
        assert(
          expected ===
            createPdfOrTiffParams(
              Some(elem),
              params,
              defaultLang
            )
        )
      }
  }

  describe("submitContinuation") {

    val MyAsyncSuccessAction = "my-async-success"
    val MyAsyncFailureAction = "my-async-failure"

    val MyProcesses = Map(
      "my-process"              -> s"""a1 then a2 then $MyAsyncSuccessAction(v = "after a2") then a3 then $MyAsyncSuccessAction(v = "after a3") then a4""",
      "my-process-with-failure" -> s"""a1 then a2 then $MyAsyncFailureAction(v = "after a2") then a3 then $MyAsyncSuccessAction(v = "after a3") then a4 recover $MyAsyncSuccessAction(v = "after a4") then a5 then a6""",
    )

    class ContinuationTestProcessInterpreter extends TestProcessInterpreter {

      override def findProcessByName(scope: String, name: String): Option[String] = MyProcesses.get(name)

      override def extensionActions: List[(String, Action)] =
        super.extensionActions ++: (
          (MyAsyncSuccessAction -> myASyncAction(MyAsyncSuccessAction, Success(())) _) ::
          (MyAsyncFailureAction -> myASyncAction(MyAsyncFailureAction, Failure(new IllegalArgumentException)) _) ::
          Nil
        )

      val completionQueue = new ConcurrentLinkedQueue[(Try[Any] => Either[Try[Any], Future[Any]], Promise[Any], Try[Any])]
      var pendingList     = List.empty[Future[Any]]

      override def submitContinuation[T, U](message: String, computation: IO[T], continuation: Try[T] => Either[Try[U], Future[U]]): Future[U] = {

        val p = Promise[U]()

        def preProcessFutureCompletion(result: Try[T]): Try[T] = {
          // Make sure we add to the completion queue before the `Future` in the pendingList is completed
          completionQueue.add((continuation.asInstanceOf[Try[Any] => Either[Try[Any], Future[Any]]], p.asInstanceOf[Promise[Any]], result))
          result
        }

        pendingList ::= computation.unsafeToFuture().transform(preProcessFutureCompletion)

        p.future
      }

      def awaitResultAndProcessSingleBatch(): Unit = {

        val batch = pendingList
        pendingList = Nil

        Await.ready(Future.sequence(batch), Duration.Inf)

        // Do it only once
        Option(completionQueue.poll()).foreach {
          case (continuation, callerPromise, resultTry) =>
            continuation(resultTry) match {
              case Left(t) =>
                callerPromise.complete(t)
              case Right(future) =>
                future.onComplete(result => callerPromise.complete(result))
            }
        }
      }

      def myASyncAction[T](name: String, continuationResult: Try[T])(params: ActionParams): ActionResult =
        ActionResult.tryAsync {

          _trace += s"$name-start"

          val io = IO(params.getOrElse(Some("v"), throw new IllegalArgumentException))

          def continuation(t: Try[String]): Try[Any] = {
            _trace += s"$name-continuation($t)"
            continuationResult
          }

          (io, continuation _)
        }
    }

    it(s"must pass process with async action and continuations that return a `Success`") {

      val Interpreter = new ContinuationTestProcessInterpreter

      Interpreter.xpathContext = null
      Interpreter.runProcessByName("", "my-process")

      assert(s"a1 a2 $MyAsyncSuccessAction-start" == Interpreter.trace)
      assert(Interpreter.pendingList.nonEmpty)

      Interpreter.awaitResultAndProcessSingleBatch()
      assert(s"a1 a2 $MyAsyncSuccessAction-start $MyAsyncSuccessAction-continuation(Success(after a2)) a3 $MyAsyncSuccessAction-start" == Interpreter.trace)
      assert(Interpreter.pendingList.nonEmpty)

      Interpreter.awaitResultAndProcessSingleBatch()
      assert(s"a1 a2 $MyAsyncSuccessAction-start $MyAsyncSuccessAction-continuation(Success(after a2)) a3 $MyAsyncSuccessAction-start $MyAsyncSuccessAction-continuation(Success(after a3)) a4" == Interpreter.trace)
      assert(Interpreter.pendingList.isEmpty)
    }

    it(s"must pass process with async action and continuations that returns a `Failure`") {

      val Interpreter = new ContinuationTestProcessInterpreter

      Interpreter.xpathContext = null
      Interpreter.runProcessByName("", "my-process-with-failure")

      assert(s"a1 a2 $MyAsyncFailureAction-start" == Interpreter.trace)
      assert(Interpreter.pendingList.nonEmpty)

      Interpreter.awaitResultAndProcessSingleBatch()
      assert(s"a1 a2 $MyAsyncFailureAction-start $MyAsyncFailureAction-continuation(Success(after a2)) $MyAsyncSuccessAction-start" == Interpreter.trace)
      assert(Interpreter.pendingList.nonEmpty)

      Interpreter.awaitResultAndProcessSingleBatch()
      assert(s"a1 a2 $MyAsyncFailureAction-start $MyAsyncFailureAction-continuation(Success(after a2)) $MyAsyncSuccessAction-start $MyAsyncSuccessAction-continuation(Success(after a4)) a5 a6" == Interpreter.trace)
      assert(Interpreter.pendingList.isEmpty)
    }
  }
}
