package dev.ohs.player.codegen.util

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import dev.ohs.player.codegen.model.ViewConfigDefinition
import dev.ohs.player.codegen.model.ViewDefinition

private val fhirDateClass = ClassName("dev.ohs.fhir.model.r4", "FhirDate")
private val fhirDateTimeClass = ClassName("dev.ohs.fhir.model.r4", "FhirDateTime")
private val bigDecimalClass = ClassName("com.ionspin.kotlin.bignum.decimal", "BigDecimal")

/** Collection columns become `List<T>` (non-null elements); scalar columns become `T?`. */
fun ViewDefinition.Column.fieldType(): TypeName {
  val scalar = scalarType(type)
  return if (collection) {
    List::class.asClassName().parameterizedBy(scalar.copy(nullable = false))
  } else {
    scalar.copy(nullable = true)
  }
}


/** Collection properties become `List<T>`; scalars become `T?`. */
fun fieldType(property: ViewConfigDefinition.Property): TypeName {
  val scalar = scalarType(property.type)
  return if (property.collection) {
    List::class.asClassName().parameterizedBy(scalar.copy(nullable = false))
  } else {
    scalar.copy(nullable = true)
  }
}

fun scalarType(fhirType: String?): TypeName =
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
