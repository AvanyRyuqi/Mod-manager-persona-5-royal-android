package app.compendium.android

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

/** A mod in a GameBanana list. */
class ModSummary(val j: JSONObject) {
    val id: Long get() = j.optLong("id")
    val sourceKey: String get() = j.optString("sourceKey")
    val name: String get() = j.optString("name")
    val author: String get() = j.optString("author")
    val category: String get() = j.optString("category")
    val thumbnailUrl: String get() = j.optString("thumbnailUrl")
    val profileUrl: String get() = j.optString("profileUrl")
    val likes: Long get() = j.optLong("likes")
    val views: Long get() = j.optLong("views")
}

class ModFile(val j: JSONObject) {
    val id: Long get() = j.optLong("id")
    val filename: String get() = j.optString("filename")
    val size: Long get() = j.optLong("size")
    val description: String get() = j.optString("description")
    val version: String get() = j.optString("version")
    val note: String get() = j.optString("note")
    val noteKind: String get() = j.optString("noteKind")
    val avClean: Boolean get() = j.optBoolean("avClean", true)
}

/** A mod with its files; what the install flow works from. */
class ModDetails(val j: JSONObject) {
    val summary = ModSummary(j.optJSONObject("summary") ?: JSONObject())
    val version: String get() = j.optString("version")
    val descriptionHtml: String get() = j.optString("descriptionHtml")
    val downloads: Long get() = j.optLong("downloads")
    val screenshots: List<String> get() = j.optJSONArray("screenshotUrls").strings()
    val files: List<ModFile> = (j.optJSONArray("files") ?: JSONArray()).objects().map { ModFile(it) }
    val recommended: Int get() = j.optInt("recommended")
}

class Mod(val j: JSONObject) {
    val id: String get() = j.optString("id")
    val name: String get() = j.optString("name")
    val author: String get() = j.optString("author")
    val version: String get() = j.optString("version")
    val sourceFile: String get() = j.optString("sourceFile")
    val format: String get() = j.optString("format")
    val pcMod: Boolean get() = j.optBoolean("pcMod")
    val enabled: Boolean get() = j.optBoolean("enabled")
    val notes: List<String> get() = j.optJSONArray("notes").strings()
    val variants: List<String> get() = j.optJSONArray("variants").strings()
    val fileCount: Int get() = j.optInt("fileCount")
}

class Conflict(val j: JSONObject) {
    val path: String get() = j.optString("path")
    val modIds: List<String> get() = j.optJSONArray("modIds").strings()
}

class InboxFile(val j: JSONObject) {
    val name: String get() = j.optString("name")
    val path: String get() = j.optString("path")
    val size: Long get() = j.optLong("size")
}

/** The library as the native engine last reported it. */
class Snapshot(val j: JSONObject) {
    val mods: List<Mod> = (j.optJSONArray("mods") ?: JSONArray()).objects().map { Mod(it) }
    val conflicts: List<Conflict> = (j.optJSONArray("conflicts") ?: JSONArray()).objects().map { Conflict(it) }
    val buildTarget: String get() = j.optJSONObject("build")?.optString("target") ?: ""
    val buildFiles: Int get() = j.optJSONObject("build")?.optInt("files") ?: 0
    val buildBytes: Long get() = j.optJSONObject("build")?.optLong("bytes") ?: 0
    val missingOriginals: Boolean get() = j.optJSONObject("build")?.optBoolean("missingOriginals") ?: false
    val p5rcbtMode: Boolean get() = j.optString("mode") == "p5rcbt"
    val unmanaged: String get() = j.optString("unmanaged")
    val haveOriginals: Boolean get() = j.optBoolean("haveOriginals")
    val p5rcbtInstalled: Boolean get() = j.optBoolean("p5rcbtInstalled")
    val inbox: List<InboxFile> = (j.optJSONArray("inbox") ?: JSONArray()).objects().map { InboxFile(it) }
    val smm: List<Pair<String, String>> =
        (j.optJSONArray("smm") ?: JSONArray()).objects().map { it.optString("name") to it.optString("dir") }
    val installedKeys: Set<String> get() = j.optJSONArray("installedKeys").strings().toSet()
    val freeSpace: Long get() = j.optLong("freeSpace")
    val sdRoot: String get() = j.optString("sdRoot")

    /** How many files of this mod a mod higher in the load order covers up. */
    fun overriddenFiles(id: String): Int = conflicts.count { it.modIds.contains(id) && it.modIds.firstOrNull() != id }
    fun overridesFiles(id: String): Int = conflicts.count { it.modIds.firstOrNull() == id }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "size unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1e3)
    else -> n.toString()
}
