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
package org.orbeon.oxf.fr

import org.orbeon.dom
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.externalcontext.Credentials
import org.orbeon.oxf.fr.FormRunner.orbeonRolesFromCurrentRequest
import org.orbeon.oxf.fr.permission.{Operations, PermissionsAuthorization}
import org.orbeon.oxf.resources.ResourceManagerWrapper
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.{IndentedLogger, XPath}
import org.orbeon.oxf.xforms.NodeInfoFactory
import org.orbeon.oxf.xforms.action.XFormsAPI.insert
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}
import org.orbeon.scaxon.NodeConversions.*
import org.orbeon.scaxon.Implicits.*
import org.orbeon.scaxon.SimplePath.*


trait FormBuilderPermissionsOps {

  private val SupportedFormBuilderPermissionsPaths  = List(
    "/config/form-builder-permissions.xml",
    "/config/form-runner-roles.xml"
  )

  // Load form-builder-permissions.xml. For code called from XForms, that instance is loaded in permissions-model.xml.
  //@XPathFunction
  def formBuilderPermissionsConfiguration: Option[DocumentInfo] = {

    val resourceManager = ResourceManagerWrapper.instance

    SupportedFormBuilderPermissionsPaths collectFirst { case key if resourceManager.exists(key) =>
      new DocumentWrapper(resourceManager.getContentAsOrbeonDom(key), null, XPath.GlobalConfiguration)
    }
  }

  //@XPathFunction
  def formBuilderPermissionsForCurrentUserXPath(configurationOpt: Option[NodeInfo]): NodeInfo =
    formBuilderPermissionsForCurrentUserAsXML(configurationOpt, orbeonRolesFromCurrentRequest)

  // Result document contains a tree structure of apps and forms if roles are configured
  // NOTE: The result is sorted by app name first, then form name.
  def formBuilderPermissionsForCurrentUserAsXML(configurationOpt: Option[NodeInfo], incomingRoleNames: Set[String]): NodeInfo =
    if (findConfiguredRoles(configurationOpt).isEmpty) {
      <apps has-roles="false"/>
    } else {
      <apps has-roles="true">{
        formBuilderPermissions(configurationOpt, incomingRoleNames).toList.sortBy(_._1) map { case (app, forms) =>
          <app name={app}>{ forms.toList.sorted map { form => <form name={form}/> } }</app>
        }
      }</apps>
    }

  def formBuilderPermissions(configurationOpt: Option[NodeInfo], incomingRoleNames: Set[String]): Map[String, Set[String]] =
    findConfiguredRoles(configurationOpt) match {
      case Nil =>
        // No role configured
        Map.empty
      case configuredRoles =>
        // Roles configured
        val allConfiguredRoleNames = configuredRoles map (_.attValue("name")) toSet
        val applicableRoleNames    = allConfiguredRoleNames & incomingRoleNames
        val applicableRoles        = configuredRoles filter (e => (applicableRoleNames + "*")(e.attValue("name")))
        val applicableAppNames     = applicableRoles map (_.attValue("app")) toSet

        if (applicableAppNames("*")) {
          // User has access to all apps (and therefore all forms)
          Map("*" -> Set("*"))
        } else {
          // User has access to certain apps only
          (for {
            app <- applicableAppNames
            forms = {
              val applicableFormsForApp = applicableRoles filter (_.attValue("app") == app) map (_.attValue("form")) toSet

              if (applicableFormsForApp("*")) Set("*") else applicableFormsForApp
            }
          } yield
            app -> forms) toMap
        }
    }

  /** Given a list of forms metadata:
   *  - determines the operations the current user can perform,
   *  - annotates the `<form>` with an `operations="…"` attribute,
   *  - filters out forms the current user can perform no operation on.
   */
  def filterFormsAndAnnotateWithOperations(
    formsEls              : List[NodeInfo],
    allForms              : Boolean,
    ignoreAdminPermissions: Boolean,
    credentialsOpt        : Option[Credentials]
  )(implicit
    indentedLogger        : IndentedLogger
  ): List[NodeInfo] = {

    var wrapperOpt: Option[DocumentWrapper] = None

    val fbPermissions =
      formBuilderPermissions(
        FormRunner.formBuilderPermissionsConfiguration,
        orbeonRolesFromCurrentRequest
      )

    formsEls.flatMap { formEl =>

      val wrapper = wrapperOpt.getOrElse(
        // Create wrapper if we don't have one already
        new DocumentWrapper(dom.Document(), null, formEl.getConfiguration)
          |!> (w => wrapperOpt = Some(w)) // save wrapper for following iterations
      )

      val appName  = formEl.elemValue(Names.AppName)
      val formName = formEl.elemValue(Names.FormName)
      val hasAdminPermissionForAppForm  = {
        def canAccessEverything = fbPermissions.contains("*")
        def canAccessAppForm = {
          val formsUserCanAccess = fbPermissions.getOrElse(appName, Set.empty)
          formsUserCanAccess.contains("*") || formsUserCanAccess.contains(formName)
        }
        canAccessEverything || canAccessAppForm
      }

      // For each form, compute the operations the user can potentially perform
      val operations = {
        val adminOperation = hasAdminPermissionForAppForm.list("admin")
        val permissionsElement = formEl.child(Names.Permissions).headOption
        val otherOperations =
          Operations.serialize(
            PermissionsAuthorization.authorizedOperationsForNoData(
              permissions    = FormRunner.permissionsFromElemOrProperties(permissionsElement, AppForm(appName, formName)),
              credentialsOpt = credentialsOpt
            ),
            normalized = true
          )

        adminOperation ++ otherOperations
      }

      // Is this form metadata returned by the API?
      val keepForm =
        allForms                                                   ||     // all forms are explicitly requested
        (hasAdminPermissionForAppForm && ! ignoreAdminPermissions) || ! ( // admins can see everything
          formName == Names.LibraryFormName ||     // filter libraries
          operations.isEmpty                ||     // filter forms on which user can't possibly do anything
          formEl.elemValue("available") == "false" // filter forms marked as not available
        )

      // If kept, rewrite <form> to add operations="…" attribute
      keepForm list {
        val newFormEl      = wrapper.wrap(dom.Element("form"))
        val operationsAttr = NodeInfoFactory.attributeInfo("operations", operations mkString " ")
        val newFormContent = operationsAttr +: formEl.child(*)

        // 2024-07-23: I thought that it might not be necessary to include in the response the `<permissions>` element,
        // since we compute here the required `operations` attribute. However the Search API, as well as the persistence
        // proxy, require that the `<permissions>` element be returned.
        // https://doc.orbeon.com/form-runner/api/persistence/custom-persistence-providers#form-metadata-api

        insert(into = List(newFormEl), origin = newFormContent)

        newFormEl
      }
    }
  }

  private def findConfiguredRoles(configurationOpt: Option[NodeInfo]) = configurationOpt match {
    case Some(configuration) => configuration.root / * / "role" toList
    case None                => Nil
  }
}

object FormBuilderPermissionsOps extends FormBuilderPermissionsOps