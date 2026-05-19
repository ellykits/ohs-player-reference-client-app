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
 * Declares the FHIRPath expression and source resource type that populate a state property.
 *
 * Applied by the ig-codegen tool to each property of a generated state class. The annotation is
 * retained at runtime so tooling can inspect extraction metadata without reading IG JSON.
 *
 * @param expression FHIRPath expression evaluated against [resource] to produce this field's value.
 * @param resource FHIR resource type name (e.g. "Patient", "AllergyIntolerance") against which
 *   [expression] is evaluated.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FhirPath(val expression: String, val resource: String)
