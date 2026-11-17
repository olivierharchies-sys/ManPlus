@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.olivier.manplus.ai.NanoEngine
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.olivier.manplus.data.CUSTOM_TYPE
import com.olivier.manplus.data.DOC_TYPES
import com.olivier.manplus.data.LANGS
import com.olivier.manplus.data.Manual
import com.olivier.manplus.data.NotPdfException
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings
import com.olivier.manplus.data.WebSearch
import kotlinx.coroutines.launch

fun NavController.openChat(id: String) = navigate("chat/$id") { popUpTo("home") }

@Composable
fun BusyBar() {
    val busy by Repo.busy.collectAsState()
    busy?.let {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- Bibliothèque ----------------
@Composable
fun LibraryScreen(nav: NavController) {
    val list by Repo.manuals.collectAsState()
    var toDelete by remember { mutableStateOf<Manual?>(null) }
    var favs by remember { mutableStateOf(Settings.favorites) }
    val sorted = remember(list, favs) { list.sortedByDescending { it.id in favs } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mes documents") }, navigationIcon = {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BusyBar()
            if (list.isEmpty()) {
                Text(
                    "Aucun document.\nUtilise les onglets Médicament ou Manuel pour en chercher un.",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge
                )
            }
            LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sorted, key = { it.id }) { m ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${m.id}") }) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (m.domain == Repo.D_MED) "💊 " else "📘 ") + m.product, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${m.pages} pages · ${m.title}", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (m.chunks == 0) Text("⚠ Pas de texte (PDF scanné)", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton({
                                favs = if (m.id in favs) favs - m.id else favs + m.id
                                Settings.favorites = favs
                            }) {
                                Icon(
                                    Icons.Default.Star, if (m.id in favs) "Retirer des favoris" else "Ajouter aux favoris",
                                    tint = if (m.id in favs) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            IconButton({ nav.navigate("viewer/${m.id}") }) { Icon(Icons.AutoMirrored.Filled.List, "Document") }
                            IconButton({ toDelete = m }) { Icon(Icons.Default.Delete, "Supprimer") }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer ?") },
            text = { Text(m.product) },
            confirmButton = { TextButton({ Repo.delete(m.id); toDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Annuler") } }
        )
    }
}

// ---------------- Réglages ----------------
@Composable
fun SettingsScreen(nav: NavController) {
    var mode by remember { mutableStateOf(Settings.mode) }
    var key by remember { mutableStateOf(Settings.apiKey) }
    var model by remember { mutableStateOf(Settings.model) }
    var nano by remember { mutableStateOf("…") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { nano = try { NanoEngine.statusText() } catch (e: Throwable) { "Non supporté (${e.message})" } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Réglages") }, navigationIcon = {
            IconButton({
                Settings.mode = mode; Settings.apiKey = key; Settings.model = model
                nav.popBackStack()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("Moteur de réponse", fontWeight = FontWeight.Bold)
            listOf(
                "AUTO" to "Auto (Nano → Gemini cloud → extraits)",
                "NANO" to "Gemini Nano uniquement (hors-ligne)",
                "CLOUD" to "Gemini cloud uniquement",
                "EXTRACT" to "Extraits du manuel seulement"
            ).forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clickable { mode = k; Settings.mode = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == k, { mode = k; Settings.mode = k })
                    Text(label)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Gemini Nano : $nano")
            Button({
                scope.launch {
                    nano = "Vérification…"
                    val ok = try { NanoEngine.ensureReady { nano = it } } catch (e: Throwable) { false }
                    nano = if (ok) "Disponible" else try { NanoEngine.statusText() } catch (e: Throwable) { "Non supporté" }
                }
            }) { Text("Préparer / télécharger Nano") }
            Spacer(Modifier.height(16.dp))
            Text("Gemini cloud (clé gratuite aistudio.google.com)", fontWeight = FontWeight.Bold)
            OutlinedTextField(key, { key = it; Settings.apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Clé API") }, singleLine = true)
            OutlinedTextField(model, { model = it; Settings.model = it }, Modifier.fillMaxWidth(), label = { Text("Modèle") }, singleLine = true)
        }
    }
}
