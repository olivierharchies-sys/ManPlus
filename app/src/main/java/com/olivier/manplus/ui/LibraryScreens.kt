@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.olivier.manplus.data.Manual
import com.olivier.manplus.data.NotPdfException
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings
import com.olivier.manplus.data.WebSearch
import kotlinx.coroutines.launch

fun NavController.openChat(id: String) = navigate("chat/$id") { popUpTo("library") }

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
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ManPlus") }, actions = {
                IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Réglages") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("search") },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Ajouter un manuel") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BusyBar()
            if (list.isEmpty()) {
                Text(
                    "Aucun manuel.\nAppuie sur « Ajouter un manuel » et encode le nom du produit.",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge
                )
            }
            LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { m ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${m.id}") }) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.product, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${m.pages} pages · ${m.title}", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (m.chunks == 0) Text("⚠ Pas de texte (PDF scanné)", style = MaterialTheme.typography.bodySmall)
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

// ---------------- Recherche ----------------
@Composable
fun SearchScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var product by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WebSearch.Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val busy by Repo.busy.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try { nav.openChat(Repo.importUri(uri, product)) }
            catch (e: Exception) { Toast.makeText(ctx, e.message ?: "Erreur", Toast.LENGTH_LONG).show() }
        }
    }

    fun doSearch() {
        if (product.isBlank() || loading) return
        loading = true; info = null
        scope.launch {
            results = try { WebSearch.search(product.trim()) } catch (e: Exception) { emptyList() }
            loading = false
            if (results.isEmpty()) info = "Aucun résultat. Essaie le navigateur intégré."
        }
    }

    fun open(r: WebSearch.Result) {
        if (busy != null) return
        scope.launch {
            try { nav.openChat(Repo.importUrl(r.url, product.ifBlank { r.title })) }
            catch (e: NotPdfException) {
                nav.navigate("browser?url=${Uri.encode(r.url)}&p=${Uri.encode(product)}")
            } catch (e: Exception) {
                Toast.makeText(ctx, "Échec : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Ajouter un manuel") }, navigationIcon = {
            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                product, { product = it }, Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Nom / référence du produit") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() })
            )
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ doSearch() }, enabled = product.isNotBlank() && !loading) { Text("Internet") }
                OutlinedButton({
                    val q = Uri.encode("${product.trim()} manuel pdf")
                    nav.navigate("browser?url=${Uri.encode("https://www.google.com/search?q=$q")}&p=${Uri.encode(product)}")
                }, enabled = product.isNotBlank()) { Text("Navigateur") }
                OutlinedButton({ picker.launch(arrayOf("application/pdf")) }) { Text("Téléphone") }
            }
            Spacer(Modifier.height(4.dp))
            BusyBar()
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
            info?.let { Text(it, Modifier.padding(12.dp)) }
            LazyColumn(Modifier.weight(1f)) {
                items(results) { r ->
                    ListItem(
                        modifier = Modifier.clickable { open(r) },
                        overlineContent = { Text((if (r.isPdf) "PDF · " else "Page · ") + r.domain) },
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (r.snippet.isBlank()) null else {
                            { Text(r.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    )
                }
            }
        }
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
