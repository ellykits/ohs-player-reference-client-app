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
import dev.ohs.player.codegen.model.ViewJoinMap
import dev.ohs.player.codegen.model.fhir.ViewDefinition
import dev.ohs.player.codegen.writeFormattedTo
import java.io.File

/**
 * Generates a `@Serializable` state data class and a `StateExtractor` subclass from a
 * [ViewJoinMap], merging columns from all participating [ViewDefinition]s.
 *
 * ### Output packages
 * - State class → `<basePackage>.state`
 * - Extractor → `<basePackage>.extractor`
 *
 * ### Naming
 * The ViewJoinMap `name` is PascalCased and suffixed:
 * - `"patientAllergy"` → `PatientAllergyState` + `PatientAllergyExtractor`
 */
class BinaryGenerator(
  basePackage: String,
  private val outputDir: File,
  private val viewDefs: Map<String, ViewDefinition>,
) {
  private val statePkg = "$basePackage.state"
  private val extractorPkg = "$basePackage.extractor"

  private val scopeClass = ClassName("dev.ohs.player.library.annotation", "Scope")
  private val joinMapClass = ClassName("dev.ohs.player.library.annotation", "JoinMap")
  private val fhirPathAnnotation = ClassName("dev.ohs.player.library.annotation", "FhirPath")
  private val serializableClass = ClassName("kotlinx.serialization", "Serializable")
  private val contextualClass = ClassName("kotlinx.serialization", "Contextual")
  private val stateExtractorClass = ClassName("dev.ohs.player.library.extractor", "StateExtractor")
  private val engineClass = ClassName("dev.ohs.fhir.fhirpath", "FhirPathEngine")
  private val resourceClass = ClassName("dev.ohs.fhir.model.r4", "Resource")
  private val fhirDateClass = ClassName("dev.ohs.fhir.model.r4", "FhirDate")
  private val fhirDateTimeClass = ClassName("dev.ohs.fhir.model.r4", "FhirDateTime")
  private val bigDecimalClass = ClassName("com.ionspin.kotlin.bignum.decimal", "BigDecimal")
  private val searchResultClass = ClassName("dev.ohs.player.library.model", "SearchResult")

  /**
   * Tracks a single column together with its origin context.
   *
   * [src] identifies the resource that contributes the column (`"pivot"` or `"joinN"`). The three
   * expansion fields are mutually exclusive and null for plain anchor columns:
   * - [forEach]: the FHIRPath expression of the `forEach` block this column belongs to.
   * - [forEachOrNull]: the FHIRPath expression of the `forEachOrNull` block.
   * - [unionBlockIdx]: the zero-based index of the inner `unionAll` sub-block.
   */
  private data class ColSrc(
    val col: ViewDefinition.Column,
    val resourceType: String,
    val src: String,
    val forEach: String? = null,
    val forEachOrNull: String? = null,
    val unionBlockIdx: Int? = null,
  )

  fun generate(map: ViewJoinMap) {
    val pivotView =
      viewDefs[map.view]
        ?: error("BinaryGenerator: ViewDefinition '${map.view}' not found for map '${map.name}'")

    val columns = mutableListOf<ColSrc>()

    // Classify pivot columns by select-block type
    pivotView.select.forEach { block ->
      when {
        block.forEach != null ->
          block.column.forEach {
            columns += ColSrc(it, pivotView.resource, "pivot", forEach = block.forEach)
          }
        block.forEachOrNull != null ->
          block.column.forEach {
            columns += ColSrc(it, pivotView.resource, "pivot", forEachOrNull = block.forEachOrNull)
          }
        block.unionAll.isNotEmpty() ->
          block.unionAll.forEachIndexed { idx, inner ->
            inner.column.forEach {
              columns += ColSrc(it, pivotView.resource, "pivot", unionBlockIdx = idx)
            }
          }
        else -> block.column.forEach { columns += ColSrc(it, pivotView.resource, "pivot") }
      }
    }

    map.joins.forEachIndexed { i, join ->
      val jv =
        viewDefs[join.view]
          ?: error(
            "BinaryGenerator: ViewDefinition '${join.view}' not found in join[$i] of '${map.name}'"
          )
      jv.allColumns().forEach { columns += ColSrc(it, jv.resource, "join$i") }
    }

    // Merge constants from pivot view and all join views (later definitions win on name collision)
    val constants =
      buildMap<String, Any?> {
        pivotView.constant.forEach { put(it.name, it.value) }
        map.joins.forEach { join ->
          viewDefs[join.view]?.constant?.forEach { put(it.name, it.value) }
        }
      }

    val pascal = map.name.replaceFirstChar { it.uppercase() }
    generateState("${pascal}State", map, columns)
    generateExtractor("${pascal}Extractor", "${pascal}State", pivotView, map, columns, constants)
  }

  private fun generateState(name: String, map: ViewJoinMap, cols: List<ColSrc>) {
    val ctor = FunSpec.constructorBuilder()
    val cls =
      TypeSpec.classBuilder(name)
        .addModifiers(KModifier.DATA)
        .addAnnotation(AnnotationSpec.builder(serializableClass).build())
        .addAnnotation(buildJoinMapAnnotation(map))

    // Deduplicate by name: unionAll blocks repeat the same column names across sub-blocks
    cols
      .distinctBy { it.col.name }
      .forEach { src ->
        val type = stateFieldType(src.col)
        val default = if (src.col.collection) "emptyList()" else "null"
        ctor.addParameter(ParameterSpec.builder(src.col.name, type).defaultValue(default).build())

        val propBuilder =
          PropertySpec.builder(src.col.name, type)
            .initializer(src.col.name)
            .addAnnotation(
              AnnotationSpec.builder(fhirPathAnnotation)
                .addMember("expression = %S", src.col.path)
                .addMember("resource = %S", src.resourceType)
                .build()
            )
        if (!src.col.collection && needsContextual(src.col.type)) {
          propBuilder.addAnnotation(AnnotationSpec.builder(contextualClass).build())
        }
        cls.addProperty(propBuilder.build())
      }

    cls.primaryConstructor(ctor.build())

    FileSpec.builder(statePkg, name)
      .addFileComment("Generated from ViewJoinMap '${map.name}'. Do not edit manually.")
      .addType(cls.build())
      .build()
      .writeFormattedTo(outputDir)
  }

  private fun generateExtractor(
    name: String,
    stateName: String,
    pivotView: ViewDefinition,
    map: ViewJoinMap,
    cols: List<ColSrc>,
    constants: Map<String, Any?>,
  ) {
    val stateClass = ClassName(statePkg, stateName)

    val fn =
      FunSpec.builder("extract")
        .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
        .addParameter("engine", engineClass)
        .addParameter("result", searchResultClass.parameterizedBy(resourceClass))
        .returns(List::class.asClassName().parameterizedBy(stateClass))
        .addCode(buildExtractBody(stateClass, pivotView, map, cols, constants))
        .build()

    val needsEvalList =
      cols.any { it.col.collection } ||
        cols.any { it.forEach != null } ||
        cols.any { it.forEachOrNull != null }

    FileSpec.builder(extractorPkg, name)
      .addFileComment("Generated from ViewJoinMap '${map.name}'. Do not edit manually.")
      .addImport("dev.ohs.player.library.extractor", "eval")
      .apply { if (needsEvalList) addImport("dev.ohs.player.library.extractor", "evalList") }
      .addType(
        TypeSpec.objectBuilder(name)
          .superclass(stateExtractorClass.parameterizedBy(stateClass))
          .addFunction(fn)
          .build()
      )
      .build()
      .writeFormattedTo(outputDir)
  }

  private fun buildExtractBody(
    stateClass: ClassName,
    pivotView: ViewDefinition,
    map: ViewJoinMap,
    cols: List<ColSrc>,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    // 1. Resolve the pivot list
    emitPivots(map)
    add("\n")

    // 2. Emit constants map if the participating views declare any
    if (constants.isNotEmpty()) {
      emitConstants(constants)
      add("\n")
    }

    // 3. Pre-build join indexes/values before the pivot loop.
    //    Dynamic join (matchKey): Map<String, Resource> keyed by id — O(1) lookups in the loop.
    //    Static join (no matchKey): resolve the single resource and evaluate its columns once.
    map.joins.forEachIndexed { i, join ->
      add(emitJoinPreBuild(join, cols.filter { it.src == "join$i" }, constants))
      add("\n")
    }

    // 4. Iterate pivots and collect state instances
    val pv = pivotVar(map)
    beginControlFlow("return buildList")
    beginControlFlow("for (%N in pivots)", pv)

    // 5. where clause guards — skip the pivot entirely if any clause evaluates to false
    if (pivotView.where.isNotEmpty()) {
      pivotView.where.forEach { clause ->
        val constSuffix = if (constants.isEmpty()) "" else ", constants"
        addStatement(
          "if (engine.eval(%N, %S%L).bool != true) continue",
          pv,
          clause.path,
          constSuffix,
        )
      }
      add("\n")
    }

    // 6. Extract anchor columns from the current pivot resource (flat, non-expanding blocks)
    val anchorPivotCols =
      cols.filter {
        it.src == "pivot" &&
          it.forEach == null &&
          it.forEachOrNull == null &&
          it.unionBlockIdx == null
      }
    anchorPivotCols.forEach { s -> add(emitColumnEval(s.col, pv, constants)) }

    // 7. Resolve dynamic join resources (static join columns already in scope from step 3)
    map.joins.forEachIndexed { i, join ->
      add(emitJoinInLoop(join, cols.filter { it.src == "join$i" }, constants))
    }

    // Deduplicated state columns used in constructor calls
    val stateCols = cols.distinctBy { it.col.name }

    // Classify expanding blocks
    val forEachGroups =
      cols.filter { it.src == "pivot" && it.forEach != null }.groupBy { it.forEach!! }
    val forEachOrNullGroups =
      cols.filter { it.src == "pivot" && it.forEachOrNull != null }.groupBy { it.forEachOrNull!! }
    val unionCols = cols.filter { it.src == "pivot" && it.unionBlockIdx != null }

    val hasExpansion =
      forEachGroups.isNotEmpty() || forEachOrNullGroups.isNotEmpty() || unionCols.isNotEmpty()

    if (hasExpansion) {
      // 8a. forEach blocks — one add() per element
      forEachGroups.forEach { (path, blockCols) ->
        add("\n")
        add(emitForEachBlock(stateClass, path, blockCols, stateCols, pv, constants))
      }

      // 8b. forEachOrNull blocks — one add() per element, or one null row if empty
      forEachOrNullGroups.forEach { (path, blockCols) ->
        add("\n")
        add(emitForEachOrNullBlock(stateClass, path, blockCols, stateCols, pv, constants))
      }

      // 8c. unionAll blocks — one run{} + add() per sub-block
      if (unionCols.isNotEmpty()) {
        val maxIdx = unionCols.maxOf { it.unionBlockIdx!! }
        for (i in 0..maxIdx) {
          add("\n")
          add(
            emitUnionAllBlock(
              stateClass,
              i,
              unionCols.filter { it.unionBlockIdx == i },
              stateCols,
              pv,
              constants,
            )
          )
        }
      }
    } else {
      // 8. Simple case — one add() per pivot
      add("\n")
      add(buildConstructorAdd(stateClass, stateCols))
    }

    endControlFlow()
    endControlFlow()
  }

  /** Emits `val pivots = ...` resolving the pivot resource list from [SearchResult]. */
  private fun CodeBlock.Builder.emitPivots(map: ViewJoinMap) {
    when {
      map.from == "root" -> addStatement("val pivots = listOf(result.resource)")
      map.from == "included" && map.searchParam != null ->
        addStatement("val pivots = result.included?.get(%S) ?: return emptyList()", map.searchParam)
      map.from == "included" ->
        addStatement("val pivots = result.included?.values?.flatten() ?: return emptyList()")
      map.from == "revIncluded" && map.searchParam != null ->
        addStatement(
          "val pivots = result.revIncluded?.get(%S to %S) ?: return emptyList()",
          map.resource,
          map.searchParam,
        )
      map.from == "revIncluded" ->
        addStatement(
          "val pivots = result.revIncluded?.entries?.filter·{ it.key.first == %S }?.flatMap·{ it.value } ?: return emptyList()",
          map.resource,
        )
      else -> addStatement("val pivots = emptyList<%T>()", resourceClass)
    }
  }

  /** Emits `val constants: Map<String, Any?> = mapOf(...)` for ViewDefinition constants. */
  private fun CodeBlock.Builder.emitConstants(constants: Map<String, Any?>) {
    add(
      "val constants: %T = mapOf(\n",
      Map::class.asClassName()
        .parameterizedBy(String::class.asTypeName(), ANY.copy(nullable = true)),
    )
    indent()
    constants.entries.forEachIndexed { i, (k, v) ->
      val comma = if (i < constants.size - 1) "," else ""
      when (v) {
        is String -> add("%S to %S$comma\n", k, v)
        is Boolean -> add("%S to %L$comma\n", k, v)
        is Number -> add("%S to %L$comma\n", k, v)
        else -> add("%S to null$comma\n", k)
      }
    }
    unindent()
    add(")\n")
  }

  /**
   * Emits join pre-build code that runs BEFORE the pivot loop.
   *
   * **Dynamic join** (`matchKey` present): builds a single `Map<String, Resource>` keyed by the
   * join resource's id. Each join resource is visited exactly once; in-loop lookups are O(1).
   *
   * **Static join** (no `matchKey`): resolves the single joined resource once and evaluates all its
   * column expressions. The resulting `val` bindings remain in scope for the entire loop body.
   */
  private fun emitJoinPreBuild(
    join: ViewJoinMap.Join,
    joinCols: List<ColSrc>,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    if (join.matchKey != null) {
      val loopVar = join.resource.lowercaseFirst()
      val mapType =
        Map::class.asClassName().parameterizedBy(String::class.asTypeName(), resourceClass)
      beginControlFlow("val %N: %T = buildMap", joinIndexVar(join), mapType)
      beginControlFlow("for (%N in %L)", loopVar, dynamicJoinPool(join))
      addStatement("val key = engine.eval(%N, %S).str ?: continue", loopVar, "id")
      addStatement("put(key, %N)", loopVar)
      endControlFlow()
      endControlFlow()
    } else {
      val sv = joinStaticVar(join)
      addStatement("val %N = %L?.firstOrNull()", sv, staticJoinPool(join))
      joinCols.forEach { s -> add(emitStaticJoinColumnEval(s.col, sv, constants)) }
    }
  }

  /**
   * Emits join resolution code that runs INSIDE the pivot loop.
   *
   * **Dynamic join**: looks up the joined resource by the pivot's `matchKey` value, then evaluates
   * each column. **Static join**: columns are already bound above the loop — nothing is emitted.
   */
  private fun emitJoinInLoop(
    join: ViewJoinMap.Join,
    joinCols: List<ColSrc>,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    if (join.matchKey != null) {
      val rv = joinResolvedVar(join)
      addStatement("val %N = %N?.let·{ %N[it] }", rv, join.matchKey, joinIndexVar(join))
      joinCols.forEach { s -> add(emitDynamicJoinColumnEval(s.col, rv, constants)) }
    }
  }

  /** Emits `val colName = engine.eval(resource, path[, constants]).accessor` for a pivot column. */
  private fun emitColumnEval(
    col: ViewDefinition.Column,
    resourceVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    val evalCall = evalCall(resourceVar, col.path, constants)
    if (col.collection) {
      addStatement("val %N = %L.%L", col.name, evalCall, collectionMapper(col.type))
    } else {
      addStatement("val %N = %L.%L", col.name, evalCall, getter(col.type))
    }
  }

  /** Emits `val colName = staticJoinVar?.let { engine.eval(it, path).accessor } ?: default`. */
  private fun emitStaticJoinColumnEval(
    col: ViewDefinition.Column,
    joinVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    if (col.collection) {
      val evalCall = evalListCall("it", col.path, constants)
      addStatement(
        "val %N = %N?.let·{ %L.%L } ?: emptyList()",
        col.name,
        joinVar,
        evalCall,
        collectionMapper(col.type),
      )
    } else {
      val evalCall = evalCall("it", col.path, constants)
      addStatement("val %N = %N?.let·{ %L.%L }", col.name, joinVar, evalCall, getter(col.type))
    }
  }

  /** Emits `val colName = joinedResource?.let { engine.eval(it, path).accessor } ?: default`. */
  private fun emitDynamicJoinColumnEval(
    col: ViewDefinition.Column,
    resolvedVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    if (col.collection) {
      val evalCall = evalListCall("it", col.path, constants)
      addStatement(
        "val %N = %N?.let·{ %L.%L } ?: emptyList()",
        col.name,
        resolvedVar,
        evalCall,
        collectionMapper(col.type),
      )
    } else {
      val evalCall = evalCall("it", col.path, constants)
      addStatement("val %N = %N?.let·{ %L.%L }", col.name, resolvedVar, evalCall, getter(col.type))
    }
  }

  /**
   * Emits a `forEach` block: `evalList` on the pivot → inner loop → column evals on each element →
   * `add()`. Produces one state instance per element.
   */
  private fun emitForEachBlock(
    stateClass: ClassName,
    path: String,
    blockCols: List<ColSrc>,
    stateCols: List<ColSrc>,
    pivotVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    val elemVar = forEachElementVar(path)
    val listVar = "${elemVar}List"
    val constSuffix = if (constants.isEmpty()) "" else ", constants"
    addStatement("val %N = engine.evalList(%N, %S%L)", listVar, pivotVar, path, constSuffix)
    beginControlFlow("for (%N in %N)", elemVar, listVar)
    blockCols.forEach { src -> add(emitForEachColumnEval(src.col, elemVar, constants)) }
    add("\n")
    add(buildConstructorAdd(stateClass, stateCols))
    endControlFlow()
  }

  /**
   * Emits a `forEachOrNull` block: like [emitForEachBlock] but falls back to `listOf(null)` when
   * the collection is empty, ensuring the pivot always contributes at least one output row with
   * null values for all expansion columns.
   */
  private fun emitForEachOrNullBlock(
    stateClass: ClassName,
    path: String,
    blockCols: List<ColSrc>,
    stateCols: List<ColSrc>,
    pivotVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    val elemVar = forEachElementVar(path)
    val listVar = "${elemVar}List"
    val constSuffix = if (constants.isEmpty()) "" else ", constants"
    addStatement(
      "val %N = engine.evalList(%N, %S%L).ifEmpty·{ listOf(null) }",
      listVar,
      pivotVar,
      path,
      constSuffix,
    )
    beginControlFlow("for (%N in %N)", elemVar, listVar)
    blockCols.forEach { src -> add(emitForEachOrNullColumnEval(src.col, elemVar, constants)) }
    add("\n")
    add(buildConstructorAdd(stateClass, stateCols))
    endControlFlow()
  }

  /**
   * Emits a single `unionAll` sub-block wrapped in `run { }` so local variable names don't clash
   * between sub-blocks. Anchor and join columns are captured from the enclosing scope.
   */
  private fun emitUnionAllBlock(
    stateClass: ClassName,
    blockIdx: Int,
    blockCols: List<ColSrc>,
    stateCols: List<ColSrc>,
    pivotVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    addStatement("// unionAll block $blockIdx")
    beginControlFlow("run")
    blockCols.forEach { src -> add(emitColumnEval(src.col, pivotVar, constants)) }
    add("\n")
    add(buildConstructorAdd(stateClass, stateCols))
    endControlFlow()
  }

  /**
   * Emits column eval for a [forEach] block element. Column expressions are evaluated against
   * `elemVar.raw` (the raw FHIR object at the forEach path).
   */
  private fun emitForEachColumnEval(
    col: ViewDefinition.Column,
    elemVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    val constSuffix = if (constants.isEmpty()) "" else ", constants"
    if (col.collection) {
      addStatement(
        "val %N = %N.raw?.let·{ engine.evalList(it, %S%L).%L } ?: emptyList()",
        col.name,
        elemVar,
        col.path,
        constSuffix,
        collectionMapper(col.type),
      )
    } else {
      addStatement(
        "val %N = %N.raw?.let·{ engine.eval(it, %S%L).%L }",
        col.name,
        elemVar,
        col.path,
        constSuffix,
        getter(col.type),
      )
    }
  }

  /**
   * Emits column eval for a [forEachOrNull] block element. When the element is null (the fallback
   * row) all column values are null; when non-null they are evaluated against `elemVar?.raw`.
   */
  private fun emitForEachOrNullColumnEval(
    col: ViewDefinition.Column,
    elemVar: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    val constSuffix = if (constants.isEmpty()) "" else ", constants"
    if (col.collection) {
      addStatement(
        "val %N = %N?.raw?.let·{ engine.evalList(it, %S%L).%L } ?: emptyList()",
        col.name,
        elemVar,
        col.path,
        constSuffix,
        collectionMapper(col.type),
      )
    } else {
      addStatement(
        "val %N = %N?.raw?.let·{ engine.eval(it, %S%L).%L }",
        col.name,
        elemVar,
        col.path,
        constSuffix,
        getter(col.type),
      )
    }
  }

  /**
   * Returns the `engine.eval(resource, path[, constants])` call expression. Constants are omitted
   * when the map is empty to keep noise out of constant-free extractors.
   */
  private fun evalCall(resourceVar: String, path: String, constants: Map<String, Any?>): CodeBlock =
    buildCodeBlock {
      if (constants.isEmpty()) {
        add("engine.eval(%N, %S)", resourceVar, path)
      } else {
        add("engine.eval(%N, %S, constants)", resourceVar, path)
      }
    }

  private fun evalListCall(
    resourceVar: String,
    path: String,
    constants: Map<String, Any?>,
  ): CodeBlock = buildCodeBlock {
    if (constants.isEmpty()) {
      add("engine.evalList(%N, %S)", resourceVar, path)
    } else {
      add("engine.evalList(%N, %S, constants)", resourceVar, path)
    }
  }

  /** Emits `add(StateClass(field = field, ...))` with each argument on its own line. */
  private fun buildConstructorAdd(stateClass: ClassName, cols: List<ColSrc>): CodeBlock =
    buildCodeBlock {
      add("add(\n")
      indent()
      add("%T(\n", stateClass)
      indent()
      cols.forEach { s -> add("%N = %N,\n", s.col.name, s.col.name) }
      unindent()
      add("),\n")
      unindent()
      add(")\n")
    }

  /** Pool expression for a dynamic join's for-loop iteration (always non-null). */
  private fun dynamicJoinPool(join: ViewJoinMap.Join): CodeBlock = buildCodeBlock {
    when {
      join.from == "included" && join.searchParam != null ->
        add("result.included?.get(%S) ?: emptyList()", join.searchParam)
      join.from == "revIncluded" && join.searchParam != null ->
        add("result.revIncluded?.get(%S·to·%S) ?: emptyList()", join.resource, join.searchParam)
      join.from == "revIncluded" ->
        add(
          "result.revIncluded?.entries?.filter·{ it.key.first == %S }?.flatMap·{ it.value } ?: emptyList()",
          join.resource,
        )
      else -> add("emptyList<%T>()", resourceClass)
    }
  }

  /** Pool expression for resolving a static join's single resource (nullable). */
  private fun staticJoinPool(join: ViewJoinMap.Join): CodeBlock = buildCodeBlock {
    when {
      join.from == "included" && join.searchParam != null ->
        add("result.included?.get(%S)", join.searchParam)
      join.from == "revIncluded" && join.searchParam != null ->
        add("result.revIncluded?.get(%S·to·%S)", join.resource, join.searchParam)
      join.from == "revIncluded" ->
        add(
          "result.revIncluded?.entries?.filter·{ it.key.first == %S }?.flatMap·{ it.value }",
          join.resource,
        )
      else -> add("null")
    }
  }

  private fun buildJoinMapAnnotation(map: ViewJoinMap): AnnotationSpec {
    val joinsCode = buildCodeBlock {
      add("[")
      map.joins.forEachIndexed { i, j ->
        if (i > 0) add(", ")
        add("%L", scopeCode(j.resource, j.from, j.searchParam, j.matchKey))
      }
      add("]")
    }
    return AnnotationSpec.builder(joinMapClass)
      .addMember("pivot = %L", scopeCode(map.resource, map.from, map.searchParam, null))
      .addMember("joins = %L", joinsCode)
      .build()
  }

  private fun scopeCode(
    resource: String,
    from: String,
    searchParam: String?,
    matchKey: String?,
  ): CodeBlock = buildCodeBlock {
    add("%T(resource = %S, from = %S", scopeClass, resource, from)
    if (!searchParam.isNullOrEmpty()) add(", searchParam = %S", searchParam)
    if (!matchKey.isNullOrEmpty()) add(", matchKey = %S", matchKey)
    add(")")
  }

  private fun pivotVar(map: ViewJoinMap) = map.resource.lowercaseFirst()

  private fun joinIndexVar(join: ViewJoinMap.Join) = "${join.resource.lowercaseFirst()}ByKey"

  private fun joinResolvedVar(join: ViewJoinMap.Join) =
    "joined${join.resource.replaceFirstChar { it.uppercase() }}"

  private fun joinStaticVar(join: ViewJoinMap.Join) = join.resource.lowercaseFirst()

  /** Derives a loop variable name from the last path segment of a `forEach` expression. */
  private fun forEachElementVar(path: String) = path.substringAfterLast('.').lowercaseFirst()

  private fun String.lowercaseFirst() = replaceFirstChar { it.lowercase() }

  /**
   * Kotlin type for a state field.
   * - Collection fields → `List<T>` (non-nullable, elements non-nullable)
   * - Scalar fields → `T?` (nullable)
   */
  private fun stateFieldType(col: ViewDefinition.Column): TypeName {
    val scalar = scalarType(col.type)
    return if (col.collection) {
      List::class.asClassName().parameterizedBy(scalar.copy(nullable = false))
    } else {
      scalar.copy(nullable = true)
    }
  }

  private fun scalarType(fhirUri: String?): TypeName =
    when (fhirUri?.substringAfterLast('/')) {
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

  /** True for types that require `@Contextual` for kotlinx-serialization to resolve them. */
  private fun needsContextual(fhirUri: String?): Boolean =
    when (fhirUri?.substringAfterLast('/')) {
      "decimal",
      "date",
      "dateTime",
      "instant" -> true
      else -> false
    }

  /**
   * Accessor property name on [EvalResult] for a scalar column (e.g. `"str"`, `"bool"`). Used in
   * `engine.eval(resource, path).accessor`.
   */
  private fun getter(fhirUri: String?): String =
    when (fhirUri?.substringAfterLast('/')) {
      "boolean" -> "bool"
      "decimal" -> "decimal"
      "integer",
      "positiveInt",
      "unsignedInt" -> "int"
      "integer64" -> "long"
      "date" -> "date"
      "dateTime",
      "instant" -> "dateTime"
      else -> "str"
    }

  /**
   * Terminal expression to map `List<EvalResult>` → `List<T>` for a collection column. Used as
   * `engine.evalList(resource, path).mapper`. Date/time collection columns stay as `List<String>`
   * to avoid `@Contextual` on list elements.
   */
  private fun collectionMapper(fhirUri: String?): String =
    when (fhirUri?.substringAfterLast('/')) {
      "boolean" -> "mapNotNull { it.bool }"
      "decimal" -> "mapNotNull { it.decimal }"
      "integer",
      "positiveInt",
      "unsignedInt" -> "mapNotNull { it.int }"
      "integer64" -> "mapNotNull { it.long }"
      else -> "mapNotNull { it.str }"
    }
}
