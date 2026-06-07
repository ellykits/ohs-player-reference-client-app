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
import dev.ohs.player.codegen.model.ViewConfigDefinition
import dev.ohs.player.codegen.writeFormattedTo
import java.io.File

/**
 * Generates a `@Serializable` config data class from a [ViewConfigDefinition] Binary.
 *
 * The class name is the PascalCased view type plus `Config` (e.g. `PatientHeader` → `PatientHeaderConfig`),
 * and each declared property becomes a nullable field. The library deserializes the Binary's property
 * values into an instance of this class at runtime.
 */
class ConfigBinaryGenerator(basePackage: String, private val outputDir: File) {

  private val configPkg = "$basePackage.config"
  private val serializableClass = ClassName("kotlinx.serialization", "Serializable")

  fun generate(def: ViewConfigDefinition) {
    require(def.viewType.isNotBlank()) {
      "ConfigBinaryGenerator: ViewConfig Binary is missing a viewType."
    }
    val name = "${def.viewType.replaceFirstChar { it.uppercase() }}Config"

    val ctor = FunSpec.constructorBuilder()
    val cls = TypeSpec.classBuilder(name).addModifiers(KModifier.DATA).addAnnotation(serializableClass)

    def.property.forEach { property ->
      val type = fieldType(property)
      val default = if (property.collection) "emptyList()" else "null"
      ctor.addParameter(ParameterSpec.builder(property.name, type).defaultValue(default).build())
      cls.addProperty(PropertySpec.builder(property.name, type).initializer(property.name).build())
    }

    FileSpec.builder(configPkg, name)
      .addFileComment("Generated from ViewConfig Binary '${def.viewType}'. Do not edit manually.")
      .addType(cls.primaryConstructor(ctor.build()).build())
      .build()
      .writeFormattedTo(outputDir)
  }

  /** Collection properties become `List<T>`; scalars become `T?`. */
  private fun fieldType(property: ViewConfigDefinition.Property): TypeName {
    val scalar = scalarType(property.type)
    return if (property.collection) {
      List::class.asClassName().parameterizedBy(scalar.copy(nullable = false))
    } else {
      scalar.copy(nullable = true)
    }
  }

  private fun scalarType(fhirType: String?): TypeName =
    when (fhirType?.substringAfterLast('/')) {
      "boolean" -> Boolean::class.asTypeName()
      "decimal" -> Float::class.asTypeName()
      "integer",
      "positiveInt",
      "unsignedInt" -> Int::class.asTypeName()
      "integer64" -> Long::class.asTypeName()
      else -> String::class.asTypeName()
    }
}
