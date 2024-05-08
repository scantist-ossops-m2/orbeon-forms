/**
 * Copyright (C) 2015 Orbeon, Inc.
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
package org.orbeon.builder

import org.orbeon.facades.{Bowser, Mousetrap}
import org.orbeon.fr._
import org.orbeon.oxf.util.CoreUtils.BooleanOps
import org.orbeon.web.DomEventNames
import org.orbeon.xforms._
import org.scalajs.dom
import org.scalajs.dom.ext._
import org.scalajs.dom.html

import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}


// Scala.js starting point for Form Builder
object FormBuilderApp extends App {

  def onOrbeonApiLoaded(): Unit = {

    FormRunnerApp.onOrbeonApiLoaded()

    val orbeonDyn = g.window.ORBEON

    val builderDyn = {
      if (js.isUndefined(orbeonDyn.builder))
        orbeonDyn.builder = new js.Object
      orbeonDyn.builder
    }

    val builderPrivateDyn = {
      if (js.isUndefined(builderDyn.`private`))
        builderDyn.`private` = new js.Object
      builderDyn.`private`
    }

    builderPrivateDyn.API = FormBuilderPrivateAPI
    registerFormBuilderKeyboardShortcuts()
    updateKeyboardShortcutHints()

    // Other initializations
    BlockCache
  }

  private case class Shortcut(
    shift     : Boolean = false,
    key       : String,
    target    : String,
    condition : Option[js.Function0[Boolean]] = None
  )

  private val shortcuts = List(
    Shortcut(              key = "z", target = "undo-trigger"                                                                    ),
    Shortcut(shift = true, key = "z", target = "redo-trigger"                                                                    ),
    Shortcut(              key = "x", target = "cut-trigger"                                                                     ),
    Shortcut(              key = "c", target = "copy-trigger", condition = Some(() => dom.window.getSelection().toString.isEmpty)),
    Shortcut(              key = "v", target = "paste-trigger"                                                                   ),
  )

  private def registerFormBuilderKeyboardShortcuts(): Unit =
    shortcuts.foreach { case Shortcut(shift, key, target, condition) =>
      List("command", "ctrl").foreach(modifier => {
        val keyCombination = (List(modifier) ++ shift.list("shift") ++ List(key)).mkString("+")
        Mousetrap.bind(command = keyCombination, callback = { (e: dom.KeyboardEvent, combo: String) =>
          if (condition.forall(_.apply())) {
            e.preventDefault()
            AjaxClient.fireEvent(
              AjaxEvent(
                eventName = DomEventNames.DOMActivate,
                targetId  = target,
                form      = Support.allFormElems.headOption, // 2023-09-01: only used by Form Builder, so presumably only one
              )
            )
          }
        })
      })
    }

  private def updateKeyboardShortcutHints(): Unit =
    if (! Set("macOS", "iOS")(Bowser.osname))
      dom.window.document.querySelectorAll(".orbeon *[title], .orbeon kbd").foreach {
        case elem: html.Element if (elem.title ne null) && elem.title.contains("⌘") =>
          elem.title = elem.title.replace("⌘", "⌃")
        case elem: html.Element if elem.tagName.equalsIgnoreCase("kbd") && elem.innerHTML.contains("⌘") =>
          elem.innerHTML = elem.innerHTML.replace("⌘", "⌃")
        case _ =>
      }

  private def updateKeyboardShortcutHints(): Unit =
    if (! Set("macOS", "iOS")(Bowser.osname))
      dom.window.document.querySelectorAll(".orbeon *[title], .orbeon kbd").foreach {
        case elem: html.Element if (elem.title ne null) && elem.title.contains("⌘") =>
          elem.title = elem.title.replace("⌘", "⌃")
        case elem: html.Element if elem.tagName.equalsIgnoreCase("kbd") && elem.innerHTML.contains("⌘") =>
          elem.innerHTML = elem.innerHTML.replace("⌘", "⌃")
        case _ =>
      }

  def onPageContainsFormsMarkup(): Unit = {

    FormRunnerApp.onPageContainsFormsMarkup()

    DialogItemset
    ControlDnD
    SectionGridEditor
    RowEditor
    LabelEditor
    ControlEditor
    ControlLabelHintTextEditor
    GridWallDnD

    BrowserCheck.checkSupportedBrowser()
  }
}
