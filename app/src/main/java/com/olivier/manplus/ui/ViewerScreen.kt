@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.olivier.manplus.ai.Retriever
import com.olivier.manplus.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Rendu PDF thread-safe. */
class PdfHolder(file: File) {
    private val mutex = Mutex()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    val ratios: FloatArray   // hauteur / largeur
    val error: String?

    init {
        var err: String? = null
        var r = FloatArray(0)
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            val rd = renderer!!
            r = FloatArray(rd.pageCount) { i ->
                rd.openPage(i).use { p -> if (p.width > 0) p.height.toFloat() / p.width else 1.414f }
            }
        } catch (e: Exception) {
            err = e.message ?: "PDF illisible"
        }
        ratios = r; error = err
    }

    val count get() = ratios.size

    suspend fun render(i: Int, w: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rd = renderer ?: return@withLock null
            try {
                rd.openPage(i).use { p ->
                    val h = (w * ratios[i]).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AColor.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            } catch (e: Exception) { null }
        }
    }

    fun close() {
        Repo.scope.launch {
            mutex.withLock {
                runCatching { renderer?.close() }; runCatching { pfd?.close() }
                renderer = null; pfd = null
            }
        }
    }
}

@Composable
fun ViewerScreen(nav: NavController, id: String, page: Int, q: String) {
    val ctx = LocalContext.current
    val m = Repo.get(id)
    val holder = remember(id) { PdfHolder(Repo.pdf(id)) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val count = holder.count
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (page - 1).coerceIn(0, maxOf(0, count - 1)))
    val excerpt = remember(id, page, q) {
        if (q.isBlank()) null else Retriever.bestOnPage(id, Repo.chunks(id), page, q)?.text
    }
    var showExcerpt by remember { mutableStateOf(excerpt != null) }
    var zoom by remember { mutableFloatStateOf(1f) }

    fun share() {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Repo.pdf(id))
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Ouvrir avec"))
        } catch (e: Exception) {
            Toast.makeText(ctx, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(m?.product ?: "Document", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    Text("Page ${state.firstVisibleItemIndex + 1}/$count", style = MaterialTheme.typography.bodySmall)
                }
            },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = {
                IconButton({ zoom = if (zoom >= 3f) 1f else zoom + 0.5f }) {
                    if (zoom >= 3f) Icon(Icons.Default.Close, "Zoom 1x") else Icon(Icons.Default.Add, "Zoom +")
                }
                IconButton({ share() }) { Icon(Icons.Default.Share, "Ouvrir avec") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            holder.error?.let { Text("Affichage impossible : $it", Modifier.padding(16.dp)) }
            if (showExcerpt && excerpt != null) {
                Card(
                    Modifier.fillMaxWidth().padding(8.dp).clickable { showExcerpt = false },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Passage de la réponse – p. $page (toucher pour masquer)", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium)
                        Text(highlight(excerpt, q), maxLines = 7, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF808080))) {
                val pageW = maxWidth * zoom
                val px = with(LocalDensity.current) { pageW.toPx() }.toInt().coerceIn(200, 2400)
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(count) { i ->
                        val bmp by produceState<Bitmap?>(null, i, px) { value = holder.render(i, px) }
                        Box(
                            Modifier.width(pageW).aspectRatio(1f / holder.ratios[i]).background(Color.White),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize()) }
                            Text(
                                "${i + 1}", Modifier.background(Color(0x99000000)).padding(4.dp),
                                color = Color.White, style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun highlight(text: String, q: String): AnnotatedString {
    val terms = Retriever.tokens(q).toSet()
    return buildAnnotatedString {
        Regex("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+").findAll(text).forEach { m ->
            val w = m.value
            val isHit = w.first().isLetterOrDigit() && Retriever.tokens(w).any { it in terms }
            if (isHit) withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x66FFEB3B))) { append(w) }
            else append(w)
        }
    }
}
