package app.compendium.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Compendium works on plain paths (the same code as on the Switch, where the folder is the SD card),
 * so it needs "All files access" to write into a folder the player picks, such as an emulator's
 * SD card folder.
 */
object Storage {
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** The system screen where the player allows file access for this app (Android 11+). */
    fun accessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun defaultRoot(): String = File(Environment.getExternalStorageDirectory(), "Compendium").path

    /** A folder only this app can see: works with no permission, but no emulator can read it. */
    fun privateRoot(context: Context): String =
        (context.getExternalFilesDir(null) ?: context.filesDir).path

    /** Turns a folder picked with the system picker into a path, when it is on shared or SD card storage. */
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
