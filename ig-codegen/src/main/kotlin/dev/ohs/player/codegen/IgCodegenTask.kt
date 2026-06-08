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
import dev.ohs.player.codegen.generator.ConfigBinaryGenerator
import dev.ohs.player.codegen.model.ViewConfigDefinition
import dev.ohs.player.codegen.model.ViewJoinMap
import dev.ohs.player.codegen.model.CodeSystem
import dev.ohs.player.codegen.model.ViewDefinition
import dev.ohs.player.codegen.util.json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gradle task that generates Kotlin source from runtime config Binaries.
 *
 * Every `Binary-*.json` under [sourcesDir] is routed by its top-level `resourceType` — the same
 * discriminator the runtime `ConfigStore` uses — and turned into typed code:
 * - **ViewDefinition** → columns feeding state generation
 * - **ViewJoinMap** → a `@Serializable` state data class in the `state` package
 * - **ViewConfig** → a `@Serializable` config data class in the `config` package
 * - **CodeSystem** → a view-type constants object in the `viewtype` package
 *
 * The task does not read the IG: the IG is the blueprint (it defines these shapes and ships examples),
 * while the artifacts consumed here are the implementer's own, provided wherever they choose to store
 * them.
 */
@CacheableTask
abstract class IgCodegenTask : DefaultTask() {

  /** Directory tree of runtime config `Binary-*.json` files (e.g. the app's bundled config). */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourcesDir: DirectoryProperty

  /** Root Kotlin package for all emitted files (e.g. `dev.ohs.player.generated`). */
  @get:Input abstract val packageName: Property<String>

  /** Directory into which generated `.kt` files are written. */
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val sourcesRoot = sourcesDir.get().asFile
    require(sourcesRoot.isDirectory) {
      "ig-codegen: sourcesDir '${sourcesRoot.absolutePath}' is not a directory."
    }

    val outDir = outputDir.get().asFile
    outDir.deleteRecursively()
    outDir.mkdirs()

    val pkg = packageName.get()

    // Pair each Binary file with its parsed JSON once, so routing reads resourceType cheaply.
    val binaries =
      sourcesRoot
        .walkTopDown()
        .filter { it.isFile && it.name.startsWith("Binary-") && it.extension == "json" }
        .mapNotNull { file -> runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() }
        .toList()

    fun JsonObject.resourceType(): String? = this["resourceType"]?.jsonPrimitive?.content

    // ViewDefinitions provide the columns; index them by name for the state generator.
    val viewDefs =
      binaries
        .filter { it.resourceType() == VIEW_DEFINITION }
        .map { json.decodeFromJsonElement(ViewDefinition.serializer(), it) }
        .associateBy { it.name }

    // ViewJoinMap → state class.
    val binaryGen = BinaryGenerator(pkg, outDir, viewDefs)
    binaries
      .filter { it.resourceType() == VIEW_JOIN_MAP }
      .map { json.decodeFromJsonElement(ViewJoinMap.serializer(), it) }
      .forEach { map ->
        binaryGen.generate(map)
        logger.lifecycle("ig-codegen: generated state → ${map.name}")
      }

    // ViewConfig → config class.
    val configGen = ConfigBinaryGenerator(pkg, outDir)
    binaries
      .filter { it.resourceType() == VIEW_CONFIG }
      .map { json.decodeFromJsonElement(ViewConfigDefinition.serializer(), it) }
      .forEach { def ->
        configGen.generate(def)
        logger.lifecycle("ig-codegen: generated config → ${def.viewType}")
      }

    // CodeSystem → view-type constants.
    val codeSystemGen = CodeSystemGenerator(pkg, "viewtype", outDir)
    binaries
      .filter { it.resourceType() == "CodeSystem" }
      .map { json.decodeFromJsonElement(CodeSystem.serializer(), it) }
      .forEach { cs ->
        codeSystemGen.generate(cs)
        logger.lifecycle("ig-codegen: generated view types → ${cs.name}")
      }
  }

  private companion object {
    const val VIEW_DEFINITION = "https://sql-on-fhir.org/ig/StructureDefinition/ViewDefinition"
    const val VIEW_JOIN_MAP = "http://ohs.dev/StructureDefinition/ViewJoinMap"
    const val VIEW_CONFIG = "http://ohs.dev/StructureDefinition/ViewConfig"
  }
}
