package app.compendium.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class BrowseState(
    val pc: Boolean = false,
    val sort: Int = 0,
    val category: Long = 0,
    val query: String = "",
    val items: List<ModSummary> = emptyList(),
    val page: Int = 0,
    val isLast: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val categories: List<Pair<Long, String>> = emptyList(),
) {
    fun copy(
        pc: Boolean = this.pc,
        sort: Int = this.sort,
        category: Long = this.category,
        query: String = this.query,
        items: List<ModSummary> = this.items,
        page: Int = this.page,
        isLast: Boolean = this.isLast,
        loading: Boolean = this.loading,
        error: String? = this.error,
        categories: List<Pair<Long, String>> = this.categories,
    ) = BrowseState(pc, sort, category, query, items, page, isLast, loading, error, categories)
}

class AskOption(val label: String, val onClick: () -> Unit)

/** What the install dialog shows. */
sealed interface InstallUi {
    class Working(val title: String, val task: String, val cancellable: Boolean) : InstallUi
    class PickVariants(val details: ModDetails, val file: ModFile, val prep: JSONObject) : InstallUi
    class Ask(val title: String, val body: String, val options: List<AskOption>) : InstallUi
    class Result(val title: String, val body: String, val error: Boolean) : InstallUi
}

/** A library change in progress (turning a mod on, rebuilding): blocks the screen with its progress. */
class Busy(val title: String, val task: String)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("compendium", 0)

    val sdRoot = MutableStateFlow(prefs.getString("sdRoot", null))
    val snapshot = MutableStateFlow<Snapshot?>(null)
    val initError = MutableStateFlow<String?>(null)
    val message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val busy = MutableStateFlow<Busy?>(null)
    val install = MutableStateFlow<InstallUi?>(null)
    val detail = MutableStateFlow<ModDetails?>(null)
    val browse = MutableStateFlow(BrowseState())
    val loadingDetail = MutableStateFlow(false)
    val accessGranted = MutableStateFlow(Storage.hasAccess(app))

    private var browseJob: Job? = null
    private var counter = 0

    init {
        Bridge.userAgent = "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; Compendium/${BuildConfig.APP_VERSION})"
        refreshAccess()
        loadCategories()
        loadBrowse(reset = true)
    }

    private fun newTask(prefix: String) = "$prefix-${++counter}"

    fun refreshAccess() {
        accessGranted.value = Storage.hasAccess(app) || (sdRoot.value?.startsWith(Storage.privateRoot(app)) == true)
        val root = sdRoot.value
        if (root != null && accessGranted.value && snapshot.value == null && busy.value == null) initEngine(root)
    }

    // ------------------------------------------------------------------ storage folder

    fun chooseRoot(path: String) {
        prefs.edit().putString("sdRoot", path).apply()
        sdRoot.value = path
        snapshot.value = null
        initError.value = null
        accessGranted.value = Storage.hasAccess(app) || path.startsWith(Storage.privateRoot(app))
        if (accessGranted.value) initEngine(path)
    }

    private fun initEngine(root: String) {
        val task = newTask("init")
        busy.value = Busy("Opening your mod library…", task)
        viewModelScope.launch {
            try {
                snapshot.value = Snapshot(Engine.call("init", obj("sdRoot" to root, "version" to BuildConfig.APP_VERSION), task))
                initError.value = null
            } catch (e: EngineException) {
                initError.value = e.message
            } finally {
                busy.value = null
            }
        }
    }

    private suspend fun refreshSnapshot() {
        snapshot.value = Snapshot(Engine.call("snapshot"))
    }

    // ------------------------------------------------------------------ browsing

    fun updateBrowse(change: BrowseState.() -> BrowseState) {
        val before = browse.value
        browse.update { it.change() }
        val after = browse.value
        if (before.pc != after.pc) {
            browse.update { it.copy(category = 0, categories = emptyList()) }
            loadCategories()
        }
        loadBrowse(reset = true)
    }

    private fun loadCategories() {
        val pc = browse.value.pc
        viewModelScope.launch {
            try {
                val arr = Engine.callArray("gbCategories", obj("pc" to pc))
                val cats = arr.objects().map { it.optLong("id") to it.optString("name") }
                browse.update { if (it.pc == pc) it.copy(categories = cats) else it }
            } catch (_: EngineException) {
                // categories are optional
            }
        }
    }

    fun loadBrowse(reset: Boolean) {
        val s = browse.value
        if (!reset && (s.loading || s.isLast)) return
        val page = if (reset) 1 else s.page + 1
        browseJob?.cancel()
        browse.update { if (reset) it.copy(items = emptyList(), page = 0, isLast = false, loading = true, error = null) else it.copy(loading = true) }
        browseJob = viewModelScope.launch {
            try {
                val r = if (s.query.isBlank()) {
                    Engine.call("gbList", obj("pc" to s.pc, "sort" to s.sort, "page" to page, "category" to s.category))
                } else {
                    Engine.call("gbSearch", obj("pc" to s.pc, "query" to s.query, "page" to page))
                }
                val mods = (r.optJSONArray("mods")?.objects() ?: emptyList()).map { ModSummary(it) }
                browse.update { it.copy(items = if (reset) mods else it.items + mods, page = page, isLast = r.optBoolean("isLast", true), loading = false) }
            } catch (e: EngineException) {
                browse.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun openMod(summary: ModSummary) {
        if (loadingDetail.value) return
        loadingDetail.value = true
        viewModelScope.launch {
            try {
                detail.value = ModDetails(Engine.call("gbDetails", obj("id" to summary.id)))
            } catch (e: EngineException) {
                message.emit("Couldn't open the mod: ${e.message}")
            } finally {
                loadingDetail.value = false
            }
        }
    }

    fun openLink(link: String) {
        if (loadingDetail.value) return
        loadingDetail.value = true
        viewModelScope.launch {
            try {
                detail.value = ModDetails(Engine.call("resolveLink", obj("url" to link)))
            } catch (e: EngineException) {
                message.emit(e.message ?: "That link didn't work.")
            } finally {
                loadingDetail.value = false
            }
        }
    }

    fun openLocal(path: String, smm: Boolean = false) {
        viewModelScope.launch {
            try {
                detail.value = ModDetails(Engine.call("localFile", obj("path" to path, "smm" to smm)))
            } catch (e: EngineException) {
                message.emit(e.message ?: "Couldn't open that file.")
            }
        }
    }

    /** Copies a file the player picked on the device into the inbox, then opens it for installing. */
    fun importUri(uri: Uri) {
        val snap = snapshot.value ?: return
        val task = newTask("copy")
        busy.value = Busy("Copying the file…", task)
        viewModelScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    val name = displayName(uri) ?: "mod.zip"
                    val inbox = File(snap.sdRoot, "switch/Compendium/inbox").apply { mkdirs() }
                    val out = File(inbox, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                    app.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
                        ?: throw java.io.IOException("Couldn't read that file.")
                    out
                }
                refreshSnapshot()
                openLocal(dest.path)
            } catch (e: Exception) {
                message.emit("Couldn't copy the file: ${e.message}")
            } finally {
                busy.value = null
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ------------------------------------------------------------------ installing

    fun startInstall(d: ModDetails, f: ModFile) {
        val task = newTask("install")
        install.value = InstallUi.Working(d.summary.name, task, true)
        viewModelScope.launch {
            try {
                val prep = Engine.call("prepareInstall", obj("details" to d.j, "fileId" to f.id), task)
                val token = prep.optString("token")
                if (token.isEmpty()) {
                    install.value = InstallUi.Result("Can't install this file", prep.optString("problem").ifEmpty { "It doesn't hold anything for this game." }, true)
                    return@launch
                }
                val variants = prep.optJSONArray("variants")?.length() ?: 0
                if (variants <= 1) {
                    proceed(d, f, token, listOf(0))
                } else {
                    install.value = InstallUi.PickVariants(d, f, prep)
                }
            } catch (e: EngineException) {
                install.value = if (e.cancelled) null else InstallUi.Result("Install failed", e.message ?: "", true)
            }
        }
    }

    fun confirmVariants(d: ModDetails, f: ModFile, token: String, chosen: List<Int>) {
        viewModelScope.launch { proceed(d, f, token, chosen) }
    }

    fun cancelPrepared(token: String) {
        install.value = null
        viewModelScope.launch { runCatching { Engine.call("discardPrepared", obj("token" to token)) } }
    }

    fun cancelTask(task: String) {
        Engine.cancel(task)
        val cur = install.value
        if (cur is InstallUi.Working && cur.task == task) install.value = InstallUi.Working(cur.title, task, false)
    }

    private suspend fun proceed(d: ModDetails, f: ModFile, token: String, chosen: List<Int>) {
        install.value = InstallUi.Working(d.summary.name, newTask("check"), false)
        try {
            val c = Engine.call("installConflicts", obj("token" to token, "variants" to chosen))
            val same = c.optJSONArray("sameMod")?.objects() ?: emptyList()
            val clashing = c.optJSONArray("clashing").strings()
            val clashText = if (clashing.isEmpty()) "" else
                "It changes files that ${clashing.joinToString(", ")} also ${if (clashing.size == 1) "changes" else "change"}. The mod higher in My mods wins; new installs go to the top."
            when {
                same.isNotEmpty() -> {
                    val names = same.joinToString(", ") { it.optString("name") }
                    val ids = same.map { it.optString("id") }
                    install.value = InstallUi.Ask(
                        "Replace an earlier file?",
                        "You already have $names from this mod. Replace it with ${f.filename}?" + if (clashText.isEmpty()) "" else "\n\n$clashText",
                        listOf(
                            AskOption("Replace") { commit(d, f, token, chosen, ids) },
                            AskOption("Keep both") { commit(d, f, token, chosen, emptyList()) },
                            AskOption("Cancel") { cancelPrepared(token) },
                        ),
                    )
                }
                clashing.isNotEmpty() -> install.value = InstallUi.Ask(
                    "Before you install ${d.summary.name}",
                    clashText,
                    listOf(
                        AskOption("Install") { commit(d, f, token, chosen, emptyList()) },
                        AskOption("Cancel") { cancelPrepared(token) },
                    ),
                )
                else -> commit(d, f, token, chosen, emptyList())
            }
        } catch (e: EngineException) {
            install.value = InstallUi.Result("Install failed", e.message ?: "", true)
        }
    }

    private fun commit(d: ModDetails, f: ModFile, token: String, chosen: List<Int>, replace: List<String>) {
        val task = newTask("commit")
        install.value = InstallUi.Working(d.summary.name, task, false)
        viewModelScope.launch {
            try {
                val r = Engine.call("commitInstall", obj("token" to token, "variants" to chosen, "replace" to replace), task)
                refreshSnapshot()
                val build = r.optJSONObject("build")
                var body = "${d.summary.name} is installed and turned on."
                val target = build?.optString("target").orEmpty()
                if (target.isNotEmpty()) body += "\n\nThe game's mods were packed into ${File(target).name} (${build?.optInt("files")} files)."
                if (r.optBoolean("pcMod")) body += "\n\nThis is a PC mod, adapted for the Switch: if something looks wrong in the game, turn it off in My mods."
                for (n in r.optJSONArray("notes").strings()) body += "\n\n• $n"
                if (build?.optBoolean("missingOriginals") == true) {
                    body += "\n\nTip: give the app the game's own PATCH1.CPK once (Settings → The game's own files), so its files are kept in the archive built for your mods."
                }
                install.value = InstallUi.Result("Installed", body, false)
            } catch (e: EngineException) {
                install.value = InstallUi.Result("Install failed", e.message ?: "", true)
            }
        }
    }

    fun dismissInstall() {
        install.value = null
    }

    // ------------------------------------------------------------------ the library

    /** Runs a library change with a progress overlay, then takes the new state. */
    fun libraryOp(title: String, method: String, args: JSONObject = JSONObject(), onDone: (JSONObject) -> Unit = {}) {
        if (busy.value != null) return
        val task = newTask("lib")
        busy.value = Busy(title, task)
        viewModelScope.launch {
            try {
                val data = Engine.call(method, args, task)
                if (data.has("mods")) snapshot.value = Snapshot(data)
                onDone(data)
            } catch (e: EngineException) {
                message.emit(e.message ?: "That didn't work.")
                runCatching { refreshSnapshot() }
            } finally {
                busy.value = null
            }
        }
    }

    fun setEnabled(m: Mod, on: Boolean) =
        libraryOp(if (on) "Turning on ${m.name}…" else "Turning off ${m.name}…", "setEnabled", obj("id" to m.id, "enabled" to on))

    fun move(m: Mod, delta: Int) = libraryOp("Changing the load order…", "move", obj("id" to m.id, "delta" to delta))

    fun remove(m: Mod) = libraryOp("Removing ${m.name}…", "remove", obj("id" to m.id))

    fun setMode(p5rcbt: Boolean) = libraryOp("Switching how mods reach the game…", "setMode", obj("mode" to if (p5rcbt) "p5rcbt" else "patch1"))

    fun rebuild() = libraryOp("Building the game's mod archive…", "rebuild")

    fun importUnmanaged() = libraryOp("Adopting the mods already there…", "importUnmanaged", obj("name" to "Mods from before"))

    fun installP5rcbt() = libraryOp("Installing p5rcbt…", "installP5rcbt")

    /** The player picked the game's own CPK files (copied to cache first). */
    fun importGameCpks(uris: List<Uri>) {
        if (busy.value != null || uris.isEmpty()) return
        val task = newTask("cpk")
        busy.value = Busy("Reading the game's files…", task)
        viewModelScope.launch {
            val copies = mutableListOf<File>()
            try {
                withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "gamecpk").apply { deleteRecursively(); mkdirs() }
                    for (u in uris) {
                        val f = File(dir, (displayName(u) ?: "file.cpk").replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                        app.contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
                        copies += f
                    }
                }
                val data = Engine.call("importGameCpks", obj("paths" to copies.map { it.path }), task)
                snapshot.value = Snapshot(data)
                message.emit("Got the game's files.")
            } catch (e: Exception) {
                message.emit(e.message ?: "Couldn't read those files.")
            } finally {
                withContext(Dispatchers.IO) { copies.forEach { it.delete() } }
                busy.value = null
            }
        }
    }

    fun deleteInboxFile(f: InboxFile) {
        viewModelScope.launch {
            runCatching { Engine.call("deleteInboxFile", obj("path" to f.path)) }
            runCatching { refreshSnapshot() }
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            try {
                val r = Engine.call("clearDownloads")
                message.emit("Freed ${formatSize(r.optLong("bytes"))}.")
            } catch (e: EngineException) {
                message.emit(e.message ?: "")
            }
        }
    }

    suspend fun readLog(): String = try {
        Engine.call("readLog").optString("text")
    } catch (e: EngineException) {
        e.message ?: ""
    }
}
