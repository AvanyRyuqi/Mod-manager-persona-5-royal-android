package app.compendium.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.compendium.android.AppViewModel
import app.compendium.android.Bridge
import app.compendium.android.InstallUi
import app.compendium.android.Storage
import app.compendium.android.ints
import app.compendium.android.objects
import app.compendium.android.strings

@Composable
fun App(vm: AppViewModel) {
    val sdRoot by vm.sdRoot.collectAsStateWithLifecycle()
    val access by vm.accessGranted.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (sdRoot == null || !access) SetupScreen(vm) else MainScreen(vm)
    }
}

// ---------------------------------------------------------------------------------- setup

@Composable
fun SetupScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val root by vm.sdRoot.collectAsStateWithLifecycle()
    val access by vm.accessGranted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.message.collect { snackbar.showSnackbar(it) } }

    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshAccess() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val path = Storage.treeUriToPath(uri)
            if (path == null) {
                LaunchedEffectMessage.post(vm, "Pick a folder on the phone's storage or an SD card.")
            } else {
                vm.chooseRoot(path)
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.padding(pad).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Compendium", style = MaterialTheme.typography.headlineLarge)
            Text("A Persona 5 Royal mod manager. It downloads mods, builds the game's PATCH1.CPK and writes it where the game " +
                "(or an emulator) reads it, in the same folder layout as a Switch SD card: atmosphere/contents/<title id>/romfs/…")
            Text("1. Allow file access", style = MaterialTheme.typography.titleMedium)
            Text("Compendium works with plain folders, so it needs \"All files access\" to write into the folder you pick.")
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.startActivity(Storage.accessSettingsIntent(context))
                else legacyPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }) { Text(if (Storage.hasAccess(context)) "File access allowed" else "Allow file access") }
            Text("2. Choose the \"SD card\" folder", style = MaterialTheme.typography.titleMedium)
            Text("Pick the folder your emulator uses as its Switch SD card (its \"sdmc\" folder), or any folder for now and point " +
                "the emulator at it later. Mods go in <folder>/atmosphere/contents/01005CA01580E000/.")
            Button(onClick = { picker.launch(null) }) { Text("Choose a folder") }
            OutlinedButton(onClick = { vm.chooseRoot(Storage.defaultRoot()) }) { Text("Use ${Storage.defaultRoot()}") }
            OutlinedButton(onClick = { vm.chooseRoot(Storage.privateRoot(context)) }) { Text("Use an app-only folder (no permission needed)") }
            if (root != null && !access) {
                Text("Folder: $root\nFile access isn't allowed yet.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Posts a message from a place that isn't a coroutine (a picker callback). */
object LaunchedEffectMessage {
    fun post(vm: AppViewModel, text: String) {
        vm.message.tryEmit(text)
    }
}

// ---------------------------------------------------------------------------------- main

@Composable
fun MainScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val initError by vm.initError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.message.collect { snackbar.showSnackbar(it) } }

    BackHandler(enabled = detail != null) { vm.detail.value = null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (detail == null) {
                NavigationBar {
                    val items = listOf(
                        Triple("Browse", Icons.Filled.Explore, 0),
                        Triple("My mods", Icons.Filled.Inventory2, 1),
                        Triple("Add", Icons.Filled.AddCircle, 2),
                        Triple("Settings", Icons.Filled.Settings, 3),
                    )
                    for ((label, icon, index) in items) {
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label) })
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                initError != null -> {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Couldn't open the mod library", style = MaterialTheme.typography.titleLarge)
                        Text(initError ?: "")
                        Button(onClick = { vm.sdRoot.value?.let { vm.chooseRoot(it) } }) { Text("Try again") }
                        OutlinedButton(onClick = { vm.sdRoot.value = null }) { Text("Choose another folder") }
                    }
                }
                detail != null -> DetailScreen(vm, detail!!)
                snapshot == null -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                }
                else -> when (tab) {
                    0 -> BrowseScreen(vm)
                    1 -> MyModsScreen(vm)
                    2 -> AddScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
    InstallHost(vm)
    BusyHost(vm)
}

// ---------------------------------------------------------------------------------- dialogs

@Composable
private fun ProgressBody(task: String, fallback: String) {
    val all by Bridge.progress.collectAsState()
    val p = all[task]
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(p?.text ?: fallback)
        if (p != null && p.fraction in 0f..1f) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun BusyHost(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val b = busy ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(b.title) },
        text = { ProgressBody(b.task, "Please wait. Don't close the app until this finishes.") },
        confirmButton = {},
    )
}

@Composable
fun InstallHost(vm: AppViewModel) {
    val state by vm.install.collectAsStateWithLifecycle()
    when (val s = state) {
        null -> {}
        is InstallUi.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s.title) },
            text = { ProgressBody(s.task, "Working…") },
            confirmButton = {
                if (s.cancellable) TextButton(onClick = { vm.cancelTask(s.task) }) { Text("Cancel") }
            },
        )
        is InstallUi.PickVariants -> {
            val variants = s.prep.optJSONArray("variants")?.objects() ?: emptyList()
            val checked = remember(s) {
                mutableStateListOf<Boolean>().apply {
                    val pre = s.prep.optJSONArray("selected").ints()
                    variants.indices.forEach { add(it in pre) }
                }
            }
            val token = s.prep.optString("token")
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Choose options") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("This file holds several versions or add-ons. Where two of them change the same file, the one lower in the list wins.")
                        variants.forEachIndexed { i, v ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                                Column(Modifier.width(220.dp)) {
                                    Text(v.optString("name").ifEmpty { "Main files" })
                                    Text("${v.optString("format")} · ${v.optInt("files")} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        for (w in s.prep.optJSONArray("warnings").strings()) Text("⚠ $w", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val chosen = checked.indices.filter { checked[it] }
                        if (chosen.isEmpty()) LaunchedEffectMessage.post(vm, "Pick at least one option")
                        else vm.confirmVariants(s.details, s.file, token, chosen)
                    }) { Text("Install selected") }
                },
                dismissButton = { TextButton(onClick = { vm.cancelPrepared(token) }) { Text("Cancel") } },
            )
        }
        is InstallUi.Ask -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s.title) },
            text = { Text(s.body) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    for (o in s.options) TextButton(onClick = o.onClick) { Text(o.label) }
                }
            },
        )
        is InstallUi.Result -> AlertDialog(
            onDismissRequest = { vm.dismissInstall() },
            title = { Text(s.title, color = if (s.error) MaterialTheme.colorScheme.error else OkGreen) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(s.body) } },
            confirmButton = { TextButton(onClick = { vm.dismissInstall() }) { Text("OK") } },
        )
    }
}

fun openUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
