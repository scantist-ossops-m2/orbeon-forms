package org.orbeon.web

import org.orbeon.oxf.util.CollectionUtils.*
import org.orbeon.web.DomEventNames.*
import org.scalajs.dom
import org.scalajs.dom.{DocumentReadyState, HTMLCollection, document, html}

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.scalajs.js


object DomSupport {

  implicit class DomElemOps[T <: dom.Element](private val elem: T) extends AnyVal {

    def querySelectorAllT(selectors: String): collection.Seq[T] =
      elem.querySelectorAll(selectors).asInstanceOf[dom.NodeList[T]]

    def querySelectorT(selectors: String): T =
      elem.querySelector(selectors).asInstanceOf[T]

    def querySelectorOpt(selectors: String): Option[T] =
      Option(elem.querySelector(selectors).asInstanceOf[T])

    def closestT(selector: String): T =
      elem.closest(selector).asInstanceOf[T]

    def closestOpt(selector: String): Option[T] =
      Option(elem.closestT(selector))

    def childrenT: collection.Seq[T] =
      elem.children.asInstanceOf[HTMLCollection[T]]

    def parentElementOpt: Option[T] =
      Option(elem.asInstanceOf[js.Dynamic].parentElement.asInstanceOf[T])

    def ancestorOrSelfElem: Iterator[T] =
      Iterator.iterate(elem)(_.asInstanceOf[js.Dynamic].parentElement.asInstanceOf[T]).takeWhile(_ ne null)
  }

  implicit class DomDocOps(private val doc: html.Document) extends AnyVal {

    def getElementByIdT(elementId: String): html.Element =
      doc.getElementById(elementId).asInstanceOf[html.Element]

    def getElementByIdOpt(elementId: String): Option[html.Element] =
      Option(doc.getElementById(elementId).asInstanceOf[html.Element])

    def createElementT(tagName: String): html.Element =
      doc.createElement(tagName).asInstanceOf[html.Element]

    def querySelectorAllT(selectors: String): collection.Seq[html.Element] =
      doc.querySelectorAll(selectors).asInstanceOf[dom.NodeList[html.Element]]

    def querySelectorT(selectors: String): html.Element =
      doc.querySelector(selectors).asInstanceOf[html.Element]

    def querySelectorOpt(selectors: String): Option[html.Element] =
      Option(querySelectorT(selectors))
  }

  implicit class DomEventOps(private val event: dom.Event) extends AnyVal {

    def targetT: html.Element =
      event.target.asInstanceOf[html.Element]

    def targetOpt: Option[html.Element] =
      Option(event.targetT)

  }

  private var lastUsedSuffix: Int = 0

  private val AtLeastDomInteractiveStates = Set(DocumentReadyState.interactive, DocumentReadyState.complete)
  private val DomCompleteStates           = Set(DocumentReadyState.complete)

  sealed trait DomReadyState
  case object DomReadyState {
    case object Interactive extends DomReadyState // doc parsed but scripts, images, stylesheets and frames are still loading
    case object Complete    extends DomReadyState // doc and all sub-resources have finished loading, `load` about to fire
  }

  private def interactiveReadyState(doc: html.Document, state: DomReadyState): Boolean =
    state == DomReadyState.Interactive && AtLeastDomInteractiveStates(doc.readyState) ||
    state == DomReadyState.Complete    && DomCompleteStates(doc.readyState)

  def atLeastDomReadyStateF(doc: html.Document, state: DomReadyState): Future[Unit] = {

    val promise = Promise[Unit]()

    if (interactiveReadyState(doc, state)) {

      // Because yes, even if the document is interactive, JavaScript placed after us might not have run yet.
      // Although if we do everything in an async way, that should be changed.
      // TODO: Review once full order of JavaScript is determined in `App` doc.
      js.timers.setTimeout(0) {
        promise.success(())
      }: Unit
    } else {

      lazy val readyStateChanged: js.Function1[dom.Event, _] = (_: dom.Event) =>
        if (interactiveReadyState(doc, state)) {
          doc.removeEventListener(ReadystateChange, readyStateChanged)
          promise.success(())
        }

      doc.addEventListener(ReadystateChange, readyStateChanged)
    }

    promise.future
  }

  def findCommonAncestor(elems: List[html.Element]): Option[html.Element] = {

    def findFirstCommonAncestorForPair(elem1: html.Element, elem2: html.Element): Option[html.Element] =
      elem1.ancestorOrSelfElem.toList.reverseIterator
        .zip(elem2.ancestorOrSelfElem.toList.reverseIterator)
        .takeWhile { case (e1, e2) => e1.isSameNode(e2) }
        .lastOption()
        .map(_._1)

    @tailrec
    def recurse(elems: List[html.Element]): Option[html.Element] = {
      elems match {
        case Nil =>
          None
        case elem1 :: Nil =>
          Some(elem1)
        case elem1 :: elem2 :: rest =>
          findFirstCommonAncestorForPair(elem1, elem2) match {
            case Some(elem) => recurse(elem :: rest)
            case None       => None
          }
        case _ =>
          None
      }
    }

    recurse(elems)
  }

  def generateIdIfNeeded(element: dom.Element): String = {
    if (element.id == "") {
      def id(suffix: Int)       = s"xf-client-$suffix"
      def isUnused(suffix: Int) = document.getElementById(id(suffix)) == null
      val suffix                = Iterator.from(lastUsedSuffix + 1).find(isUnused).get
      element.id                = id(suffix)
      lastUsedSuffix            = suffix
    }
    element.id
  }

  def moveIntoViewIfNeeded(containerElem: html.Element, innerContainer: html.Element, itemElem: html.Element): Unit = {
    val containerRect       = containerElem.getBoundingClientRect()
    val itemRect            = itemElem.getBoundingClientRect()
    val isEntirelyContained =
      itemRect.left   >= containerRect.left   &&
      itemRect.top    >= containerRect.top    &&
      itemRect.bottom <= containerRect.bottom &&
      itemRect.right  <= containerRect.right
    if (! isEntirelyContained) {

      val overflowsBelow = itemRect.bottom > containerRect.bottom

      val Margin = 50

      val mainInnerRect = innerContainer.getBoundingClientRect()
      val scrollTop =
        if (overflowsBelow)
          containerRect.top - mainInnerRect.top + itemRect.bottom - containerRect.bottom + Margin
        else
          containerRect.top - mainInnerRect.top - (containerRect.top - itemRect.top + Margin)

      containerElem.asInstanceOf[js.Dynamic].scrollTo(
        js.Dynamic.literal(top = scrollTop, behavior = "smooth")
      )
    }
  }
}
