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

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * DSL extension for configuring the IG codegen Gradle plugin.
 *
 * Sub-package routing is fixed by convention — no configuration required:
 * - **`viewtype`** — CodeSystems whose id contains `view-type`
 * - **`spec`** — CodeSystems whose id contains `search-scope`, plus non-config StructureDefinitions
 * - **`config`** — Logical StructureDefinitions whose name ends in `Config`
 *
 * ```kotlin
 * igCodegen {
 *     packageName = "dev.ohs.player.generated"
 * }
 * ```
 */
abstract class IgCodegenExtension @Inject constructor(objects: ObjectFactory) {

  /**
   * Absolute path to the `fsh-generated/resources/` directory of the IG.
   *
   * Resolution order:
   * 1. Gradle property `ohs.ig.dir` (set via `-Pohs.ig.dir=…` or `gradle.properties`)
   * 2. `local.properties` key `ohs.ig.dir`
   * 3. Fallback: sibling directory `../ohs-sample-ig/fsh-generated/resources` relative to root
   *    project.
   *
   * Resolved automatically in [IgCodegenPlugin] — you only need to set this if the auto-resolution
   * does not locate your IG.
   */
  val igDir: Property<String> = objects.property(String::class.java)

  /**
   * Future: URL pointing to a published FHIR package (e.g.
   * `https://packages.fhir.org/dev.ohs.ohs-player-config-ig/0.1.0`). When set the plugin will
   * download and unpack the package instead of reading from [igDir]. Not yet implemented — the
   * property is reserved for forward-compatibility.
   */
  val igPackageUrl: Property<String> = objects.property(String::class.java)

  /**
   * Root Kotlin package for all generated sources.
   *
   * Defaults to `dev.ohs.player.generated`.
   */
  val packageName: Property<String> =
    objects.property(String::class.java).apply { convention("dev.ohs.player.generated") }
}
