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
package dev.ohs.player.reference.app.feature.questionnaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.fhir.datacapture.extraction.QuestionnaireResponseExtractor
import dev.ohs.fhir.model.r4.FhirR4Json
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.player.reference.app.data.datasource.SampleDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ohsplayerreferenceclientapp.ohs_player_reference_app.generated.resources.Res

class QuestionnaireWrapperViewModel(private val formKey: String) : ViewModel() {
  private val fhirJson = FhirR4Json()
  private val _questionnaireJson = MutableStateFlow<String?>(null)
  val questionnaireJson: StateFlow<String?> = _questionnaireJson.asStateFlow()

  init {
    viewModelScope.launch {
      val fileName =
        when (formKey) {
          "household" -> "questionnaire_household_registration.json"
          "member" -> "questionnaire_member_registration.json"
          "clinical" -> "questionnaire_clinical_record.json"
          "immunization" -> "questionnaire_immunization.json"
          "allergy" -> "questionnaire_allergy_intolerance.json"
          "medication" -> "questionnaire_medication_request.json"
          else -> return@launch
        }
      _questionnaireJson.value = Res.readBytes("files/$fileName").decodeToString()
    }
  }

  suspend fun processSubmit(
    response: QuestionnaireResponse,
    contextGroupId: String?,
    contextPatientId: String?,
  ) =
    withContext(Dispatchers.Default) {
      val json = _questionnaireJson.value ?: return@withContext
      val questionnaire = fhirJson.decodeFromString(json) as Questionnaire
      val bundle = QuestionnaireResponseExtractor.extract(questionnaire, response)
      SampleDataStore.appendExtractedBundle(bundle, contextGroupId, contextPatientId)
    }
}
