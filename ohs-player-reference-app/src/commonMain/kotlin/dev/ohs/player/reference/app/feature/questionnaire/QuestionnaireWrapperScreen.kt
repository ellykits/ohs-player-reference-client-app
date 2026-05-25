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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ohs.fhir.datacapture.Questionnaire
import dev.ohs.fhir.datacapture.QuestionnaireConfig
import kotlinx.coroutines.launch

@Composable
fun QuestionnaireWrapperScreen(
  formKey: String,
  contextGroupId: String? = null,
  contextPatientId: String? = null,
  onBack: () -> Unit,
) {
  val viewModel = viewModel(key = formKey) { QuestionnaireWrapperViewModel(formKey) }
  val questionnaireJson by viewModel.questionnaireJson.collectAsState()
  val scope = rememberCoroutineScope()

  val json = questionnaireJson
  if (json != null) {
    Questionnaire(
      questionnaireJson = json,
      config = QuestionnaireConfig(showCancelButton = true),
      onSubmit = { getResponse ->
        scope.launch {
          viewModel.processSubmit(getResponse(), contextGroupId, contextPatientId)
          onBack()
        }
      },
      onCancel = onBack,
    )
  } else {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
  }
}
