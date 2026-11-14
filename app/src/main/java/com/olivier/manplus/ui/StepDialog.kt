package com.olivier.manplus.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olivier.manplus.data.Ref
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Step

/** Fenêtre « pas à pas » : une étape à la fois + illustration de la page du manuel. */
@Composable
fun StepDialog(steps: List<Step>, names: Map<String, String>, onDismiss: () -> Unit, onOpen: (Step) -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val holders = remember { HashMap<String, PdfHolder>() }
    DisposableEffect(Unit) { onDispose { holders.values.forEach { it.close() } } }
    val step = steps[i.coerceIn(0, steps.lastIndex)]

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Étape ${i + 1} / ${steps.size}", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                    )
                    IconButton(onDismiss) { Icon(Icons.Default.Close, "Fermer") }
                }
                LinearProgressIndicator({ (i + 1f) / steps.size }, Modifier.fillMaxWidth())
                key(i) {
                    Column(Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
                        Text(step.text, style = MaterialTheme.typography.titleMedium)
                        step.ref?.let { ref ->
                            val h = holders.getOrPut(ref.id) { PdfHolder(Repo.pdf(ref.id)) }
                            Spacer(Modifier.height(12.dp))
                            PageImage(h, ref) { onOpen(step) }
                            TextButton({ onOpen(step) }) {
                                val n = names[ref.id]
                                Text("📖 Voir dans le manuel" + (if (n != null) " $n" else "") + " – p. ${ref.page}")
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ i-- }, enabled = i > 0) { Text("◀ Précédent") }
                    Spacer(Modifier.weight(1f))
                    if (i < steps.lastIndex) Button({ i++ }) { Text("Suivant ▶") }
                    else Button(onDismiss) { Text("Terminer ✓") }
                }
            }
        }
    }
}

/** Illustration : rendu de la page du manuel citée par l'étape. */
@Composable
private fun PageImage(h: PdfHolder, ref: Ref, onClick: () -> Unit) {
    val idx = ref.page - 1
    if (h.error != null || idx !in 0 until h.count) {
        Text("Illustration indisponible", style = MaterialTheme.typography.bodySmall)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val px = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(200, 1600) * 2
        val bmp by produceState<Bitmap?>(null, ref, px) { value = h.render(idx, px.coerceAtMost(2400)) }
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f / h.ratios[idx]).background(Color.White).clickable(onClick = onClick)
        ) {
            bmp?.let { Image(it.asImageBitmap(), "Page ${ref.page}", Modifier.fillMaxWidth().aspectRatio(1f / h.ratios[idx])) }
        }
    }
}
