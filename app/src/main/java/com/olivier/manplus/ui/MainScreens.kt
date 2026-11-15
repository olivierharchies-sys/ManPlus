@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.olivier.manplus.data.CUSTOM_TYPE
import com.olivier.manplus.data.DOC_TYPES
import com.olivier.manplus.data.LANGS
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings

/** Écran principal : 3 onglets (Accueil, Médicament, Manuel), chacun avec son propre chat. */
@Composable
fun MainTabs(nav: NavController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ManPlus") }, actions = {
                IconButton({ nav.navigate("library") }) { Icon(Icons.AutoMirrored.Filled.List, "Mes documents") }
                IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Réglages") }
            })
        },
        bottomBar = {
            NavigationBar {
                listOf("🏠" to "Accueil", "💊" to "Médicament", "📘" to "Manuel").forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(icon) }, label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            when (tab) {
                0 -> {
                    SearchPrefs()
                    ChatBody(nav, Repo.ALL, Modifier.weight(1f))
                }
                1 -> ChatBody(nav, Repo.MED, Modifier.weight(1f))
                else -> ChatBody(nav, Repo.MAN, Modifier.weight(1f))
            }
        }
    }
}

/** Accueil : seule la langue de recherche se règle ici. */
@Composable
private fun SearchPrefs() {
    var lang by remember { mutableStateOf(Settings.lang) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🔎 Langue de recherche", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LANGS.forEach { l ->
                    FilterChip(lang == l.code, { lang = l.code; Settings.lang = l.code }, { Text(l.label) })
                }
            }
        }
    }
}

/** Onglets Médicament / Manuel : format de fichier et type de document propres à l'onglet. */
@Composable
fun DomainPrefs(medicine: Boolean) {
    var open by rememberSaveable(medicine) { mutableStateOf(false) }
    var format by remember(medicine) { mutableStateOf(if (medicine) Settings.formatMed else Settings.formatMan) }
    var type by remember(medicine) { mutableStateOf(if (medicine) Settings.typeMed else Settings.typeMan) }
    var custom by remember(medicine) { mutableStateOf(if (medicine) Settings.customMed else Settings.customMan) }

    val formatLabel = mapOf("pdf" to "PDF", "doc" to "Word", "all" to "Tous formats")
    val typeLabel =
        if (type == CUSTOM_TYPE) custom.ifBlank { "Autre" }
        else DOC_TYPES.find { it.id == type && it.medicine == medicine }?.label ?: "—"

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚙ ${formatLabel[format]} · $typeLabel",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                )
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                Text("Format du fichier", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    formatLabel.forEach { (k, v) ->
                        FilterChip(format == k, {
                            format = k
                            if (medicine) Settings.formatMed = k else Settings.formatMan = k
                        }, { Text(v) })
                    }
                }
                Text("Type de document", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DOC_TYPES.filter { it.medicine == medicine }.forEach { t ->
                        FilterChip(type == t.id, {
                            type = t.id
                            if (medicine) Settings.typeMed = t.id else Settings.typeMan = t.id
                        }, { Text(t.label) })
                    }
                    FilterChip(type == CUSTOM_TYPE, {
                        type = CUSTOM_TYPE
                        if (medicine) Settings.typeMed = CUSTOM_TYPE else Settings.typeMan = CUSTOM_TYPE
                    }, { Text("Autre…") })
                }
                if (type == CUSTOM_TYPE) OutlinedTextField(
                    custom, {
                        custom = it
                        if (medicine) Settings.customMed = it else Settings.customMan = it
                    }, Modifier.fillMaxWidth(),
                    label = { Text(if (medicine) "Type de document (ex. posologie enfant)" else "Type de document (ex. schéma de câblage)") },
                    singleLine = true
                )
            }
        }
    }
}
