/**
 * Copyright (C) 2010 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.processor.handlers.xhtml

import cats.syntax.option.*
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.xforms.analysis.{ElementAnalysis, LhhaControlRef, LhhaPlacementType}
import org.orbeon.oxf.xforms.analysis.controls.{LHHA, LHHAAnalysis}
import org.orbeon.oxf.xforms.control.controls.XFormsLHHAControl
import org.orbeon.oxf.xforms.control.{Controls, XFormsControl}
import org.orbeon.oxf.xforms.processor.handlers.{HandlerContext, XFormsBaseHandler}
import org.orbeon.oxf.xml.SaxSupport.AttributesImplOps
import org.orbeon.oxf.xml.XMLConstants.XHTML_NAMESPACE_URI
import org.orbeon.oxf.xml.XMLReceiver
import org.orbeon.oxf.xml.XMLReceiverSupport.*
import org.orbeon.xforms.XFormsId
import org.orbeon.xforms.XFormsCrossPlatformSupport
import org.xml.sax.Attributes
import shapeless.syntax.typeable.*


/**
 * Handler for label, help, hint and alert when those are placed outside controls.
 */
class XFormsLHHAHandler(
  uri            : String,
  localname      : String,
  qName          : String,
  localAtts      : Attributes,
  elementAnalysis: LHHAAnalysis,
  handlerContext : HandlerContext
) extends
  XFormsBaseHandlerXHTML(
    uri,
    localname,
    qName,
    localAtts,
    handlerContext,
    repeating  = false,
    forwarding = false
  ) {

  override def start(): Unit = {

    val lhhaEffectiveId = getEffectiveId

    implicit val xmlReceiver: XMLReceiver = handlerContext.controller.output

    // For https://github.com/orbeon/orbeon-forms/issues/3989
    def mustOmitStaticReadonlyHint(currentControl: XFormsControl): Boolean =
      elementAnalysis.lhhaType == LHHA.Hint && ! containingDocument.staticReadonlyHint && XFormsBaseHandler.isStaticReadonly(currentControl)

    elementAnalysis.lhhaPlacementType match {
      case LhhaPlacementType.External(_, _, Some(_)) =>

        // In this case, we handle our own value and don't ask it to the (repeated) controls, since there might be
        // zero, one, or several of them. We also currently don't handle constraint classes.

        // This duplicates code in `XFormsControlLifecycleHandler` as this handler doesn't derive from it.
        val currentControl =
          containingDocument.findControlByEffectiveId(lhhaEffectiveId)
            .getOrElse(throw new IllegalStateException(s"control not found for effective id: `$lhhaEffectiveId`"))

        // Case where the LHHA is external and in a shallower level of nesting of repeats.
        // NOTE: In this case, we don't output a `for` attribute. Instead, the repeated control will use
        // `aria-*` attributes to point to this element.

        if (! mustOmitStaticReadonlyHint(currentControl)) {
          val containerAtts =
            getContainerAttributes(uri, localname, attributes, lhhaEffectiveId, elementAnalysis, currentControl, None)

          val elementName =
            lhhaElementName(elementAnalysis.lhhaType)

          if (elementName == "button")
            containerAtts.addOrReplace("type", "button")

          withElement(
            localName = elementName,
            prefix    = handlerContext.findXHTMLPrefix,
            uri       = XHTML_NAMESPACE_URI,
            atts      = containerAtts
          ) {
            for {
              currentLHHAControl <- currentControl.narrowTo[XFormsLHHAControl]
              externalValue      <- currentLHHAControl.externalValueOpt(handlerContext.collector)
              if externalValue.nonEmpty
            } locally {
              if (elementAnalysis.element.attributeValueOpt("mediatype") contains "text/html")
                XFormsCrossPlatformSupport.streamHTMLFragment(externalValue, currentLHHAControl.getLocationData, handlerContext.findXHTMLPrefix)
              else
                xmlReceiver.characters(externalValue.toCharArray, 0, externalValue.length)
            }
          }
        }

      case LhhaPlacementType.External(directTargetControl, _, None) =>

        // One question is what control do we ask for the value of the LHHA? When we have a `xxbl:label-for`, we attach
        // the LHHA to both the direct control (so the XBL component itself) and the destination control if there is
        // one. This means that both will evaluate their LHHA values. We should be able to ask either one, is this
        // correct?

        val effectiveTargetControlOpt: Option[XFormsControl] =
          Controls.resolveControlsById(containingDocument, lhhaEffectiveId, directTargetControl.staticId, followIndexes = true).headOption

        if (effectiveTargetControlOpt.isEmpty)
          println(s"xxx effectiveTargetControlOpt.isEmpty: $lhhaEffectiveId")

        val effectiveTargetControl = effectiveTargetControlOpt.getOrElse(throw new IllegalStateException)

        if (! mustOmitStaticReadonlyHint(effectiveTargetControl)) {

          val labelForEffectiveIdWithNsOpt =
            elementAnalysis.lhhaType == LHHA.Label flatOption
              XFormsLHHAHandler.findLabelForEffectiveIdWithNs(elementAnalysis, XFormsId.getEffectiveIdSuffix(lhhaEffectiveId), handlerContext)

          handleLabelHintHelpAlert(
            lhhaAnalysis            = elementAnalysis,
            controlEffectiveIdOpt   = lhhaEffectiveId.some,
            forEffectiveIdWithNsOpt = labelForEffectiveIdWithNsOpt,
            requestedElementNameOpt = None,
            control                 = effectiveTargetControl,
            isExternal              = true
          )
        }

      case LhhaPlacementType.Local(_, _) =>
        // Q: Can this happen? Do we match on local LHHA?
        // 2020-11-13: This seems to happen.
        // 2022-06-08: still happens! `currentControl eq null`
    }
  }
}

object XFormsLHHAHandler {

  def coreControlLhhaByCondition(lhhaAnalysis: LHHAAnalysis): Boolean =
    (lhhaAnalysis.isForRepeat || lhhaAnalysis.lhhaType != LHHA.Label) && placeholderLhhaByCondition(lhhaAnalysis)

  def placeholderLhhaByCondition(lhhaAnalysis: LHHAAnalysis): Boolean =
    lhhaAnalysis.lhhaType match {
      case LHHA.Label | LHHA.Hint if lhhaAnalysis.isPlaceholder => false
      case _                                                    => true
    }

  def findLabelForEffectiveIdWithNs(
    lhhaAnalysis        : LHHAAnalysis,
    currentControlSuffix: String,
    handlerContext      : HandlerContext
  ): Option[String] =
    lhhaAnalysis.lhhaPlacementType.lhhaControlRef match {
      case LhhaControlRef.Control(targetControl) =>
        findTargetControlForEffectiveIdWithNs(lhhaAnalysis, targetControl, handlerContext)
      case LhhaControlRef.PrefixedId(targetPrefixedId) =>
        handlerContext.containingDocument.namespaceId(targetPrefixedId + currentControlSuffix).some
    }

  private def findTargetControlForEffectiveIdWithNs(
    lhhaAnalysis  : LHHAAnalysis,
    targetControl : ElementAnalysis,
    handlerContext: HandlerContext
  ): Option[String] = {

    // The purpose of this code is to identify the id of the target of the `for` attribute for the given target
    // control. In order to do that, we:
    //
    // - find which handler will process that control
    // - instantiate that handler
    // - so we can call `getForEffectiveId` on it
    //
    // NOTE: A possibly simpler better solution would be to always use the `foo$bar$$c.1-2-3` scheme for the `@for` id
    // of a control.

    // Push/pop component context so that handler resolution works
    if (! targetControl.scope.isTopLevelScope)
      handlerContext.pushComponentContext(targetControl.scope.scopeId)
    try
      handlerContext.controller.findHandlerFromElem(targetControl.element) match {
        case Some(handler: XFormsControlLifecycleHandler) => handler.getForEffectiveIdWithNs(lhhaAnalysis)
        case _                                            => None
      }
    finally
      if (! targetControl.scope.isTopLevelScope)
        handlerContext.popComponentContext()
  }
}