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
package org.orbeon.oxf.xforms.control

import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.xforms.analysis.controls.{LHHA, LHHAAnalysis, StaticLHHASupport}
import org.orbeon.oxf.xforms.control.LHHASupport.*
import org.orbeon.oxf.xforms.control.XFormsControl.*
import org.orbeon.oxf.xforms.event.EventCollector
import org.orbeon.oxf.xforms.event.EventCollector.ErrorEventCollector
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.xforms.XFormsId
import org.orbeon.xforms.analysis.model.ValidationLevel
import shapeless.syntax.typeable.*


trait ControlLHHASupport {

  self: XFormsControl =>

  // Label, help, hint and alert (evaluated lazily)
  // 2013-06-19: We support multiple alerts, but only one client-facing alert value at this point.
  // NOTE: `var` because of cloning
  private[ControlLHHASupport] var lhhaMap: Map[LHHA, LHHAProperty] = Map.empty

  // XBL Container in which dynamic LHHA elements like `xf:output` and AVTs evaluate
  def lhhaContainer: XBLContainer = container

  def markLHHADirty(): Unit =
    lhhaMap.valuesIterator.foreach(_.handleMarkDirty())

  // This is needed because, unlike the other LHH, the alert doesn't only depend on its expressions: it also depends
  // on the control's current validity and validations. Because we don't have yet a way of taking those in as
  // dependencies, we force dirty alerts whenever such validations change upon refresh.
  def forceDirtyAlert(): Unit =
    lhhaMap.get(LHHA.Alert).foreach(_.handleMarkDirty(force = true))

  def evaluateNonRelevantLHHA(): Unit =
    lhhaMap = Map.empty

  // Copy LHHA
  def updateLHHACopy(copy: XFormsControl, collector: ErrorEventCollector): Unit = {
    lhhaMap.valuesIterator.foreach(_.value(collector)) // evaluate lazy values before copying
    copy.lhhaMap = lhhaMap // simply assign as we use an immutable `Map`
  }

  def lhhaProperty(lhha: LHHA): LHHAProperty =
    lhhaMap.getOrElse(lhha, {
      val property = evaluateLhha(lhha)
      lhhaMap += lhha -> property
      property
    })

  // NOTE: Ugly because of imbalanced hierarchy between static/runtime controls
  private def evaluateLhha(lhha: LHHA): LHHAProperty =
    self.staticControl match {
      case staticLhhaSupport: StaticLHHASupport if staticLhhaSupport.hasDirectLhha(lhha) =>
        self match {
          case singleNodeControl: XFormsSingleNodeControl if lhha == LHHA.Alert =>
            new MutableAlertProperty(singleNodeControl, lhha, htmlLhhaSupport(lhha))
          case control: XFormsControl if lhha != LHHA.Alert =>
            new MutableLHHProperty(control, lhha, htmlLhhaSupport(lhha))
          case _ =>
            NullLHHA
        }
      case _ =>
        NullLHHA
    }

  def eagerlyEvaluateLhha(collector: ErrorEventCollector): Unit =
    for (lhha <- LHHA.values)
      lhhaProperty(lhha).value(collector)

  def htmlLhhaSupport: Set[LHHA] = LHHA.DefaultLHHAHTMLSupport
  def ajaxLhhaSupport: Seq[LHHA] = LHHA.values

  def compareLHHA(other: XFormsControl, collector: ErrorEventCollector): Boolean =
    ajaxLhhaSupport forall (lhha => lhhaProperty(lhha).value(collector) == other.lhhaProperty(lhha).value(collector))

  // Convenience accessors
  final def getLabel   (collector: ErrorEventCollector): Option[String] = Option(lhhaProperty(LHHA.Label).value(collector))
  final def isHTMLLabel(collector: ErrorEventCollector): Boolean = lhhaProperty(LHHA.Label).isHTML(collector)
  final def getHelp    (collector: ErrorEventCollector): Option[String] = Option(lhhaProperty(LHHA.Help).value(collector))
  final def getHint    (collector: ErrorEventCollector): Option[String] = Option(lhhaProperty(LHHA.Hint).value(collector))
  final def getAlert   (collector: ErrorEventCollector): Option[String] = Option(lhhaProperty(LHHA.Alert).value(collector))
  final def isHTMLAlert(collector: ErrorEventCollector): Boolean = lhhaProperty(LHHA.Alert).isHTML(collector)

  lazy val referencingControl: Option[(StaticLHHASupport, XFormsSingleNodeControl)] = {
    for {
      lhhaSupport           <- self.staticControl.cast[StaticLHHASupport]
      staticRc              <- lhhaSupport.referencingControl
      concreteRcEffectiveId = XFormsId.buildEffectiveId(staticRc.prefixedId, XFormsId.getEffectiveIdSuffixParts(self.effectiveId))
      concreteRc            <- containingDocument.findControlByEffectiveId(concreteRcEffectiveId)
      concreteSnRc          <- concreteRc.cast[XFormsSingleNodeControl]
    } yield
      staticRc -> concreteSnRc
  }

  def lhhaValue(lhha: LHHA): Option[String] =
    (
      self.staticControl match { // Scala 3: `.match`
        case s: StaticLHHASupport if s.hasDirectLhha(lhha) => Some(self)
        case s: StaticLHHASupport if s.hasByLhha(lhha)     => self.referencingControl.map(_._2)
        case _                                             => None
      }
    )
    .flatMap(_.lhhaProperty(lhha)
    .valueOpt(EventCollector.Throw))
}

// NOTE: Use name different from trait so that the Java compiler is happy
object LHHASupport {

  val NullLHHA = new NullLHHAProperty

  // Control property for LHHA
  trait LHHAProperty extends ControlProperty[String] {
    def escapedValue(collector: ErrorEventCollector): String
    def isHTML(collector: ErrorEventCollector): Boolean
  }

  // Immutable null LHHA property
  class NullLHHAProperty extends ImmutableControlProperty(null: String) with LHHAProperty {
    def escapedValue(collector: ErrorEventCollector): String = null
    def isHTML(collector: ErrorEventCollector) = false
  }

  // Gather all active alerts for the given control following a selection algorithm
  //
  // - This depends on
  //     - the control validity
  //     - failed validations
  //     - alerts in the UI matching validations or not
  // - If no alert is active for the control, return None.
  // - Only alerts for the highest ValidationLevel are returned.
  //
  def gatherActiveAlerts(control: XFormsSingleNodeControl): Option[(ValidationLevel, List[LHHAAnalysis])] =
    if (control.isRelevant) {

      val staticAlerts = control.staticControl.asInstanceOf[StaticLHHASupport].alerts

      def nonEmptyOption[T](l: List[T]) = l.nonEmpty option l

      def alertsMatchingValidations = {
        val failedValidationsIds = control.failedValidations.map(_.id).to(Set)
        nonEmptyOption(staticAlerts.filter(_.forValidations.intersect(failedValidationsIds).nonEmpty))
      }

      // Find all alerts which match the given level, if there are any failed validations for that level
      // NOTE: ErrorLevel is handled specially: in addition to failed validations, the level matches if the
      // control is not valid for any reason including failed schema validation.
      def alertsMatchingLevel(level: ValidationLevel) =
        nonEmptyOption(staticAlerts filter (_.forLevels(level)))

      // Alerts that specify neither a validations nor a level
      def alertsMatchingAny =
        nonEmptyOption(staticAlerts filter (a => a.forValidations.isEmpty && a.forLevels.isEmpty))

      // For that given level, identify all matching alerts if any, whether they match by validations or by level.
      // Alerts that specify neither a validation nor a level are considered a default, that is, they are not added
      // if other alerts have already been matched.
      // Alerts are returned in document order
      control.alertLevel flatMap { level =>

        val alerts =
          alertsMatchingValidations  orElse
          alertsMatchingLevel(level) orElse
          alertsMatchingAny          getOrElse
          Nil

        val matchingAlertIds = alerts.map(_.staticId).toSet
        val matchingAlerts   = staticAlerts.filter(a => matchingAlertIds(a.staticId))

        matchingAlerts.nonEmpty option (level, matchingAlerts)
      }
    } else
      None
}