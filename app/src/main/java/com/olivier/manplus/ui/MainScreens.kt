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

/** Préférences de recherche (langue, format, type de document), réglées depuis l'accueil. */
@Composable
private fun SearchPrefs() {
    var open by rememberSaveable { mutableStateOf(false) }
    var lang by remember { mutableStateOf(Settings.lang) }
    var format by remember { mutableStateOf(Settings.format) }
    var typeMan by remember { mutableStateOf(Settings.typeMan) }
    var typeMed by remember { mutableStateOf(Settings.typeMed) }
    var customMan by remember { mutableStateOf(Settings.customMan) }
    var customMed by remember { mutableStateOf(Settings.customMed) }

    fun typeLabel(id: String, custom: String, med: Boolean) =
        if (id == CUSTOM_TYPE) custom.ifBlank { "Autre" }
        else DOC_TYPES.find { it.id == id && it.medicine == med }?.label ?: "—"

    val formatLabel = mapOf("pdf" to "PDF", "doc" to "Word", "all" to "Tous formats")

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔎 ${LANGS.find { it.code == lang }?.label ?: lang} · ${formatLabel[format]} · " +
                        "manuel : ${typeLabel(typeMan, customMan, false)} · " +
                        "médicament : ${typeLabel(typeMed, customMed, true)}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                )
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                Text("Langue", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LANGS.forEach { l ->
                        FilterChip(lang == l.code, { lang = l.code; Settings.lang = l.code }, { Text(l.label) })
                    }
                }
                Text("Format du fichier", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    formatLabel.forEach { (k, v) ->
                        FilterChip(format == k, { format = k; Settings.format = k }, { Text(v) })
                    }
                }
                Text("Type de document – manuel", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DOC_TYPES.filter { !it.medicine }.forEach { t ->
                        FilterChip(typeMan == t.id, { typeMan = t.id; Settings.typeMan = t.id }, { Text(t.label) })
                    }
                    FilterChip(typeMan == CUSTOM_TYPE, { typeMan = CUSTOM_TYPE; Settings.typeMan = CUSTOM_TYPE }, { Text("Autre…") })
                }
                if (typeMan == CUSTOM_TYPE) OutlinedTextField(
                    customMan, { customMan = it; Settings.customMan = it }, Modifier.fillMaxWidth(),
                    label = { Text("Type de document (ex. schéma de câblage)") }, singleLine = true
                )
                Text("Type de document – médicament", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DOC_TYPES.filter { it.medicine }.forEach { t ->
                        FilterChip(typeMed == t.id, { typeMed = t.id; Settings.typeMed = t.id }, { Text(t.label) })
                    }
                    FilterChip(typeMed == CUSTOM_TYPE, { typeMed = CUSTOM_TYPE; Settings.typeMed = CUSTOM_TYPE }, { Text("Autre…") })
                }
                if (typeMed == CUSTOM_TYPE) OutlinedTextField(
                    customMed, { customMed = it; Settings.customMed = it }, Modifier.fillMaxWidth(),
                    label = { Text("Type de document (ex. posologie enfant)") }, singleLine = true
                )
            }
        }
    }
}
