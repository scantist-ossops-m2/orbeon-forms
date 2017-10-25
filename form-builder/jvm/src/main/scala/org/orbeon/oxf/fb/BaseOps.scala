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
package org.orbeon.oxf.fb

import org.orbeon.oxf.fb.ToolboxOps.XcvEntry
import org.orbeon.oxf.fr.FormRunner._
import org.orbeon.oxf.fr.Names
import org.orbeon.oxf.util.{IndentedLogger, Logging}
import org.orbeon.oxf.xforms.XFormsConstants.COMPONENT_SEPARATOR
import org.orbeon.oxf.xforms.XFormsProperties
import org.orbeon.oxf.xforms.action.XFormsAPI._
import org.orbeon.oxf.xforms.model.{XFormsInstance, XFormsModel}
import org.orbeon.oxf.xml.TransformerUtils
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.Implicits._
import org.orbeon.scaxon.SimplePath._

import scala.collection.immutable

case class FormBuilderDocContext(
  explicitFormDefinitionInstance : Option[NodeInfo],   // for annotating the form definition outside of an instance
  formBuilderModel               : Option[XFormsModel] // always present at runtime, but missing for annotation tests
) {

  lazy val formDefinitionInstance = formBuilderModel flatMap (_.findInstance("fb-form-instance"))
  lazy val xcvInstance            = formBuilderModel flatMap (_.findInstance("fb-xcv-instance"))
  lazy val undoInstance           = formBuilderModel flatMap (_.findInstance("fb-undo-instance"))

  lazy val formDefinitionRootElem = explicitFormDefinitionInstance getOrElse formDefinitionInstance.get.rootElement

  lazy val componentBindings: Seq[NodeInfo] =
    asScalaSeq(formBuilderModel.get.getVariable("component-bindings")).asInstanceOf[Seq[NodeInfo]]

  lazy val clipboardXcvRootElem = xcvInstance.get.rootElement
  lazy val undoRootElem         = undoInstance.get.rootElement

  lazy val formResourcesRoot: NodeInfo =
    formBuilderModel.get.unsafeGetVariableAsNodeInfo("resources")

  lazy val modelElem             = findModelElem(formDefinitionRootElem)
  lazy val dataInstanceElem      = instanceElemFromModelElem(modelElem, Names.FormInstance).get
  lazy val metadataInstanceElem  = instanceElemFromModelElem(modelElem, Names.MetadataInstance).get
  lazy val resourcesInstanceElem = instanceElemFromModelElem(modelElem, Names.FormResources).get
  lazy val topLevelBindElem      = findTopLevelBindFromModelElem(modelElem)
  lazy val bodyElem              = findFRBodyElem(formDefinitionRootElem)

  lazy val dataRootElem          = dataInstanceElem      / * head
  lazy val metadataRootElem      = metadataInstanceElem  / * head
  lazy val resourcesRootElem     = resourcesInstanceElem / * head
}

object FormBuilderDocContext {

  // Create with a specific form definition document, but still pass a model to provide access to variables
  def apply(inDoc: NodeInfo): FormBuilderDocContext =
    FormBuilderDocContext(Some(inDoc.rootElement), topLevelModel(Names.FormModel))

  def apply(formBuilderModel: XFormsModel): FormBuilderDocContext =
    FormBuilderDocContext(None, Some(formBuilderModel))

  def apply(): FormBuilderDocContext =
    FormBuilderDocContext(topLevelModel(Names.FormModel) getOrElse (throw new IllegalStateException))
}

trait BaseOps extends Logging {

  implicit def logger: IndentedLogger = inScopeContainingDocument.getIndentedLogger("form-builder")

  // Minimal version of IE supported for Form Builder
  // 2017-10-06: Starting Orbeon Forms 2017.2, we don't support IE11 anymore and require Edge.
  //@XPathExpression
  val MinimalIEVersion = 12

  // Id of the xxf:dynamic control holding the edited form
  val DynamicControlId = "fb"

  // Find the top-level form model of the form being edited
  def getFormModel: XFormsModel =
    inScopeContainingDocument.getObjectByEffectiveId(s"$DynamicControlId${COMPONENT_SEPARATOR}fr-form-model")
      .asInstanceOf[XFormsModel] ensuring (_ ne null, "did not find fb$fr-form-model")

  def templateRoot(repeatName: String)(implicit ctx: FormBuilderDocContext): Option[NodeInfo] =
    inlineInstanceRootElem(ctx.formDefinitionRootElem, templateId(repeatName))

  // Find the next available id for a given token
  def nextId(token: String)(implicit ctx: FormBuilderDocContext): String =
    nextIds(token, 1).head

  def nextTmpId()(implicit ctx: FormBuilderDocContext): String =
    nextTmpIds(count = 1).head

  private def idsIterator(docWithIdsInstanceOrElem: XFormsInstance Either NodeInfo): Iterator[String] = {

    val formDefinitionRootElem = docWithIdsInstanceOrElem match {
      case Left(instance)  ⇒ instance.rootElement
      case Right(formElem) ⇒ formElem
    }

    def elementIdsFromIndexOpt =
      for {
        formDefinitionInstance ← docWithIdsInstanceOrElem.left.toOption
        if formDefinitionHasIndex(formDefinitionRootElem.root)
      } yield
        formDefinitionInstance.idsIterator

    def elementIdsFromXPath =
      (formDefinitionRootElem descendantOrSelf *).ids.iterator

    elementIdsFromIndexOpt getOrElse elementIdsFromXPath
  }

  // The idea is that we can search ids in the following ways:
  //
  // - looking for `id` attributes in a document, whether via an index or XPath
  // - looking for element names in a another, optional document
  //
  // The resulting `Iterator` can contain duplicates.
  //
  // NOTE: We consider that an `-iteration` suffix is not allowed as a control name,
  // and always used only as a suffix of a repeated grid or section name.
  //
  def iterateNamesInUse(
    docWithIdsInstanceOrElem : XFormsInstance Either NodeInfo,
    dataElemOpt              : Option[NodeInfo]
  ): Iterator[String] = {

    val namesFromIndexIt = idsIterator(docWithIdsInstanceOrElem) flatMap controlNameFromIdOpt
    val namesFromDataIt  = dataElemOpt.toList descendant * map (_.localname)

    namesFromIndexIt ++ namesFromDataIt filterNot (_.endsWith(DefaultIterationSuffix))
  }

  // Special id namespace for `tmp-n-tmp` ids. We don't care if those are used in data as element names, or
  // if they are in the clipboard.
  def nextTmpIds(token: String = "tmp", count: Int)(implicit ctx: FormBuilderDocContext): immutable.IndexedSeq[String] = {

    val allIdsIt = idsIterator(ctx.explicitFormDefinitionInstance.toRight(ctx.formDefinitionInstance.get))

    val prefix = token + "-"
    val suffix = "-" + token

    val allTmpIdsInUse =
      collection.mutable.Set() ++ allIdsIt filter (id ⇒ id.startsWith(prefix) && id.endsWith(suffix))

    var guess = 1

    def nextId(): String = {

      def buildName(i: Int) = prefix + i + suffix

      while (allTmpIdsInUse(buildName(guess)))
        guess += 1

      val result = buildName(guess)
      allTmpIdsInUse += result
      result
    }

    for (_ ← 1 to count)
      yield nextId()
  }

  // Find a series of next available ids for a given token
  // Return ids of the form "foo-123-foo", where "foo" is the token
  def nextIds(token: String, count: Int)(implicit ctx: FormBuilderDocContext): immutable.IndexedSeq[String] = {

    val prefix = token + "-"
    val suffix = "-" + token

    val allNamesInUse =
      collection.mutable.Set() ++
        // Ids coming from the form definition
        iterateNamesInUse(ctx.explicitFormDefinitionInstance.toRight(ctx.formDefinitionInstance.get), Some(ctx.dataRootElem)) ++ {
        // Ids coming from the special cut/copy/paste instance, if present
        ctx.xcvInstance match {
          case Some(xcvInstance) ⇒

            val dataElemOpt =
              ctx.xcvInstance flatMap (_.rootElement / XcvEntry.Holder.entryName headOption)

            iterateNamesInUse(Left(xcvInstance), dataElemOpt)
          case None ⇒ Nil
        }
      }

    var guess = 1

    def nextId(): String = {

      def buildName(i: Int) = prefix + i

      while (allNamesInUse(buildName(guess)))
        guess += 1

      val result = buildName(guess)
      allNamesInUse += result
      result + suffix
    }

    for (_ ← 1 to count)
      yield nextId()
  }

  def makeInstanceExpression(name: String): String = "instance('" + name + "')"

  def withDebugGridOperation[T](message: String)(body: ⇒ T)(implicit ctx: FormBuilderDocContext): T = {
    debugDumpDocument(s"before $message")
    val result = body
    debugDumpDocument(s"after $message")
    result
  }

  def debugDumpDocument(message: String)(implicit ctx: FormBuilderDocContext): Unit =
    debug(message, Seq("doc" → TransformerUtils.tinyTreeToString(ctx.formDefinitionRootElem)))

  def insertElementsImposeOrder(into: Seq[NodeInfo], origin: Seq[NodeInfo], order: Seq[String]): Seq[NodeInfo] = {
    val name            = origin.head.localname
    val namesUntil      = (order takeWhile (_ != name)) :+ name toSet
    val elementsBefore  = into child * filter (e ⇒ namesUntil(e.localname))

    insert(into = into, after = elementsBefore, origin = origin)
  }
}
