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
package dev.ohs.player.library.extractor

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import dev.ohs.fhir.fhirpath.FhirPathEngine
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime

/**
 * Lightweight wrapper around a single FHIRPath evaluation result.
 *
 * Provides typed accessors for all common output types so generated extractors can read values
 * without repetitive null/cast boilerplate.
 */
class EvalResult(val raw: Any?) {
  val str: String?
    get() =
      when (raw) {
        is dev.ohs.fhir.model.r4.Date -> raw.value?.toString()
        is dev.ohs.fhir.model.r4.DateTime -> raw.value?.toString()
        is dev.ohs.fhir.model.r4.Time -> raw.value?.toString()
        is FhirDate -> raw.toString()
        is FhirDateTime -> raw.toString()
        null -> null
        else -> raw.toString()
      }

  val bool: Boolean?
    get() = raw as? Boolean

  val int: Int?
    get() = (raw as? Number)?.toInt()

  val long: Long?
    get() = (raw as? Number)?.toLong()

  val float: Float?
    get() = (raw as? Number)?.toFloat()

  val double: Double?
    get() = (raw as? Number)?.toDouble()

  val decimal: BigDecimal?
    get() = raw as? BigDecimal

  val date: FhirDate?
    get() = raw as? FhirDate

  val dateTime: FhirDateTime?
    get() = raw as? FhirDateTime
}

/**
 * Evaluates [path] against [focus] and returns an [EvalResult] for the first result.
 *
 * [variables] are accessible in the expression via `%name` syntax (ViewDefinition `constant`
 * entries). Swallows evaluation errors — a failed expression returns [EvalResult] wrapping null.
 */
fun FhirPathEngine.eval(
  focus: Any?,
  path: String,
  variables: Map<String, Any?> = emptyMap(),
): EvalResult =
  EvalResult(
    runCatching {
        evaluateExpression(expression = path, base = focus, variables = variables).firstOrNull()
      }
      .getOrNull()
  )

/**
 * Evaluates [path] against [focus] and returns an [EvalResult] for every element in the result
 * collection. Use for columns declared with `collection: true` in a ViewDefinition.
 *
 * [variables] are accessible in the expression via `%name` syntax. Returns an empty list on
 * evaluation error.
 */
fun FhirPathEngine.evalList(
  focus: Any?,
  path: String,
  variables: Map<String, Any?> = emptyMap(),
): List<EvalResult> =
  runCatching {
      evaluateExpression(expression = path, base = focus, variables = variables).map {
        EvalResult(it)
      }
    }
    .getOrElse { emptyList() }
