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

import dev.ohs.player.generated.extractor.GroupHeaderExtractor
import dev.ohs.player.generated.extractor.GroupListExtractor
import dev.ohs.player.generated.extractor.GroupMemberExtractor
import dev.ohs.player.generated.state.GroupListState
import dev.ohs.player.reference.app.FhirPathEngine.forR4 as engine
import dev.ohs.player.reference.app.data.datasource.groupListSearchResults
import dev.ohs.player.reference.app.data.datasource.groupProfileSearchResult
import dev.ohs.player.reference.app.feature.group.profile.GroupProfileUiState
import kotlinx.coroutines.withContext

object GroupRepository {

  suspend fun getGroups(): List<GroupListState> =
    withContext(extractorDispatcher) {
      groupListSearchResults().flatMap { GroupListExtractor.extract(engine, it) }
    }

  suspend fun getGroupProfile(groupId: String): GroupProfileUiState =
    withContext(extractorDispatcher) {
      val result = groupProfileSearchResult(groupId) ?: return@withContext GroupProfileUiState()
      GroupProfileUiState(
        groupHeader = GroupHeaderExtractor.extract(engine, result).firstOrNull(),
        members = GroupMemberExtractor.extract(engine, result),
      )
    }
}
