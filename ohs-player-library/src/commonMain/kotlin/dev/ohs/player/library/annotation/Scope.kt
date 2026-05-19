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
package dev.ohs.player.library.annotation

/**
 * Describes one resource scope (pivot or join) within a [JoinMap].
 *
 * @param resource FHIR resource type (e.g. "AllergyIntolerance", "Patient").
 * @param from SearchResult bucket: "root", "included", or "revIncluded".
 * @param searchParam Search parameter name used to locate this resource in the bucket (e.g.
 *   "patient" for revIncluded AllergyIntolerance).
 * @param matchKey Column name in the pivot view whose value is matched against this scope's
 *   resource id to perform the join. Empty string means no match key (broadcast join).
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class Scope(
  val resource: String,
  val from: String,
  val searchParam: String = "",
  val matchKey: String = "",
)
