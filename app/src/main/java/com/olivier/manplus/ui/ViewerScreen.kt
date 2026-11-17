@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.olivier.manplus.ai.Retriever
import com.olivier.manplus.data.Mark
import com.olivier.manplus.data.PdfMarks
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings
import com.olivier.manplus.data.Toc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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

/** Lecture à voix haute (synthèse vocale du téléphone). */
private class Speaker(ctx: Context, private val lang: String) {
    var ready by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.forLanguageTag(lang))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                })
                ready = true
            }
        }
    }

    fun speak(text: String) {
        val t = tts ?: return
        val max = TextToSpeech.getMaxSpeechInputLength()
        t.speak(text.take(max - 1), TextToSpeech.QUEUE_FLUSH, null, "manplus")
        speaking = true
    }

    fun stop() { tts?.stop(); speaking = false }

    fun shutdown() { tts?.stop(); tts?.shutdown() }
}

@Composable
fun ViewerScreen(nav: NavController, id: String, page: Int, q: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = Repo.get(id)
    val holder = remember(id) { PdfHolder(Repo.pdf(id)) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val count = holder.count
    val startIdx = (page - 1).coerceIn(0, maxOf(0, count - 1))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIdx)
    val readerState = rememberLazyListState(initialFirstVisibleItemIndex = startIdx)

    val excerpt = remember(id, page, q) {
        if (q.isBlank()) null else Retriever.bestOnPage(id, Repo.chunks(id), page, q)?.text
    }
    var showExcerpt by remember { mutableStateOf(excerpt != null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pagePx by remember { mutableIntStateOf(1000) }

    // ---- recherche dans le document : surbrillance + navigation entre occurrences ----
    var query by remember { mutableStateOf(q) }
    var field by remember { mutableStateOf(q) }
    var searchOpen by remember { mutableStateOf(false) }
    var cur by remember { mutableIntStateOf(0) }
    var navTick by remember { mutableIntStateOf(0) }
    val marks by produceState<List<Mark>>(emptyList(), id, query) {
        value = if (query.isBlank() || holder.error != null) emptyList() else PdfMarks.find(Repo.pdf(id), query)
    }
    val byPage = remember(marks) { marks.withIndex().groupBy { it.value.page } }

    // ---- affichage ----
    var reader by remember { mutableStateOf(false) }
    val firstVisible = { if (reader) readerState.firstVisibleItemIndex else state.firstVisibleItemIndex }
    var fontSp by remember { mutableFloatStateOf(17f) }
    var contrast by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var note by remember(id) { mutableStateOf(Repo.note(id)) }

    val toc by produceState<List<Toc>>(emptyList(), id) {
        val o = PdfMarks.outline(Repo.pdf(id))
        value = if (o.isNotEmpty()) o else withContext(Dispatchers.IO) { PdfMarks.headings(Repo.chunks(id)) }
    }
    val texts by produceState<Map<Int, String>>(emptyMap(), id) {
        value = withContext(Dispatchers.IO) { PdfMarks.pageTexts(Repo.chunks(id)) }
    }
    val speaker = remember { Speaker(ctx, Settings.lang) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    // Quand de nouvelles occurrences arrivent : on se place sur la première à partir de la page affichée.
    LaunchedEffect(marks) {
        cur = marks.indexOfFirst { it.page >= firstVisible() }.let { if (it < 0) 0 else it }
        if (searchOpen && marks.isNotEmpty()) navTick++
    }
    // Navigation explicite (flèches) : on centre l'occurrence courante.
    LaunchedEffect(navTick) {
        if (navTick == 0) return@LaunchedEffect
        val mk = marks.getOrNull(cur) ?: return@LaunchedEffect
        if (reader) readerState.animateScrollToItem(mk.page.coerceIn(0, maxOf(0, count - 1)))
        else {
            val pageH = pagePx * holder.ratios.getOrElse(mk.page) { 1.414f }
            state.animateScrollToItem(mk.page.coerceIn(0, maxOf(0, count - 1)), (mk.y * pageH - 160).toInt().coerceAtLeast(0))
        }
    }

    fun step(d: Int) {
        if (marks.isEmpty()) return
        cur = (cur + d + marks.size) % marks.size
        navTick++
    }

    fun submitSearch() { query = field.trim() }

    fun openWith() {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Repo.pdf(id))
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Ouvrir avec"))
        } catch (e: Exception) {
            Toast.makeText(ctx, e.message ?: "Erreur", Toast.LENGTH_LONG).show()
        }
    }

    fun sharePassage(text: String, p: Int) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "${m?.product ?: "Document"} – p. $p\n\n$text")
        ctx.startActivity(Intent.createChooser(i, "Partager le passage"))
    }

    fun jumpTo(p: Int) {
        val i = p.coerceIn(0, maxOf(0, count - 1))
        scope.launch { if (reader) readerState.scrollToItem(i) else state.scrollToItem(i) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(m?.product ?: "Document", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    val shown = (if (reader) readerState.firstVisibleItemIndex else state.firstVisibleItemIndex) + 1
                    Text("Page $shown/$count", style = MaterialTheme.typography.bodySmall)
                }
            },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = {
                IconButton({ searchOpen = !searchOpen }) { Icon(Icons.Default.Search, "Chercher dans le document") }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Plus") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Sommaire" + if (toc.isEmpty()) " (aucun)" else "") },
                            enabled = toc.isNotEmpty(),
                            onClick = { menu = false; tocOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text(if (reader) "Mode page (PDF)" else "Mode lecture (texte)") },
                            onClick = { menu = false; reader = !reader; if (!reader) speaker.stop() }
                        )
                        DropdownMenuItem(
                            text = { Text("Notes" + if (note.isNotBlank()) " •" else "") },
                            onClick = { menu = false; noteOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Zoom : ajuster à la largeur") },
                            onClick = { menu = false; zoom = 1f }
                        )
                        DropdownMenuItem(text = { Text("Ouvrir avec…") }, onClick = { menu = false; openWith() })
                    }
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            holder.error?.let { Text("Affichage impossible : $it", Modifier.padding(16.dp)) }

            if (searchOpen) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        field, { field = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Mot à chercher") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() })
                    )
                    Text(
                        when {
                            query.isBlank() -> ""
                            marks.isEmpty() -> "0"
                            else -> "${cur + 1}/${marks.size}"
                        },
                        Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelLarge
                    )
                    IconButton({ step(-1) }, enabled = marks.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, "Précédent") }
                    IconButton({ step(1) }, enabled = marks.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, "Suivant") }
                    IconButton({ searchOpen = false; field = ""; query = "" }) { Icon(Icons.Default.Close, "Fermer la recherche") }
                }
            }

            if (showExcerpt && excerpt != null) {
                Card(
                    Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Passage de la réponse – p. $page", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium)
                        Text(highlight(excerpt, q), maxLines = 7, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton({ sharePassage(excerpt, page) }) { Text("Partager") }
                            TextButton({ showExcerpt = false }) { Text("Masquer") }
                        }
                    }
                }
            }

            if (reader) {
                ReaderView(
                    count, texts, query, readerState, fontSp, contrast, speaker,
                    onSmaller = { fontSp = (fontSp - 2f).coerceAtLeast(12f) },
                    onBigger = { fontSp = (fontSp + 2f).coerceAtMost(36f) },
                    onContrast = { contrast = !contrast }
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF808080))) {
                    val pageW = maxWidth * zoom
                    val pxRaw = with(LocalDensity.current) { pageW.toPx() }.toInt()
                    // Largeur de rendu arrondie par paliers : évite de refaire le rendu à chaque cran de pincement.
                    val px = ((pxRaw + 99) / 200 * 200).coerceIn(400, 2400)
                    SideEffect { pagePx = pxRaw }
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                // Pincement à deux doigts = zoom ; un doigt reste réservé au défilement.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        if (ev.changes.size >= 2) {
                                            val z = ev.calculateZoom()
                                            if (z != 1f) {
                                                zoom = (zoom * z).coerceIn(1f, 4f)
                                                ev.changes.forEach { it.consume() }
                                            }
                                        }
                                    } while (ev.changes.any { it.pressed })
                                }
                            }
                            .horizontalScroll(rememberScrollState()),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(count) { i ->
                            val bmp by produceState<Bitmap?>(null, i, px) { value = holder.render(i, px) }
                            val pageMarks = byPage[i].orEmpty()
                            Box(
                                Modifier.width(pageW).aspectRatio(1f / holder.ratios[i]).background(Color.White),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize()) }
                                if (pageMarks.isNotEmpty()) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        pageMarks.forEach { iv ->
                                            val mk = iv.value
                                            drawRect(
                                                if (iv.index == cur) Color(0xAAFF9800) else Color(0x66FFEB3B),
                                                Offset(mk.x * size.width, mk.y * size.height),
                                                Size(mk.w * size.width, mk.h * size.height)
                                            )
                                        }
                                    }
                                }
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

    if (tocOpen) {
        AlertDialog(
            onDismissRequest = { tocOpen = false },
            title = { Text("Sommaire") },
            text = {
                LazyColumn {
                    items(toc) { t ->
                        Text(
                            t.title + "  ·  p. ${t.page + 1}",
                            Modifier.fillMaxWidth()
                                .clickable { tocOpen = false; jumpTo(t.page) }
                                .padding(start = (t.depth * 12).dp, top = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = { TextButton({ tocOpen = false }) { Text("Fermer") } }
        )
    }

    if (noteOpen) {
        var draft by remember { mutableStateOf(note) }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("Notes sur ce document") },
            text = {
                OutlinedTextField(
                    draft, { draft = it }, Modifier.fillMaxWidth(), minLines = 4,
                    placeholder = { Text("Ta note personnelle…") }
                )
            },
            confirmButton = {
                TextButton({ note = draft; Repo.saveNote(id, draft); noteOpen = false }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton({ noteOpen = false }) { Text("Annuler") } }
        )
    }
}

/** Texte du document : taille de police réglable, contraste élevé, lecture à voix haute. */
@Composable
private fun ReaderView(
    count: Int, texts: Map<Int, String>, query: String, listState: LazyListState,
    fontSp: Float, contrast: Boolean, speaker: Speaker,
    onSmaller: () -> Unit, onBigger: () -> Unit, onContrast: () -> Unit
) {
    val bg = if (contrast) Color.Black else MaterialTheme.colorScheme.surface
    val fg = if (contrast) Color(0xFFFFEB3B) else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onSmaller) { Text("A−") }
            TextButton(onBigger) { Text("A+") }
            TextButton(onContrast) { Text(if (contrast) "Contraste ✔" else "Contraste") }
            TextButton(
                onClick = {
                    if (speaker.speaking) speaker.stop()
                    else speaker.speak(texts[listState.firstVisibleItemIndex + 1].orEmpty().ifBlank { "Page sans texte." })
                },
                enabled = speaker.ready
            ) { Text(if (speaker.speaking) "⏹ Stop" else "🔊 Lire la page") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(count) { i ->
                val t = texts[i + 1]
                Text("— Page ${i + 1} —", color = fg, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (t.isNullOrBlank()) {
                    Text("(Pas de texte sur cette page — document scanné ?)", color = fg, fontSize = fontSp.sp)
                } else {
                    Text(
                        if (query.isBlank()) AnnotatedString(t) else highlight(t, query),
                        color = fg, fontSize = fontSp.sp, lineHeight = (fontSp * 1.5f).sp
                    )
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
