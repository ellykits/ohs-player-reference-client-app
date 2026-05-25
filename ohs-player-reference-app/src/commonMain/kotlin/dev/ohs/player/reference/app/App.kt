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
package dev.ohs.player.reference.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import dev.ohs.player.library.registry.LocalViewRegistry
import dev.ohs.player.reference.app.feature.group.list.GroupListScreen
import dev.ohs.player.reference.app.feature.group.profile.GroupProfileScreen
import dev.ohs.player.reference.app.feature.patient.profile.IpsPatientProfileScreen
import dev.ohs.player.reference.app.feature.questionnaire.QuestionnaireWrapperScreen

private const val GROUP_LIST_ROUTE = "groupList"
private const val GROUP_PROFILE_ROUTE = "groupProfile"
private const val PATIENT_PROFILE_ROUTE = "patientProfile"
private const val GROUP_ID_ARG = "groupId"
private const val PATIENT_ID_ARG = "patientId"
private const val QUESTIONNAIRE_HOUSEHOLD_ROUTE = "questionnaire/household"
private const val QUESTIONNAIRE_MEMBER_ROUTE = "questionnaire/member"
private const val QUESTIONNAIRE_CLINICAL_ROUTE = "questionnaire/clinical"
private const val QUESTIONNAIRE_IMMUNIZATION_ROUTE = "questionnaire/immunization"
private const val QUESTIONNAIRE_ALLERGY_ROUTE = "questionnaire/allergy"
private const val QUESTIONNAIRE_MEDICATION_ROUTE = "questionnaire/medication"

@Composable
fun App() {
  val registry = remember { buildAppViewRegistry() }

  CompositionLocalProvider(LocalViewRegistry provides registry) {
    OhsPlayerTheme {
      val navController = rememberNavController()
      NavHost(navController = navController, startDestination = GROUP_LIST_ROUTE) {

        // Screen 1: Household list
        composable(GROUP_LIST_ROUTE) {
          GroupListScreen(
            onGroupClick = { id -> navController.navigate("$GROUP_PROFILE_ROUTE/$id") },
            onRegisterHousehold = { navController.navigate(QUESTIONNAIRE_HOUSEHOLD_ROUTE) },
          )
        }

        // Screen 2: Household profile (head + members)
        composable(
          route = "$GROUP_PROFILE_ROUTE/{$GROUP_ID_ARG}",
          arguments = listOf(navArgument(GROUP_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val groupId = back.arguments?.read { getString(GROUP_ID_ARG) }.orEmpty()
          GroupProfileScreen(
            groupId = groupId,
            onBack = { navController.popBackStack() },
            onMemberClick = { id -> navController.navigate("$PATIENT_PROFILE_ROUTE/$id") },
            onRegisterMember = { navController.navigate("$QUESTIONNAIRE_MEMBER_ROUTE/$groupId") },
          )
        }

        // Screen 3: Patient IPS summary
        composable(
          route = "$PATIENT_PROFILE_ROUTE/{$PATIENT_ID_ARG}",
          arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val patientId = back.arguments?.read { getString(PATIENT_ID_ARG) }.orEmpty()
          IpsPatientProfileScreen(
            patientId = patientId,
            onBack = { navController.popBackStack() },
            onNavigateToForm = { formKey ->
              navController.navigate("questionnaire/$formKey/$patientId")
            },
          )
        }

        // Questionnaire: Register household
        composable(QUESTIONNAIRE_HOUSEHOLD_ROUTE) {
          QuestionnaireWrapperScreen(
            formKey = "household",
            onBack = { navController.popBackStack() },
          )
        }

        // Questionnaire: Register household member
        composable(
          route = "$QUESTIONNAIRE_MEMBER_ROUTE/{$GROUP_ID_ARG}",
          arguments = listOf(navArgument(GROUP_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val groupId = back.arguments?.read { getString(GROUP_ID_ARG) }.orEmpty()
          QuestionnaireWrapperScreen(
            formKey = "member",
            contextGroupId = groupId,
            onBack = { navController.popBackStack() },
          )
        }

        // Questionnaire: Record condition / diagnosis
        composable(
          route = "$QUESTIONNAIRE_CLINICAL_ROUTE/{$PATIENT_ID_ARG}",
          arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val patientId = back.arguments?.read { getString(PATIENT_ID_ARG) }.orEmpty()
          QuestionnaireWrapperScreen(
            formKey = "clinical",
            contextPatientId = patientId,
            onBack = { navController.popBackStack() },
          )
        }

        // Questionnaire: Record immunization
        composable(
          route = "$QUESTIONNAIRE_IMMUNIZATION_ROUTE/{$PATIENT_ID_ARG}",
          arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val patientId = back.arguments?.read { getString(PATIENT_ID_ARG) }.orEmpty()
          QuestionnaireWrapperScreen(
            formKey = "immunization",
            contextPatientId = patientId,
            onBack = { navController.popBackStack() },
          )
        }

        // Questionnaire: Record allergy / intolerance
        composable(
          route = "$QUESTIONNAIRE_ALLERGY_ROUTE/{$PATIENT_ID_ARG}",
          arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val patientId = back.arguments?.read { getString(PATIENT_ID_ARG) }.orEmpty()
          QuestionnaireWrapperScreen(
            formKey = "allergy",
            contextPatientId = patientId,
            onBack = { navController.popBackStack() },
          )
        }

        // Questionnaire: Record medication request
        composable(
          route = "$QUESTIONNAIRE_MEDICATION_ROUTE/{$PATIENT_ID_ARG}",
          arguments = listOf(navArgument(PATIENT_ID_ARG) { type = NavType.StringType }),
        ) { back ->
          val patientId = back.arguments?.read { getString(PATIENT_ID_ARG) }.orEmpty()
          QuestionnaireWrapperScreen(
            formKey = "medication",
            contextPatientId = patientId,
            onBack = { navController.popBackStack() },
          )
        }
      }
    }
  }
}
