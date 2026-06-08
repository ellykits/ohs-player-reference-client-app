package dev.ohs.player.codegen.model

import kotlinx.serialization.Serializable

/** Minimal model for deserializing a FHIR CodeSystem JSON artifact from the IG. */
@Serializable
data class CodeSystem(
  val resourceType: String,
  val id: String,
  val name: String,
  val title: String? = null,
  val description: String? = null,
  val concept: List<Concept> = emptyList(),
) {
  @Serializable
  data class Concept(val code: String, val display: String? = null)
}