package app.compendium.android.ui

import android.text.Html
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.compendium.android.AppViewModel
import app.compendium.android.ModDetails
import app.compendium.android.ModFile
import app.compendium.android.formatCount
import app.compendium.android.formatSize
import coil.compose.AsyncImage

/** GameBanana's descriptions are HTML; show them as plain text. */
private fun plainText(html: String, max: Int = 2500): String {
    val t = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\n{3,}"), "\n\n").trim()
    return if (t.length > max) t.take(max).trimEnd() + "…" else t
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel, d: ModDetails) {
    val context = LocalContext.current
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val s = d.summary
    val installed = snapshot?.installedKeys?.contains(s.sourceKey) == true
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.name, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { vm.detail.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                if (s.profileUrl.isNotBlank()) IconButton(onClick = { openUrl(context, s.profileUrl) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open the mod's page")
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shots = d.screenshots.ifEmpty { listOfNotNull(s.thumbnailUrl.ifEmpty { null }) }
            if (shots.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shots) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.height(180.dp).width(300.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
            Text(s.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "by ${s.author}" + (if (s.category.isNotEmpty()) " · ${s.category}" else "") +
                    (if (d.version.isNotEmpty()) " · v${d.version}" else "") +
                    (if (s.likes > 0) " · ♥ ${formatCount(s.likes)}" else "") +
                    (if (d.downloads > 0) " · ${formatCount(d.downloads)} downloads" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (installed) Text("✓ Installed (see My mods)", color = OkGreen)
            val body = plainText(d.descriptionHtml)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium)
            Text("Files", style = MaterialTheme.typography.titleMedium)
            if (d.files.isEmpty()) Text("This mod has no downloadable files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.files.forEachIndexed { i, f -> FileCard(f, recommended = i == d.recommended && d.files.size > 1) { vm.startInstall(d, f) } }
        }
    }
}

@Composable
private fun FileCard(f: ModFile, recommended: Boolean, onInstall: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.filename, fontWeight = FontWeight.SemiBold)
            Text(
                (if (f.description.isNotEmpty()) "${f.description} · " else "") + formatSize(f.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (f.note.isNotEmpty()) Text(f.note, style = MaterialTheme.typography.bodySmall, color = if (f.noteKind == "ok") OkGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!f.avClean) Text("GameBanana's virus scan flagged this file", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (recommended) Text("Recommended", color = WarnAmber, style = MaterialTheme.typography.labelMedium) else Text("")
                Button(onClick = onInstall, enabled = f.avClean) { Text("Install") }
            }
        }
    }
}
