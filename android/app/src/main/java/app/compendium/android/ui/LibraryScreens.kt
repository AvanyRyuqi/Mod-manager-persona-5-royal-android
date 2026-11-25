package app.compendium.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.compendium.android.AppViewModel
import app.compendium.android.BuildConfig
import app.compendium.android.Engine
import app.compendium.android.Mod
import app.compendium.android.Snapshot
import app.compendium.android.Storage
import app.compendium.android.formatSize
import app.compendium.android.obj
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------------------------- My mods

@Composable
fun MyModsScreen(vm: AppViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val s = snap ?: return
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Mod?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BuildCard(s) }
        if (s.unmanaged.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mods from somewhere else", fontWeight = FontWeight.SemiBold)
                    Text("There's already a ${File(s.unmanaged).name} here that Compendium didn't build. Adopt it to turn it off and on from here; until then it's replaced the next time mods are built.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.importUnmanaged() }) { Text("Adopt it as a mod") }
                }
            }
        }
        if (s.mods.isEmpty()) item {
            Text("No mods installed yet. Find some in Browse, or add a file in Add.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        } else item {
            Text("Load order: the mod at the top wins where two change the same file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(s.mods, key = { it.id }) { m ->
            val open = expanded == m.id
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.clickable { expanded = if (open) null else m.id }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOf(m.author, m.format, if (m.pcMod) "PC mod, adapted" else "", "${m.fileCount} files").filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val over = s.overriddenFiles(m.id)
                            if (over > 0 && m.enabled) Text("⚠ $over of its files are covered by a mod above it", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
                        }
                        Switch(checked = m.enabled, onCheckedChange = { vm.setEnabled(m, it) })
                    }
                    if (open) {
                        if (m.variants.isNotEmpty()) Text("Options: ${m.variants.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        if (m.version.isNotEmpty()) Text("Version ${m.version} · from ${m.sourceFile}", style = MaterialTheme.typography.bodySmall)
                        for (n in m.notes) Text("• $n", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { vm.move(m, +1) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Higher priority") }
                            IconButton(onClick = { vm.move(m, -1) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Lower priority") }
                            IconButton(onClick = { deleting = m }) { Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("Its files are taken out of the game's mod archive and deleted from the library. You can install it again later.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.remove(m) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BuildCard(s: Snapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val on = s.mods.count { it.enabled }
            Text("$on of ${s.mods.size} mods on", fontWeight = FontWeight.SemiBold)
            if (s.buildTarget.isEmpty()) {
                Text("Nothing built yet. Turn a mod on and the game's archive is made.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Built ${File(s.buildTarget).name}: ${s.buildFiles} files, ${formatSize(s.buildBytes)}", style = MaterialTheme.typography.bodySmall)
                Text(s.buildTarget, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.missingOriginals) Text("The game's own PATCH1.CPK isn't kept yet. Settings → The game's own files.", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        }
    }
}

// ---------------------------------------------------------------------------------- Add

@Composable
fun AddScreen(vm: AppViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val s = snap ?: return
    var link by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importUri(uri) }
    var deleting by remember { mutableStateOf<app.compendium.android.InboxFile?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("From a link", style = MaterialTheme.typography.titleMedium)
        Text("A GameBanana mod link or number, a GitHub project, or a direct link to a .zip, .7z, .rar or .cpk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true, label = { Text("Link or number") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.openLink(link) }, enabled = link.isNotBlank()) { Text("Look it up") }

        HorizontalDivider()
        Text("From this device", style = MaterialTheme.typography.titleMedium)
        Text("Pick a mod you downloaded (.zip, .7z, .rar or .cpk). It's copied into the inbox first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose a file") }

        HorizontalDivider()
        Text("Inbox", style = MaterialTheme.typography.titleMedium)
        Text("Files in ${s.sdRoot}/switch/Compendium/inbox", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.inbox.isEmpty()) Text("No files yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (f in s.inbox) {
            Row(Modifier.fillMaxWidth().clickable { vm.openLocal(f.path) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name)
                    Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.Delete, contentDescription = "Delete file") }
            }
        }
        if (s.smm.isNotEmpty()) {
            HorizontalDivider()
            Text("SimpleModManager mods in this folder", style = MaterialTheme.typography.titleMedium)
            for ((name, dir) in s.smm) {
                Row(Modifier.fillMaxWidth().clickable { vm.openLocal(dir, smm = true) }.padding(vertical = 6.dp)) { Text(name) }
            }
        }
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("The file is removed from the inbox. Mods already installed from it stay.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteInboxFile(f) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val s = snap ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf<String?>(null) }
    var p5Config by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(s.p5rcbtMode, s.p5rcbtInstalled) {
        p5Config = if (s.p5rcbtMode) runCatching { Engine.call("p5rcbtConfig") }.getOrNull() else null
    }
    val cpkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importGameCpks(uris) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Storage.treeUriToPath(uri)?.let { vm.chooseRoot(it) } ?: vm.message.tryEmit("Pick a folder on the phone's storage or an SD card.")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Storage folder", style = MaterialTheme.typography.titleMedium)
        Text(s.sdRoot, style = MaterialTheme.typography.bodyMedium)
        Text("${formatSize(s.freeSpace)} free. Mods are written to ${s.sdRoot}/${s.j.optString("contentsDir")}/romfs/…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Change folder") }

        HorizontalDivider()
        Text("How mods reach the game", style = MaterialTheme.typography.titleMedium)
        ModeRow("Build PATCH1.CPK (default)", "Works on any game version: LayeredFS or the emulator loads the archive Compendium builds.", !s.p5rcbtMode) { vm.setMode(false) }
        ModeRow("p5rcbt (console only)", "Builds p5r/bind/Compendium.cpk for the Skyline plugin p5rcbt (game version 1.0.2). Not used by emulators.", s.p5rcbtMode) { vm.setMode(true) }
        if (s.p5rcbtMode) {
            Text(if (s.p5rcbtInstalled) "p5rcbt is installed." else "p5rcbt isn't installed.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.installP5rcbt() }) { Text(if (s.p5rcbtInstalled) "Update p5rcbt" else "Install p5rcbt") }
            p5Config?.let { c ->
                for ((key, label) in listOf("uncapFramerate" to "60 FPS patch", "pcSettings" to "PC settings menu", "logging" to "Logging")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        Switch(checked = c.optBoolean(key), onCheckedChange = { on ->
                            scope.launch { p5Config = runCatching { Engine.call("p5rcbtConfig", obj(key to on)) }.getOrNull() }
                        })
                    }
                }
            }
        }

        HorizontalDivider()
        Text("The game's own files", style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.haveOriginals) "Kept: the game's own PATCH1.CPK is merged into the archive built for your mods."
            else "Not read yet. PATCH1.CPK from the game holds files that should stay in the archive built for your mods. Pick it (and, to help place mods that come without folders, the game's other .CPK files) from a dump of the game's romfs/CPK folder.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { cpkPicker.launch(arrayOf("*/*")) }) { Text("Pick the game's CPK files") }
        OutlinedButton(onClick = { vm.rebuild() }) { Text("Rebuild the mod archive") }

        HorizontalDivider()
        Text("Downloads and logs", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { vm.clearDownloads() }) { Text("Clear leftover downloads") }
        OutlinedButton(onClick = { scope.launch { log = vm.readLog() } }) { Text("View the log") }

        HorizontalDivider()
        Text("Compendium ${BuildConfig.APP_VERSION} for Android", style = MaterialTheme.typography.bodySmall)
        Text("Unofficial. No game files are included; mods belong to their authors. GPL-3.0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    log?.let { text ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text("Log") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text.ifEmpty { "Nothing logged yet." }, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share the log"))
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { log = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun ModeRow(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
