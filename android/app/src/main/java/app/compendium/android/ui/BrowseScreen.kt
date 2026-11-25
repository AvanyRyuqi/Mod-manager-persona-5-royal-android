package app.compendium.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.compendium.android.AppViewModel
import app.compendium.android.ModSummary
import app.compendium.android.formatCount
import coil.compose.AsyncImage

private val sorts = listOf("Most downloaded", "Most liked", "Newest", "Recently updated")

@Composable
fun BrowseScreen(vm: AppViewModel) {
    val state by vm.browse.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val loadingDetail by vm.loadingDetail.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(state.query) }
    val listState = rememberLazyListState()
    val last by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(last, state.items.size) {
        if (state.items.isNotEmpty() && last >= state.items.size - 3) vm.loadBrowse(reset = false)
    }
    val installed = snapshot?.installedKeys ?: emptySet()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !state.pc, onClick = { vm.updateBrowse { copy(pc = false) } }, label = { Text("Switch mods") })
            FilterChip(selected = state.pc, onClick = { vm.updateBrowse { copy(pc = true) } }, label = { Text("PC mods (adapted)") })
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("Search mods") },
            trailingIcon = {
                IconButton(onClick = { vm.updateBrowse { copy(query = text.trim()) } }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.updateBrowse { copy(query = text.trim()) } }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            var sortOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text("Sort: ${sorts[state.sort]}") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    sorts.forEachIndexed { i, s -> DropdownMenuItem(text = { Text(s) }, onClick = { sortOpen = false; vm.updateBrowse { copy(sort = i) } }) }
                }
            }
            if (state.categories.isNotEmpty() && state.query.isBlank()) {
                var catOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { catOpen = true }) {
                        Text("Category: ${state.categories.firstOrNull { it.first == state.category }?.second ?: "All"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = catOpen, onDismissRequest = { catOpen = false }) {
                        DropdownMenuItem(text = { Text("All") }, onClick = { catOpen = false; vm.updateBrowse { copy(category = 0) } })
                        for ((id, name) in state.categories) DropdownMenuItem(text = { Text(name) }, onClick = { catOpen = false; vm.updateBrowse { copy(category = id) } })
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.items) { m -> ModRow(m, installed.contains(m.sourceKey)) { vm.openMod(m) } }
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator()
                            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { vm.loadBrowse(reset = state.items.isEmpty()) }) { Text("Try again") }
                            }
                            state.items.isEmpty() -> Text("No mods found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (loadingDetail) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun ModRow(m: ModSummary, installed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = m.thumbnailUrl.ifEmpty { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${m.author} · ${m.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("♥ ${formatCount(m.likes)}   👁 ${formatCount(m.views)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (installed) Icon(Icons.Filled.CheckCircle, contentDescription = "Installed", tint = OkGreen)
    }
}
