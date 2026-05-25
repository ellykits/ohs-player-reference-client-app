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
package dev.ohs.player.reference.app.data.datasource

import dev.ohs.fhir.model.r4.AllergyIntolerance
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.MedicationRequest
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.player.library.model.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import ohsplayerreferenceclientapp.ohs_player_reference_app.generated.resources.Res

internal object SampleDataStore {
  private val mutex = Mutex()
  private var initialized = false
  private val fhirJson = FhirR4Json()
  private val jsonParser = Json { ignoreUnknownKeys = true }

  val version = MutableStateFlow(0)

  val patients = OrderedMap<Patient>()
  val groups = OrderedMap<Group>()
  val relatedPersons = OrderedMap<RelatedPerson>()

  val allergies = mutableMapOf<String, ArrayDeque<AllergyIntolerance>>()
  val medications = mutableMapOf<String, ArrayDeque<MedicationRequest>>()
  val conditions = mutableMapOf<String, ArrayDeque<Condition>>()
  val immunizations = mutableMapOf<String, ArrayDeque<Immunization>>()

  // groupId → patient UUIDs registered after initial household creation.
  val groupMemberships = mutableMapOf<String, ArrayDeque<String>>()

  suspend fun ensureInit() {
    if (initialized) return
    mutex.withLock {
      if (initialized) return
      val json = Res.readBytes("files/SampleResourcesBundle.json").decodeToString()
      val bundle = fhirJson.decodeFromString(json) as Bundle
      bundle.entry.forEach { entry ->
        when (val res = entry.resource) {
          is Patient -> patients[res.id ?: return@forEach] = res
          is Group -> groups[res.id ?: return@forEach] = res
          is RelatedPerson -> relatedPersons[res.id ?: return@forEach] = res
          is AllergyIntolerance -> {
            val ref = patientId(res.patient.reference?.value)
            ref?.let { allergies.getOrPut(it) { ArrayDeque() }.addLast(res) }
          }
          is MedicationRequest -> {
            val ref = patientId(res.subject.reference?.value)
            ref?.let { medications.getOrPut(it) { ArrayDeque() }.addLast(res) }
          }
          is Condition -> {
            val ref = patientId(res.subject.reference?.value)
            ref?.let { conditions.getOrPut(it) { ArrayDeque() }.addLast(res) }
          }
          is Immunization -> {
            val ref = patientId(res.patient.reference?.value)
            ref?.let { immunizations.getOrPut(it) { ArrayDeque() }.addLast(res) }
          }
          else -> Unit
        }
      }
      initialized = true
    }
  }

  fun appendExtractedBundle(bundle: Bundle, contextGroupId: String?, contextPatientId: String?) {
    // If the bundle carries a RelatedPerson alongside the Patient (household registration), inject
    // Patient.link.seealso so GroupMemberExtractor can join Patient → RelatedPerson.
    val rpUuidsInBundle =
      bundle.entry
        .filter { it.resource is RelatedPerson }
        .mapNotNull { it.fullUrl?.value?.removePrefix("urn:uuid:") }

    bundle.entry.forEach { entry ->
      val res = entry.resource ?: return@forEach
      val fullUrl = entry.fullUrl?.value ?: return@forEach
      val uuid = fullUrl.removePrefix("urn:uuid:")
      when (res) {
        is Patient -> {
          val rpId = rpUuidsInBundle.firstOrNull()
          val patient =
            if (rpId != null) injectRelatedPersonLink(res, uuid, rpId) as Patient
            else withId(res, uuid) as Patient
          patients.prepend(uuid, patient)
        }
        is Group -> groups.prepend(uuid, withId(res, uuid) as Group)
        is RelatedPerson -> relatedPersons.prepend(uuid, withId(res, uuid) as RelatedPerson)
        is Condition -> {
          if (contextPatientId != null) {
            val r = injectPatientRef(res, uuid, contextPatientId, "subject") as Condition
            conditions.getOrPut(contextPatientId) { ArrayDeque() }.addFirst(r)
          }
        }
        is Immunization -> {
          if (contextPatientId != null) {
            val r = injectPatientRef(res, uuid, contextPatientId, "patient") as Immunization
            immunizations.getOrPut(contextPatientId) { ArrayDeque() }.addFirst(r)
          }
        }
        is AllergyIntolerance -> {
          if (contextPatientId != null) {
            val r = injectPatientRef(res, uuid, contextPatientId, "patient") as AllergyIntolerance
            allergies.getOrPut(contextPatientId) { ArrayDeque() }.addFirst(r)
          }
        }
        is MedicationRequest -> {
          if (contextPatientId != null) {
            val r = injectPatientRef(res, uuid, contextPatientId, "subject") as MedicationRequest
            medications.getOrPut(contextPatientId) { ArrayDeque() }.addFirst(r)
          }
        }
        else -> Unit
      }
    }

    if (contextGroupId != null) {
      val patientUuids =
        bundle.entry
          .filter { it.resource is Patient }
          .mapNotNull { it.fullUrl?.value?.removePrefix("urn:uuid:") }
      if (patientUuids.isNotEmpty()) {
        val deque = groupMemberships.getOrPut(contextGroupId) { ArrayDeque() }
        for (uuid in patientUuids.asReversed()) deque.addFirst(uuid)
      }
    }

    version.value++
  }

  private fun withId(resource: Resource, id: String): Resource {
    val serialized = fhirJson.encodeToString(resource)
    val jsonObj = jsonParser.parseToJsonElement(serialized).jsonObject
    val merged =
      JsonObject(
        buildMap {
          put("id", JsonPrimitive(id))
          putAll(jsonObj)
        }
      )
    return fhirJson.decodeFromString(merged.toString())
  }

  private fun injectRelatedPersonLink(resource: Resource, id: String, rpId: String): Resource {
    val serialized = fhirJson.encodeToString(resource)
    val jsonObj = jsonParser.parseToJsonElement(serialized).jsonObject
    val linkJson =
      jsonParser.parseToJsonElement(
        """[{"type":"seealso","other":{"reference":"RelatedPerson/$rpId"}}]"""
      )
    val merged =
      JsonObject(
        buildMap {
          put("id", JsonPrimitive(id))
          putAll(jsonObj)
          put("link", linkJson)
        }
      )
    return fhirJson.decodeFromString(merged.toString())
  }

  private fun injectPatientRef(
    resource: Resource,
    id: String,
    patientId: String,
    field: String,
  ): Resource {
    val serialized = fhirJson.encodeToString(resource)
    val jsonObj = jsonParser.parseToJsonElement(serialized).jsonObject
    val refJson = jsonParser.parseToJsonElement("""{"reference":"Patient/$patientId"}""")
    val merged =
      JsonObject(
        buildMap {
          put("id", JsonPrimitive(id))
          putAll(jsonObj)
          put(field, refJson)
        }
      )
    return fhirJson.decodeFromString(merged.toString())
  }

  private fun patientId(reference: String?) = reference?.removePrefix("Patient/")?.ifEmpty { null }
}

/** Returns all patient IDs in insertion order — used by the patient list screen. */
suspend fun allPatientIds(): List<String> {
  SampleDataStore.ensureInit()
  return SampleDataStore.patients.keys
}

/**
 * Patient list: root = Patient only. No clinical resources needed — the list card shows summary
 * fields available directly on the Patient resource.
 */
suspend fun patientSummarySearchResult(patientId: String): SearchResult<Resource>? {
  SampleDataStore.ensureInit()
  val patient = SampleDataStore.patients[patientId] ?: return null
  return SearchResult(resource = patient)
}

/**
 * Patient profile: root = Patient, all clinical resources in revIncluded. Mirrors a real `GET
 * /Patient/{id}/$everything` response. All section extractors run against this single result.
 */
suspend fun patientProfileSearchResult(patientId: String): SearchResult<Resource>? {
  SampleDataStore.ensureInit()
  val patient = SampleDataStore.patients[patientId] ?: return null
  val revIncluded = buildMap {
    SampleDataStore.allergies[patientId]?.takeIf { it.isNotEmpty() }
      ?.let { put("AllergyIntolerance" to "patient", it) }
    SampleDataStore.medications[patientId]?.takeIf { it.isNotEmpty() }
      ?.let { put("MedicationRequest" to "subject", it) }
    SampleDataStore.conditions[patientId]?.takeIf { it.isNotEmpty() }
      ?.let { put("Condition" to "subject", it) }
    SampleDataStore.immunizations[patientId]?.takeIf { it.isNotEmpty() }
      ?.let { put("Immunization" to "patient", it) }
  }
  return SearchResult(
    resource = patient,
    included = mapOf("patient" to listOf(patient)),
    revIncluded = revIncluded.ifEmpty { null },
  )
}

/**
 * Group list: root = Group only. Member count is derived from `Group.member.size` on the resource
 * itself — no additional includes needed.
 */
suspend fun groupListSearchResults(): List<SearchResult<Resource>> {
  SampleDataStore.ensureInit()
  return SampleDataStore.groups.values.map { group -> SearchResult(resource = group) }
}

/**
 * Group profile: root = Group, member Patients in included, RelatedPersons in revIncluded. Mirrors
 * a real `GET /Group/{id}?_include=Group:member&_revinclude=RelatedPerson:patient` response. Both
 * GroupHeaderExtractor and GroupMemberExtractor run against this single result.
 */
suspend fun groupProfileSearchResult(groupId: String): SearchResult<Resource>? {
  SampleDataStore.ensureInit()
  val group = SampleDataStore.groups[groupId] ?: return null

  val memberIdsFromGroup =
    group.member.mapNotNull { member ->
      val ref = member.entity.reference?.value ?: return@mapNotNull null
      when {
        ref.startsWith("Patient/") -> ref.removePrefix("Patient/")
        ref.startsWith("urn:uuid:") -> ref.removePrefix("urn:uuid:")
        else -> null
      }
    }
  val memberIdsFromMemberships = SampleDataStore.groupMemberships[groupId] ?: emptyList()
  val allMemberIds = (memberIdsFromMemberships + memberIdsFromGroup).distinct()

  val memberPatients = allMemberIds.mapNotNull { SampleDataStore.patients[it] }

  val relatedPersons =
    allMemberIds.mapNotNull { memberId ->
      SampleDataStore.relatedPersons.values.firstOrNull { rp ->
        val rpRef = rp.patient.reference?.value ?: return@firstOrNull false
        rpRef == "Patient/$memberId" || rpRef == "urn:uuid:$memberId"
      }
    }

  return SearchResult(
    resource = group,
    included = mapOf("member" to memberPatients),
    revIncluded =
      if (relatedPersons.isNotEmpty()) mapOf(("RelatedPerson" to "patient") to relatedPersons)
      else null,
  )
}
