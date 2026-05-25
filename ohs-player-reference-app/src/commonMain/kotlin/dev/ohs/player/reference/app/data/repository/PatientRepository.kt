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
package dev.ohs.player.reference.app.data.repository

import dev.ohs.player.generated.extractor.AllergyReactionExtractor
import dev.ohs.player.generated.extractor.PatientAllergyExtractor
import dev.ohs.player.generated.extractor.PatientConditionExtractor
import dev.ohs.player.generated.extractor.PatientContactExtractor
import dev.ohs.player.generated.extractor.PatientImmunizationExtractor
import dev.ohs.player.generated.extractor.PatientMedicationExtractor
import dev.ohs.player.generated.extractor.PatientSummaryExtractor
import dev.ohs.player.generated.extractor.PatientTelecomExtractor
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.player.reference.app.FhirPathEngine.forR4 as engine
import dev.ohs.player.reference.app.data.datasource.allPatientIds
import dev.ohs.player.reference.app.data.datasource.patientProfileSearchResult
import dev.ohs.player.reference.app.data.datasource.patientSummarySearchResult
import dev.ohs.player.reference.app.feature.patient.profile.IpsProfileUiState
import kotlinx.coroutines.withContext

object PatientRepository {

  suspend fun getPatients(): List<PatientSummaryState> =
    withContext(extractorDispatcher) {
      allPatientIds().mapNotNull { id ->
        patientSummarySearchResult(id)?.let {
          PatientSummaryExtractor.extract(engine, it).firstOrNull()
        }
      }
    }

  suspend fun getPatientProfile(patientId: String): IpsProfileUiState =
    withContext(extractorDispatcher) {
      val result = patientProfileSearchResult(patientId) ?: return@withContext IpsProfileUiState()
      IpsProfileUiState(
        patient = PatientSummaryExtractor.extract(engine, result).firstOrNull(),
        allergies = PatientAllergyExtractor.extract(engine, result),
        allergyReactions = AllergyReactionExtractor.extract(engine, result),
        medications = PatientMedicationExtractor.extract(engine, result),
        conditions = PatientConditionExtractor.extract(engine, result),
        immunizations = PatientImmunizationExtractor.extract(engine, result),
        contacts =
          PatientContactExtractor.extract(engine, result).filter {
            it.contactGivenName != null || it.contactFamilyName != null
          },
        telecoms =
          PatientTelecomExtractor.extract(engine, result).filter { it.telecomValue != null },
      )
    }
}
