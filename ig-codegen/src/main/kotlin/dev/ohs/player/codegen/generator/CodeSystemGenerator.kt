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
import dev.ohs.player.codegen.model.fhir.CodeSystem
import dev.ohs.player.codegen.writeFormattedTo
import java.io.File

/**
 * Generates a Kotlin `object` from a FHIR [CodeSystem].
 *
 * Two modes are selected automatically based on the CodeSystem id:
 * - **Search-scope** (`id` contains `search-scope`): codes emitted as `const val String`
 * - **View-type** (`id` contains `view-type`): codes emitted as `val ViewType(…)`
 */
class CodeSystemGenerator(
  private val basePackage: String,
  private val subPackage: String,
  private val outputDir: File,
) {

  private val viewTypeClass = ClassName("dev.ohs.player.library.registry", "ViewType")

  fun generate(cs: CodeSystem) {
    val pkg = "$basePackage.$subPackage"
    val objectName = cs.name // e.g. "SearchScopeCS", "ViewTypeCS"
    val isSearchScope = cs.id.contains("search-scope", ignoreCase = true)

    val kdoc =
      buildString {
          cs.title?.let { append(it).append(".\n") }
          cs.description?.let { append("\n").append(it) }
        }
        .trim()

    val objectBuilder = TypeSpec.objectBuilder(objectName).addKdoc(kdoc)

    cs.concept.forEach { concept ->
      val propName = concept.code
      val displayDoc = concept.display?.let { "[$it]" } ?: concept.code

      if (isSearchScope) {
        // const val ROOT = "root"
        objectBuilder.addProperty(
          PropertySpec.builder(propName.toScreamingSnakeCase(), String::class, KModifier.CONST)
            .initializer("%S", concept.code)
            .addKdoc(displayDoc)
            .build()
        )
      } else {
        // val PatientCard = ViewType("PatientCard")
        objectBuilder.addProperty(
          PropertySpec.builder(propName, viewTypeClass)
            .initializer("%T(%S)", viewTypeClass, concept.code)
            .addKdoc(displayDoc)
            .build()
        )
      }
    }

    FileSpec.builder(pkg, objectName)
      .addFileComment("Generated from CodeSystem/${cs.id}. Do not edit manually.")
      .addType(objectBuilder.build())
      .build()
      .writeFormattedTo(outputDir)
  }

  private fun String.toScreamingSnakeCase(): String =
    replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
}
