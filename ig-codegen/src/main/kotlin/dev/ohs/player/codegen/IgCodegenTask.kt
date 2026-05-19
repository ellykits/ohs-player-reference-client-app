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
package dev.ohs.player.codegen

import dev.ohs.player.codegen.generator.BinaryGenerator
import dev.ohs.player.codegen.generator.CodeSystemGenerator
import dev.ohs.player.codegen.generator.StructureDefinitionGenerator
import dev.ohs.player.codegen.model.ViewJoinMap
import dev.ohs.player.codegen.model.fhir.CodeSystem
import dev.ohs.player.codegen.model.fhir.StructureDefinition
import dev.ohs.player.codegen.model.fhir.ViewDefinition
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * Gradle task that reads FHIR IG artifacts from [igDir] and writes generated Kotlin source files to
 * [outputDir].
 *
 * Sub-package routing is fixed by convention:
 * - CodeSystems whose id contains `view-type` → `viewtype`
 * - Logical StructureDefinitions whose name ends in `Config` → `config`
 * - Other logical StructureDefinitions → `spec`
 *
 * Two definitions are always skipped:
 * - `ViewJoinMap` — hand-coded in the library (`dev.ohs.player.library.model`)
 * - `ViewDefinition` — recursive BackboneElement structure; managed manually in the library
 *
 * Annotated with [@CacheableTask] so Gradle can skip execution when inputs are unchanged.
 */
@CacheableTask
abstract class IgCodegenTask : DefaultTask() {

  /** Path to the `fsh-generated/resources/` directory of the compiled IG. */
  @get:Input abstract val igDir: Property<String>

  /** Root Kotlin package for all emitted files (e.g. `dev.ohs.player.generated`). */
  @get:Input abstract val packageName: Property<String>

  /** Directory into which generated `.kt` files are written. */
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val resourcesDir = File(igDir.get())
    require(resourcesDir.isDirectory) {
      "ig-codegen: igDir '${resourcesDir.absolutePath}' is not a directory. " +
        "Set 'ohs.ig.dir' in gradle.properties or local.properties."
    }

    val outDir = outputDir.get().asFile
    outDir.deleteRecursively()
    outDir.mkdirs()

    val pkg = packageName.get()

    // Load all logical StructureDefinitions for parent-chain field inheritance
    val allStructDefs =
      resourcesDir
        .listFiles { f -> f.name.startsWith("StructureDefinition-") && f.extension == "json" }
        ?.mapNotNull { file ->
          runCatching { json.decodeFromString<StructureDefinition>(file.readText()) }
            .getOrNull()
            ?.takeIf { it.kind == "logical" }
        } ?: emptyList()

    val structDefById = allStructDefs.associateBy { it.id }

    // Generate CodeSystems
    resourcesDir
      .listFiles { f -> f.name.startsWith("CodeSystem-") && f.extension == "json" }
      ?.forEach { file ->
        val cs =
          runCatching { json.decodeFromString<CodeSystem>(file.readText()) }
            .getOrElse {
              logger.warn("ig-codegen: failed to parse ${file.name}: ${it.message}")
              return@forEach
            }
        val subPkg =
          routeCodeSystem(cs)
            ?: run {
              logger.info("ig-codegen: skipping CodeSystem '${cs.id}' (no routing match)")
              return@forEach
            }
        CodeSystemGenerator(pkg, subPkg, outDir).generate(cs)
        logger.lifecycle("ig-codegen: generated CodeSystem → ${cs.name}")
      }

    // Load Binary ViewDefinition artifacts (keyed by name)
    val binaryFiles =
      resourcesDir.listFiles { f -> f.name.startsWith("Binary-") && f.extension == "json" }
        ?: emptyArray()

    val binaryViewDefs =
      binaryFiles
        .mapNotNull { file ->
          runCatching {
              val vd = json.decodeFromString<ViewDefinition>(file.readText())
              vd.takeIf {
                it.resourceType == "https://sql-on-fhir.org/ig/StructureDefinition/ViewDefinition"
              }
            }
            .getOrNull()
        }
        .associateBy { it.name }

    // Generate state classes + extractors from Binary ViewJoinMap artifacts
    val binaryGen = BinaryGenerator(pkg, outDir, binaryViewDefs)
    binaryFiles.forEach { file ->
      val map =
        runCatching {
            val vjm = json.decodeFromString<ViewJoinMap>(file.readText())
            vjm.takeIf { it.resourceType == "http://ohs.dev/StructureDefinition/ViewJoinMap" }
          }
          .getOrNull() ?: return@forEach
      binaryGen.generate(map)
      logger.lifecycle("ig-codegen: generated state+extractor → ${map.name}")
    }

    // Generate StructureDefinitions
    allStructDefs.forEach { sd ->
      val subPkg =
        routeStructDef(sd)
          ?: run {
            logger.info("ig-codegen: skipping StructureDefinition '${sd.id}'")
            return@forEach
          }
      StructureDefinitionGenerator(pkg, subPkg, outDir, structDefById).generate(sd)
      logger.lifecycle("ig-codegen: generated StructureDefinition → ${sd.name}")
    }
  }

  /**
   * Determines the sub-package for a [CodeSystem] based on its id.
   *
   * Returns `null` to skip the CodeSystem entirely. `search-scope` is omitted — 'SearchScope' is
   * hand-coded in the library.
   */
  private fun routeCodeSystem(cs: CodeSystem): String? =
    when {
      cs.id.contains("view-type", ignoreCase = true) -> "viewtype"
      else -> null
    }

  /**
   * Determines the sub-package for a logical [StructureDefinition] based on its name.
   *
   * Returns `null` to skip the definition entirely. Skipped definitions:
   * - `ViewJoinMap` — hand-coded in the library (`dev.ohs.player.library.model`)
   * - `ViewDefinition` — recursive BackboneElement structure, authored manually in the library
   */
  private fun routeStructDef(sd: StructureDefinition): String? =
    when (sd.id) {
      "ViewJoinMap",
      "ViewDefinition" -> null // hand-coded in library
      else -> if (sd.name.endsWith("Config")) "config" else "spec"
    }
}
