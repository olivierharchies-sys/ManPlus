package com.olivier.manplus.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.olivier.manplus.ai.VisionSearch
import com.olivier.manplus.data.WebSearch
import kotlinx.coroutines.launch
import java.io.File

/**
 * Recherche combinée : jusqu'à 4 photos + codes (code-barres / QR) + indice texte.
 * Les photos servent à identifier UN produit (Gemini cloud) ; [onResult] reçoit le libellé
 * du message et la requête de recherche de document.
 */
@Composable
fun PhotoSearchDialog(medicine: Boolean, onDismiss: () -> Unit, onResult: (label: String, query: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = remember { mutableStateListOf<Uri>() }
    val codes = remember { mutableStateListOf<String>() }
    var hint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var camUri by remember { mutableStateOf<Uri?>(null) }

    fun add(list: List<Uri>) {
        val room = VisionSearch.MAX_PHOTOS - photos.size
        photos += list.take(room.coerceAtLeast(0))
    }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(VisionSearch.MAX_PHOTOS)
    ) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) camUri?.let { add(listOf(it)) }
    }

    fun gallery() {
        try {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Galerie indisponible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shoot() {
        try {
            val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
            val f = File.createTempFile("p", ".jpg", dir)
            val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            camUri = u
            camera.launch(u)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Appareil photo indisponible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun scan() {
        try {
            GmsBarcodeScanning.getClient(ctx).startScan()
                .addOnSuccessListener { b ->
                    val raw = b.rawValue?.trim().orEmpty()
                    if (raw.isNotEmpty() && raw !in codes && codes.size < 3) codes += raw
                }
                .addOnFailureListener { Toast.makeText(ctx, "Scan impossible : ${it.message}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Scanner indisponible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun identify() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // un code-barres produit → nom (aide l'identification, et sert de secours sans photo)
                var fromCode: String? = null
                for (c in codes) { fromCode = WebSearch.productName(c); if (fromCode != null) break }
                val extra = listOfNotNull(hint.trim().ifBlank { null }, fromCode).joinToString(" ; ")
                if (photos.isEmpty()) {
                    val q = fromCode ?: codes.firstOrNull() ?: hint.trim()
                    onResult("📷 ${codes.joinToString(" + ")}" + if (fromCode != null) " → $fromCode" else "", q)
                } else {
                    val g = VisionSearch.identify(ctx, photos.toList(), medicine, codes.toList(), extra)
                    if (g.name.isBlank()) {
                        Toast.makeText(
                            ctx, "Produit non identifié. Ajoute une photo plus nette de l'étiquette ou un code.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val parts = buildList {
                            add("${photos.size} photo(s)")
                            if (codes.isNotEmpty()) add("${codes.size} code(s)")
                        }
                        onResult("🖼 ${parts.joinToString(" + ")} → ${g.query}", g.query)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Échec : ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Recherche par photos et codes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Jusqu'à ${VisionSearch.MAX_PHOTOS} photos du même produit (face, dos, étiquette…) : l'IA en retrouve 1 seul. " +
                        "Tu peux y ajouter un code-barres ou QR.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    photos.forEachIndexed { i, u ->
                        val bmp = remember(u) { VisionSearch.thumb(ctx, u) }
                        Box(Modifier.clickable { photos.removeAt(i) }) {
                            if (bmp != null) Image(
                                bmp.asImageBitmap(), "Photo ${i + 1} (toucher pour retirer)",
                                Modifier.size(72.dp), contentScale = ContentScale.Crop
                            ) else Text("?", Modifier.size(72.dp))
                        }
                    }
                }
                if (photos.isNotEmpty()) Text("Touche une photo pour la retirer.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ shoot() }, enabled = photos.size < VisionSearch.MAX_PHOTOS && !busy) { Text("📸 Photo") }
                    OutlinedButton({ gallery() }, enabled = photos.size < VisionSearch.MAX_PHOTOS && !busy) { Text("🖼 Galerie") }
                    OutlinedButton({ scan() }, enabled = codes.size < 3 && !busy) { Text("▮▮ Code") }
                }
                if (codes.isNotEmpty()) Row(
                    Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    codes.forEachIndexed { i, c -> AssistChip({ codes.removeAt(i) }, { Text("✕ ${c.take(24)}") }) }
                }
                OutlinedTextField(
                    hint, { hint = it }, Modifier.fillMaxWidth(),
                    label = { Text("Indice (facultatif : marque, nom…)") }, singleLine = true
                )
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Identification du produit…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button({ identify() }, enabled = !busy && (photos.isNotEmpty() || codes.isNotEmpty())) { Text("Identifier") }
        },
        dismissButton = { TextButton({ onDismiss() }, enabled = !busy) { Text("Annuler") } }
    )
}
