/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.player.codegen.model.fhir

import kotlinx.serialization.Serializable

/** Minimal model for deserializing a Binary ViewDefinition JSON artifact from the IG. */
@Serializable
data class ViewDefinition(
  val resourceType: String = "",
  val name: String,
  val resource: String,
  val status: String? = null,
  val constant: List<Constant> = emptyList(),
  val where: List<WhereClause> = emptyList(),
  val select: List<SelectBlock> = emptyList(),
) {
  /**
   * A named constant accessible in column expressions via `%name` syntax.
   *
   * Exactly one `value*` field should be set. The resolved [value] property returns whichever is
   * non-null.
   */
  @Serializable
  data class Constant(
    val name: String,
    val valueString: String? = null,
    val valueInteger: Int? = null,
    val valueBoolean: Boolean? = null,
    val valueDecimal: Double? = null,
  ) {
    val value: Any?
      get() = valueString ?: valueInteger ?: valueBoolean ?: valueDecimal
  }

  /**
   * A FHIRPath filter applied to each pivot resource before any column extraction. Resources where
   * the expression does not evaluate to `true` are skipped. Multiple clauses are AND-ed.
   */
  @Serializable data class WhereClause(val path: String)

  /**
   * A group of columns sharing an optional context path.
   * - [forEach]: expands one row per element at the given FHIRPath (equivalent to a SQL CROSS JOIN
   *   LATERAL). Column expressions are evaluated against each element.
   * - [forEachOrNull]: like [forEach] but emits one null row when the path returns empty (LEFT JOIN
   *   semantics), so the pivot always contributes at least one output row.
   * - [unionAll]: each inner [SelectBlock] must produce the same column schema; their rows are
   *   concatenated. Use when different path shapes map to the same logical columns.
   * - Plain block (all null / empty): columns evaluated once against the pivot.
   */
  @Serializable
  data class SelectBlock(
    val column: List<Column> = emptyList(),
    val forEach: String? = null,
    val forEachOrNull: String? = null,
    val unionAll: List<SelectBlock> = emptyList(),
  )

  /**
   * A single output column.
   *
   * [collection] = true means the path returns multiple values; the generated state field will be
   * `List<T>` instead of `T?`.
   */
  @Serializable
  data class Column(
    val name: String,
    val path: String,
    val type: String? = null,
    val collection: Boolean = false,
    val description: String? = null,
  )

  /**
   * All unique columns across all select blocks, in declaration order. For [SelectBlock.unionAll]
   * blocks only the first inner block's columns are included (all inner blocks share the same
   * schema).
   */
  fun allColumns(): List<Column> =
    select.flatMap { block ->
      when {
        block.unionAll.isNotEmpty() -> block.unionAll.first().column
        else -> block.column
      }
    }
}
