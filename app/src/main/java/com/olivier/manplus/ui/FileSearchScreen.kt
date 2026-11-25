@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olivier.manplus.data.CUSTOM_FORMAT
import com.olivier.manplus.data.FILE_FORMATS
import com.olivier.manplus.data.LinkChecker
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings
import com.olivier.manplus.data.WebSearch
import kotlinx.coroutines.launch

/** Un résultat affiché : le résultat brut + le verdict de la vérification du lien. */
private data class FileHit(val r: WebSearch.FileResult, val verified: Boolean)

/** Onglet « Fichiers » : choisir le format (pdf, epub…), saisir le titre, chercher partout sur le web. */
@Composable
fun FileSearchTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(Settings.fileFormat) }
    var custom by rememberSaveable { mutableStateOf(Settings.fileCustomExt) }
    var title by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    val hits = remember { mutableStateListOf<FileHit>() }
    var showPages by rememberSaveable { mutableStateOf(false) }

    /** Extensions effectivement recherchées selon le format choisi ; vide si l'extension libre est invalide. */
    fun exts(): List<String> =
        if (format == CUSTOM_FORMAT) custom.lowercase().removePrefix(".").takeIf { Regex("[a-z0-9]{1,6}").matches(it) }
            ?.let { listOf(it) } ?: emptyList()
        else FILE_FORMATS.find { it.id == format }?.exts ?: listOf("pdf")

    fun search() {
        val t = title.trim()
        val e = exts()
        if (status != null) return
        if (t.length < 2) { Toast.makeText(ctx, "Saisis un titre", Toast.LENGTH_SHORT).show(); return }
        if (e.isEmpty()) { Toast.makeText(ctx, "Saisis une extension (ex. djvu)", Toast.LENGTH_SHORT).show(); return }
        status = "Recherche sur le web…"
        info = ""
        hits.clear()
        scope.launch {
            try {
                val res = WebSearch.searchFiles(t, e, Settings.lang)
                status = "Vérification des liens…"
                val checks = LinkChecker.checkAll(res.take(30).map { it.result.url })
                val kept = res.filter { checks[it.result.url]?.state != LinkChecker.State.DEAD }
                val dead = res.size - kept.size
                hits.addAll(kept.map { FileHit(it, checks[it.result.url]?.state == LinkChecker.State.OK) })
                val files = kept.count { it.direct }
                info = if (kept.isEmpty()) "Aucun fichier trouvé pour « $t ». Essaie un autre titre, un autre format ou une autre langue (Accueil)."
                else "$files fichier(s) direct(s), ${kept.size - files} page(s) web" +
                    (if (dead > 0) " · $dead lien(s) mort(s) écarté(s)" else "")
            } catch (ex: Exception) {
                info = "Erreur de recherche : ${ex.message}"
            }
            status = null
        }
    }

    val shown = hits.filter { it.r.direct || showPages }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📁 Type de fichier", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FILE_FORMATS.forEach { f ->
                        FilterChip(format == f.id, { format = f.id; Settings.fileFormat = f.id }, { Text(f.label) })
                    }
                    FilterChip(format == CUSTOM_FORMAT, { format = CUSTOM_FORMAT; Settings.fileFormat = CUSTOM_FORMAT }, { Text("Autre…") })
                }
                if (format == CUSTOM_FORMAT) OutlinedTextField(
                    custom, { custom = it.take(7); Settings.fileCustomExt = custom }, Modifier.fillMaxWidth(),
                    label = { Text("Extension (ex. djvu, rtf, odt)") }, singleLine = true
                )
                OutlinedTextField(
                    title, { title = it }, Modifier.fillMaxWidth(),
                    label = { Text("Titre du fichier") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() })
                )
                Button({ search() }, Modifier.fillMaxWidth(), enabled = status == null) { Text("🔎 Rechercher") }
            }
        }

        status?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (info.isNotBlank()) Text(info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        if (hits.any { !it.r.direct }) Row(
            Modifier.fillMaxWidth().clickable { showPages = !showPages }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(showPages, { showPages = it })
            Text("Afficher aussi les pages web (${hits.count { !it.r.direct }})", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.r.result.url }) { h ->
                val r = h.r.result
                Column(Modifier.fillMaxWidth().clickable { openLink(ctx, r.url) }.padding(vertical = 6.dp)) {
                    Text(
                        (if (h.r.direct) h.r.ext.uppercase() else "Page") + " · " + r.domain +
                            " · ${h.r.score} %" + (if (h.verified) " · 🔗 vérifié" else ""),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(r.title.ifBlank { r.url }, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    Row {
                        if (h.r.direct) TextButton({ downloadFile(ctx, r.url, r.title, h.r.ext) }) { Text("⬇ Télécharger") }
                        TextButton({ openLink(ctx, r.url) }) { Text(if (h.r.direct) "Ouvrir" else "Ouvrir la page") }
                        TextButton({ copyLink(ctx, r.url) }) { Text("Copier le lien") }
                    }
                }
            }
        }
    }
}

private fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Impossible d'ouvrir ce lien", Toast.LENGTH_LONG).show()
    }
}

private fun copyLink(ctx: Context, url: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Lien", url))
    Toast.makeText(ctx, "Lien copié", Toast.LENGTH_SHORT).show()
}

/** Télécharge dans Téléchargements/ManPlus ; si le système refuse, on laisse le navigateur s'en charger. */
private fun downloadFile(ctx: Context, url: String, title: String, ext: String) {
    val name = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(80).ifBlank { "Fichier" } + "." + ext
    try {
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .addRequestHeader("User-Agent", Repo.UA)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ManPlus/$name")
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(ctx, "Téléchargement lancé (dossier Téléchargements/ManPlus)", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        openLink(ctx, url)
    }
}
