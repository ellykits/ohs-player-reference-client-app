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
package dev.ohs.player.reference.app.feature.group.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ohs.player.generated.state.GroupListState
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.player.library.layout.VerticalListRenderer
import dev.ohs.player.library.scaffold.ListScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(onGroupClick: (String) -> Unit, onRegisterHousehold: () -> Unit) {
  val viewModel: GroupListViewModel = viewModel { GroupListViewModel() }
  val groups by viewModel.groups.collectAsStateWithLifecycle()
  var searchQuery by remember { mutableStateOf("") }

  if (groups == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  val filteredGroups =
    if (searchQuery.isBlank()) groups!!
    else groups!!.filter { it.groupName?.contains(searchQuery, ignoreCase = true) == true }

  Box(modifier = Modifier.fillMaxSize()) {
    ListScaffold<GroupListState>(
      items = filteredGroups,
      onItemClick = { onGroupClick(it.groupId ?: "") },
      key = { it.groupId ?: it.hashCode().toString() },
    ) {
      component(ViewTypeCS.GroupCard)
      layout(VerticalListRenderer.VIEW_TYPE)
      topBar {
        Column {
          TopAppBar(
            title = { Text("Households") },
            colors =
              TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
              ),
          )
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Search households…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
          )
        }
      }
      emptyState {
        if (searchQuery.isBlank()) Text("No households")
        else Text("No households match \"$searchQuery\"")
      }
    }

    FloatingActionButton(
      onClick = onRegisterHousehold,
      modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
      Icon(Icons.Default.Add, contentDescription = "Register Household")
    }
  }
}
