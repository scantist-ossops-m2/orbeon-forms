/**
 * Copyright (C) 2016 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.search

import org.orbeon.oxf.externalcontext.{Organization, UserAndGroup}
import org.orbeon.oxf.fr.permission.PermissionsAuthorization.CheckWithDataUser
import org.orbeon.oxf.fr.permission._
import org.orbeon.oxf.fr.persistence.PersistenceMetadataSupport
import org.orbeon.oxf.fr.persistence.relational.RelationalUtils.Logger
import org.orbeon.oxf.fr.persistence.relational.Statement._
import org.orbeon.oxf.fr.persistence.relational.rest.{OrganizationId, OrganizationSupport}
import org.orbeon.oxf.fr.persistence.relational.search.adt._
import org.orbeon.oxf.fr.persistence.relational.search.part._
import org.orbeon.oxf.fr.persistence.relational.{Provider, RelationalUtils}
import org.orbeon.oxf.fr.{FormDefinitionVersion, FormRunner}
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.SQLUtils._

import java.sql.{Connection, Timestamp}
import scala.collection.mutable


trait SearchLogic extends SearchRequestParser {

  private def computePermissions(
    request: SearchRequest,
    version: FormDefinitionVersion
  ): SearchPermissions = {

    val searchOperations     = request.anyOfOperations.getOrElse(SearchOps.SearchOperations)
    val formPermissionsElOpt = PersistenceMetadataSupport.readFormPermissions(request.appForm, version)
    val formPermissions      = FormRunner.permissionsFromElemOrProperties(formPermissionsElOpt, request.appForm)

    SearchPermissions(
      formPermissions,
      authorizedBasedOnRoleOptimistic  = PermissionsAuthorization.authorizedBasedOnRole(formPermissions, request.credentials, searchOperations, optimistic = true),
      authorizedBasedOnRolePessimistic = PermissionsAuthorization.authorizedBasedOnRole(formPermissions, request.credentials, searchOperations, optimistic = false),
      authorizedIfUsername             = PermissionsAuthorization.hasPermissionCond(formPermissions, Condition.Owner, searchOperations).flatOption(request.credentials.map(_.userAndGroup.username)),
      authorizedIfGroup                = PermissionsAuthorization.hasPermissionCond(formPermissions, Condition.Group, searchOperations).flatOption(request.credentials.map(_.userAndGroup.groupname)).flatten,
      authorizedIfOrganizationMatch    = SearchOps.authorizedIfOrganizationMatch(formPermissions, request.credentials)
    )
  }

  private def doSearch[T, R <: SearchRequest](request: R, noPermissionValue: T)(body: (R, Connection, List[StatementPart], SearchPermissions) => T): T = {

    val version          = PersistenceMetadataSupport.getEffectiveFormVersionForSearchMaybeCallApi(request.appForm, request.version)
    val permissions      = computePermissions(request, version)
    val hasNoPermissions =
      ! permissions.authorizedBasedOnRoleOptimistic     &&
      permissions.authorizedIfUsername         .isEmpty &&
      permissions.authorizedIfGroup            .isEmpty &&
      permissions.authorizedIfOrganizationMatch.isEmpty

    if (hasNoPermissions)
      // There is no chance we can access any data, no need to run any SQL
      noPermissionValue
    else
      RelationalUtils.withConnection { connection =>

        val commonParts = List(
          commonPart         (request, version),
          columnFilterPart   (request),
          permissionsPart    (permissions)
        )

        body(request, connection, commonParts, permissions)
      }
    }

  def doDocumentSearch(request: DocumentSearchRequest): (List[DocumentResult], Int) =
    doSearch(request, noPermissionValue = (List[DocumentResult](), 0)) {
      case (request: DocumentSearchRequest, connection: Connection, commonParts: List[StatementPart], permissions: SearchPermissions) =>

        val statementParts = commonParts :+ draftsPart(request) :+ freeTextFilterPart(request)
        val innerSQL       = buildQuery(statementParts)

        val searchCount = {
          val sql =
            s"""SELECT count(*)
               |  FROM (
               |       $innerSQL
               |       ) a
             """.stripMargin

          Logger.logDebug("search total query", sql)
          executeQuery(connection, sql, statementParts) { rs =>
            rs.next()
            rs.getInt(1)
          }
        }

        // Build SQL and create statement
        val sql = {
          val startOffsetZeroBased = (request.pageNumber - 1) * request.pageSize
          val rowNumSQL            = Provider.rowNumSQL(request.provider, connection, tableAlias = "d")
          val rowNumCol            = rowNumSQL.col
          val rowNumOrderBy        = rowNumSQL.orderBy
          val rowNumTable          = rowNumSQL.table match {
            case Some(table) => table + ","
            case None        => ""
          }

          // Use `LEFT JOIN` instead of regular join, in case the form doesn't have any control marked
          // to be indexed, in which case there won't be anything for it in `orbeon_i_control_text`.
          s"""SELECT
             |    c.*,
             |    t.control,
             |    t.pos,
             |    t.val
             |FROM
             |    (
             |        SELECT
             |            d.*,
             |            $rowNumCol
             |        FROM
             |            (
             |                SELECT
             |                    c.*
             |                FROM
             |                    $rowNumTable
             |                    (
             |                        $innerSQL
             |                    ) s
             |                INNER JOIN
             |                    orbeon_i_current c
             |                    ON c.data_id = s.data_id
             |            ) d
             |        $rowNumOrderBy
             |    ) c
             | LEFT JOIN
             |    orbeon_i_control_text t
             |    ON t.data_id = c.data_id
             | WHERE
             |    row_num
             |        BETWEEN ${startOffsetZeroBased + 1}
             |        AND     ${startOffsetZeroBased + request.pageSize}
             |""".stripMargin
        }
        Logger.logDebug("search items query", sql)

        val documentsMetadataValues = executeQuery(connection, sql, statementParts) { documentsResultSet =>

          Iterator.iterateWhile(
            cond = documentsResultSet.next(),
            elem = (
                DocumentMetadata(
                  documentId       = documentsResultSet.getString                 ("document_id"),
                  draft            = documentsResultSet.getString                 ("draft") == "Y",
                  createdTime      = documentsResultSet.getTimestamp              ("created"),
                  lastModifiedTime = documentsResultSet.getTimestamp              ("last_modified_time"),
                  createdBy        = UserAndGroup.fromStrings(documentsResultSet.getString("username"),         documentsResultSet.getString("groupname")),
                  lastModifiedBy   = UserAndGroup.fromStrings(documentsResultSet.getString("last_modified_by"), ""),
                  workflowStage    = Option(documentsResultSet.getString          ("stage")),
                  organizationId   = RelationalUtils.getIntOpt(documentsResultSet, "organization_id")
                ),
                DocumentValue(
                  control          = documentsResultSet.getString                 ("control"),
                  pos              = documentsResultSet.getInt                    ("pos"),
                  value            = documentsResultSet.getString                 ("val")
                )
            )
          )
            .toList

            // Group row by common metadata, since the metadata is repeated in the result set
            .groupBy(_._1).mapValues(_.map(_._2)).toList

            // Sort by last modified in descending order, as the call expects the result to be pre-sorted
            .sortBy(_._1.lastModifiedTime)(Ordering[Timestamp].reverse)
        }

        // Compute possible operations for each document
        val organizationsCache = mutable.Map[Int, Organization]()
        val documents = documentsMetadataValues.map{ case (metadata, values) =>
            def readFromDatabase(id: Int) = OrganizationSupport.read(connection, OrganizationId(id)).get
            val organization              = metadata.organizationId.map(id => organizationsCache.getOrElseUpdate(id, readFromDatabase(id)))
            val check                     = CheckWithDataUser(metadata.createdBy, organization)
            val operations                = PermissionsAuthorization.authorizedOperations(permissions.formPermissions, request.credentials, check)
            DocumentResult(metadata, Operations.serialize(operations, normalized = true).mkString(" "), values)
          }

        (documents, searchCount)
    }

  def doFieldSearch(request: FieldSearchRequest): List[FieldResult] =
    doSearch(request, noPermissionValue = List[FieldResult]()) {
      case (request: FieldSearchRequest, connection: Connection, commonParts: List[StatementPart], _: SearchPermissions) =>

        val innerSQL = buildQuery(commonParts)

        // Retrieve distinct values for all queried fields
        request.fields.map { field =>

          val sql =
            s"""SELECT
               |    DISTINCT t.val
               |FROM
               |    ($innerSQL) c
               |LEFT JOIN
               |    orbeon_i_control_text t
               |    ON t.data_id = c.data_id
               |WHERE
               |    t.control = ?
               |""".stripMargin

          val controlPathPart = StatementPart("", List[Setter]((ps, i) => ps.setString(i, field.path)))

          val values = executeQuery(connection, sql, commonParts :+ controlPathPart) { valuesResultSet =>
            Iterator.iterateWhile(
              cond = valuesResultSet.next(),
              elem = valuesResultSet.getString("val")
            ).toList
          }

          FieldResult(field.path, values)
        }
    }
}
