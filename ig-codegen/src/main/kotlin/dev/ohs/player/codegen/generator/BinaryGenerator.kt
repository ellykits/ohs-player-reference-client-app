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
package dev.ohs.player.codegen.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import dev.ohs.player.codegen.model.ViewJoinMap
import dev.ohs.player.codegen.model.fhir.ViewDefinition
import dev.ohs.player.codegen.writeFormattedTo
import java.io.File

/**
 * Generates a `@Serializable` state data class from a [ViewJoinMap], merging the columns of its pivot
 * [ViewDefinition] and every joined one. The class name is the PascalCased map name plus `State`
 * (e.g. `patientAllergy` → `PatientAllergyState`).
 *
 * Extraction is not generated: the library's `GenericStateExtractor` interprets the same binaries at
 * runtime and deserializes rows into these classes. The state class is the only artifact emitted here.
 */
class BinaryGenerator(
  basePackage: String,
  private val outputDir: File,
  private val viewDefs: Map<String, ViewDefinition>,
) {
  private val statePkg = "$basePackage.state"

  private val serializableClass = ClassName("kotlinx.serialization", "Serializable")
  private val contextualClass = ClassName("kotlinx.serialization", "Contextual")
  private val fhirDateClass = ClassName("dev.ohs.fhir.model.r4", "FhirDate")
  private val fhirDateTimeClass = ClassName("dev.ohs.fhir.model.r4", "FhirDateTime")
  private val bigDecimalClass = ClassName("com.ionspin.kotlin.bignum.decimal", "BigDecimal")

  fun generate(map: ViewJoinMap) {
    val columns = mergedColumns(map)
    val stateName = "${map.name.replaceFirstChar { it.uppercase() }}State"
    generateState(stateName, map.name, columns)
  }

  /** All columns the state carries: the pivot view's, then each joined view's, in order. */
  private fun mergedColumns(map: ViewJoinMap): List<ViewDefinition.Column> = buildList {
    val pivotView =
      viewDefs[map.view]
        ?: error("BinaryGenerator: ViewDefinition '${map.view}' not found for map '${map.name}'")
    addAll(pivotView.allColumns())
    map.joins.forEachIndexed { i, join ->
      val joinView =
        viewDefs[join.view]
          ?: error("BinaryGenerator: ViewDefinition '${join.view}' not found in join[$i] of '${map.name}'")
      addAll(joinView.allColumns())
    }
  }

  private fun generateState(name: String, mapName: String, columns: List<ViewDefinition.Column>) {
    val constructor = FunSpec.constructorBuilder()
    val stateClass =
      TypeSpec.classBuilder(name).addModifiers(KModifier.DATA).addAnnotation(serializableClass)

    // Deduplicate by name: unionAll blocks repeat the same column names across sub-blocks.
    columns
      .distinctBy { it.name }
      .forEach { column ->
        val type = fieldType(column)
        val default = if (column.collection) "emptyList()" else "null"
        constructor.addParameter(ParameterSpec.builder(column.name, type).defaultValue(default).build())

        val property = PropertySpec.builder(column.name, type).initializer(column.name)
        if (!column.collection && needsContextual(column.type)) {
          property.addAnnotation(contextualClass)
        }
        stateClass.addProperty(property.build())
      }

    FileSpec.builder(statePkg, name)
      .addFileComment("Generated from ViewJoinMap '$mapName'. Do not edit manually.")
      .addType(stateClass.primaryConstructor(constructor.build()).build())
      .build()
      .writeFormattedTo(outputDir)
  }

  /** Collection columns become `List<T>` (non-null elements); scalar columns become `T?`. */
  private fun fieldType(column: ViewDefinition.Column): TypeName {
    val scalar = scalarType(column.type)
    return if (column.collection) {
      List::class.asClassName().parameterizedBy(scalar.copy(nullable = false))
    } else {
      scalar.copy(nullable = true)
    }
  }

  private fun scalarType(fhirType: String?): TypeName =
    when (fhirType?.substringAfterLast('/')) {
      "boolean" -> Boolean::class.asTypeName()
      "decimal" -> bigDecimalClass
      "integer",
      "positiveInt",
      "unsignedInt" -> Int::class.asTypeName()
      "integer64" -> Long::class.asTypeName()
      "date" -> fhirDateClass
      "dateTime",
      "instant" -> fhirDateTimeClass
      else -> String::class.asTypeName()
    }

  /** Types that need `@Contextual` for kotlinx-serialization to resolve them. */
  private fun needsContextual(fhirType: String?): Boolean =
    fhirType?.substringAfterLast('/') in setOf("decimal", "date", "dateTime", "instant")
}
