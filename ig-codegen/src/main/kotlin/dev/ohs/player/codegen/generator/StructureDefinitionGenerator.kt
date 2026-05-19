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
import dev.ohs.player.codegen.model.fhir.StructureDefinition
import dev.ohs.player.codegen.writeFormattedTo
import java.io.File

/**
 * Generates a `@Serializable` Kotlin class from a FHIR logical [StructureDefinition].
 *
 * ### Inheritance model
 * The FHIR parent chain is reflected in actual Kotlin inheritance:
 * - A class that has generated children is emitted as `open class` with `open val` on its own
 *   fields so they can be overridden by subclasses.
 * - A leaf class (no generated children) is emitted as `data class`.
 * - Every class that has a generated parent declares `override val` for inherited fields and
 *   delegates them to the superclass constructor call.
 *
 * This means a `PatientCardConfig` IS-A `CardConfig` IS-A `ViewConfig` at the Kotlin type level.
 *
 * ### Serialization
 * Each generated class is independently `@Serializable`. Serialization always targets the concrete
 * type — polymorphic deserialization is not used — so the standard per-class serializer is
 * sufficient.
 */
class StructureDefinitionGenerator(
  private val basePackage: String,
  private val subPackage: String,
  private val outputDir: File,
  private val allDefs: Map<String, StructureDefinition>,
) {

  private val serializableAnnotation = ClassName("kotlinx.serialization", "Serializable")
  private val contextualAnnotation = ClassName("kotlinx.serialization", "Contextual")

  // Stop walking parent chain at the FHIR canonical base
  private val fhirBase = "http://hl7.org/fhir/StructureDefinition/Base"

  fun generate(sd: StructureDefinition) {
    val pkg = "$basePackage.$subPackage"
    val className = sd.name

    val kdoc =
      buildString {
          append("Generated from StructureDefinition/${sd.id}. Do not edit manually.\n")
          sd.title?.let { append("\n$it") }
          sd.description?.let { append(".\n$it") }
        }
        .trim()

    // Direct parent that will also be generated (null if parent is skipped or FHIR base)
    val parentId =
      sd.baseDefinition?.substringAfterLast('/')?.takeIf { it != "Base" && isGeneratedClass(it) }

    // Intermediate = at least one generated child extends this class
    val isIntermediate =
      allDefs.values.any { other ->
        other.id != sd.id &&
          other.kind == "logical" &&
          isGeneratedClass(other.id) &&
          other.baseDefinition?.substringAfterLast('/') == sd.id
      }

    // Field names declared directly in sd's own differential (depth-1, non-root)
    val ownFieldNames =
      sd.differential
        ?.element
        ?.filter { elem ->
          elem.path.count { it == '.' } == 1 && elem.path.startsWith("${sd.name}.")
        }
        ?.map { it.path.substringAfterLast('.') }
        ?.toSet() ?: emptySet()

    val allElements = collectInheritedElements(sd)
    val fieldElements = allElements.filter { it.path != sd.name }
    val (flat, backbone) = partitionElements(fieldElements)

    val classBuilder = TypeSpec.classBuilder(className).addKdoc(kdoc)

    if (isIntermediate) {
      // Intermediate open classes are never serialized directly — only their concrete
      // subclasses are. Omitting @Serializable avoids "duplicate serial name" errors
      // when the leaf data class redeclares parent fields with `override`.
      classBuilder.addModifiers(KModifier.OPEN)
    } else {
      classBuilder.addModifiers(KModifier.DATA)
      classBuilder.addAnnotation(serializableAnnotation)
    }

    val constructorBuilder = FunSpec.constructorBuilder()

    flat.forEach { elem ->
      val fieldName = elem.path.substringAfterLast('.')
      val (typeName, defaultExpr, needsContextual) = resolveFieldType(elem)

      val isInherited = parentId != null && fieldName !in ownFieldNames

      val paramBuilder = ParameterSpec.builder(fieldName, typeName)
      defaultExpr?.let { paramBuilder.defaultValue(it) }

      val propBuilder = PropertySpec.builder(fieldName, typeName).initializer(fieldName)
      // Inherited fields are overrides; own fields on an open class are open.
      when {
        isInherited -> propBuilder.addModifiers(KModifier.OVERRIDE)
        isIntermediate -> propBuilder.addModifiers(KModifier.OPEN)
      }
      elem.short?.let { propBuilder.addKdoc(it) }
      if (needsContextual) propBuilder.addAnnotation(contextualAnnotation)

      constructorBuilder.addParameter(paramBuilder.build())
      classBuilder.addProperty(propBuilder.build())
    }

    backbone.forEach { (groupName, members) ->
      val nestedClass = buildNestedClass(groupName, members)
      val fieldName = groupName.replaceFirstChar { it.lowercase() }
      val listType = List::class.asClassName().parameterizedBy(ClassName(pkg, className, groupName))
      constructorBuilder.addParameter(
        ParameterSpec.builder(fieldName, listType).defaultValue("emptyList()").build()
      )
      classBuilder.addProperty(
        PropertySpec.builder(fieldName, listType).initializer(fieldName).build()
      )
      classBuilder.addType(nestedClass)
    }

    classBuilder.primaryConstructor(constructorBuilder.build())

    // Intermediate open classes are not @Serializable themselves, but their serializable
    // subclasses need a no-arg constructor in the parent to satisfy the kotlinx-serialization
    // plugin requirement ("parent must have exactly one constructor without parameters").
    if (isIntermediate) {
      val noArgArgs = flat.map { "null" } + backbone.keys.map { "emptyList()" }
      classBuilder.addFunction(
        FunSpec.constructorBuilder().callThisConstructor(*noArgArgs.toTypedArray()).build()
      )
    }

    if (parentId != null) {
      val parentDef = allDefs[parentId]!!
      // Collect the parent's full flat field list to build the super() call args
      val parentFlat =
        collectInheritedElements(parentDef)
          .filter { it.path != parentDef.name }
          .let { partitionElements(it) }
          .first
      val superArgs = parentFlat.joinToString(", ") { it.path.substringAfterLast('.') }
      classBuilder.superclass(ClassName(pkg, parentId))
      if (superArgs.isNotEmpty()) {
        classBuilder.addSuperclassConstructorParameter(superArgs)
      }
    }

    val fileSpec =
      FileSpec.builder(pkg, className)
        .addFileComment("Generated from StructureDefinition/${sd.id}. Do not edit manually.")
        .addType(classBuilder.build())
    if (!isIntermediate) {
      fileSpec.addImport("kotlinx.serialization", "Serializable")
    }
    fileSpec.build().writeFormattedTo(outputDir)
  }

  /** Returns true if [id] refers to a StructureDefinition that the task will generate. */
  private fun isGeneratedClass(id: String): Boolean =
    when (id) {
      "ViewJoinMap",
      "ViewDefinition" -> false
      else -> allDefs[id]?.kind == "logical"
    }

  /**
   * Walks the baseDefinition chain collecting all differential elements, stopping at [fhirBase].
   * The root element of each ancestor (path == ancestorName) is excluded.
   *
   * De-duplication uses the path relative to the defining class so BackboneElement member names do
   * not collide with top-level field names of the same short name.
   */
  private fun collectInheritedElements(sd: StructureDefinition): List<StructureDefinition.Element> {
    val chain = mutableListOf<StructureDefinition>()
    var current: StructureDefinition? = sd
    while (current != null) {
      chain.add(0, current) // ancestors first
      val base = current.baseDefinition ?: break
      if (base == fhirBase) break
      val parentId = base.substringAfterLast('/')
      current = allDefs[parentId]
    }

    val seen = mutableSetOf<String>()
    val result = mutableListOf<StructureDefinition.Element>()
    chain.reversed().forEach { def ->
      def.differential?.element?.forEach { elem ->
        val relativePath =
          if (elem.path.startsWith("${def.name}.")) {
            elem.path.removePrefix("${def.name}.")
          } else {
            elem.path
          }
        if (relativePath == def.name || seen.add(relativePath)) {
          result.add(elem)
        }
      }
    }
    return result.sortedBy { if (it.path == sd.name) 0 else 1 }
  }

  private fun partitionElements(
    elements: List<StructureDefinition.Element>
  ): Pair<List<StructureDefinition.Element>, Map<String, List<StructureDefinition.Element>>> {
    val flat =
      elements.filter { elem ->
        val depth = elem.path.count { it == '.' }
        depth == 1 && elem.type?.any { it.code == "BackboneElement" } != true
      }
    val backboneGroups = mutableMapOf<String, MutableList<StructureDefinition.Element>>()
    elements.forEach { elem ->
      val depth = elem.path.count { it == '.' }
      if (depth == 2) {
        val groupField = elem.path.split('.')[1]
        val groupName = groupField.replaceFirstChar { it.uppercase() }
        backboneGroups.getOrPut(groupName) { mutableListOf() }.add(elem)
      }
    }
    return flat to backboneGroups
  }

  private fun buildNestedClass(
    groupName: String,
    members: List<StructureDefinition.Element>,
  ): TypeSpec {
    val classBuilder =
      TypeSpec.classBuilder(groupName)
        .addModifiers(KModifier.DATA)
        .addAnnotation(serializableAnnotation)
        .addKdoc("Nested element within a BackboneElement group.")

    val ctor = FunSpec.constructorBuilder()
    members.forEach { elem ->
      val fieldName = elem.path.substringAfterLast('.')
      val (typeName, defaultExpr, needsContextual) = resolveFieldType(elem)
      val paramBuilder = ParameterSpec.builder(fieldName, typeName)
      defaultExpr?.let { paramBuilder.defaultValue(it) }
      val propBuilder = PropertySpec.builder(fieldName, typeName).initializer(fieldName)
      elem.short?.let { propBuilder.addKdoc(it) }
      if (needsContextual) propBuilder.addAnnotation(contextualAnnotation)
      ctor.addParameter(paramBuilder.build())
      classBuilder.addProperty(propBuilder.build())
    }
    return classBuilder.primaryConstructor(ctor.build()).build()
  }

  private fun resolveFieldType(
    elem: StructureDefinition.Element
  ): Triple<TypeName, String?, Boolean> {
    val max = elem.max ?: "1"
    val min = elem.min ?: 0
    val isList = max == "*"
    val isRequired = min >= 1 && max == "1"

    val typeCode = elem.type?.firstOrNull()?.code ?: "string"

    if (isList) {
      val (innerType, _, _) = resolveScalarType(typeCode)
      val listType = List::class.asClassName().parameterizedBy(innerType.copy(nullable = false))
      return Triple(listType, "emptyList()", false)
    }

    val (scalarType, _, needsContextual) = resolveScalarType(typeCode)
    return if (isRequired) {
      Triple(scalarType.copy(nullable = false), null, needsContextual)
    } else {
      Triple(scalarType.copy(nullable = true), "null", needsContextual)
    }
  }

  private fun resolveScalarType(typeCode: String): Triple<TypeName, String?, Boolean> {
    return when (typeCode) {
      "string",
      "code",
      "uri",
      "id",
      "markdown",
      "canonical",
      "url",
      "uuid",
      "oid" -> Triple(String::class.asTypeName(), null, false)
      "decimal" -> Triple(Float::class.asTypeName(), null, false)
      "integer",
      "positiveInt",
      "unsignedInt",
      "integer64" -> Triple(Int::class.asTypeName(), null, false)
      "boolean" -> Triple(Boolean::class.asTypeName(), null, false)
      "date" -> Triple(ClassName("dev.ohs.fhir.model.r4", "FhirDate"), null, true)
      "dateTime",
      "instant" -> Triple(ClassName("dev.ohs.fhir.model.r4", "FhirDateTime"), null, true)
      "time" -> Triple(ClassName("kotlinx.datetime", "LocalTime"), null, true)
      else -> Triple(String::class.asTypeName(), null, false)
    }
  }
}
