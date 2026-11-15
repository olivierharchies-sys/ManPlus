@file:OptIn(ExperimentalMaterial3Api::class)

package com.olivier.manplus.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.olivier.manplus.ai.AnswerEngine
import com.olivier.manplus.data.ChatMsg
import com.olivier.manplus.data.Ref
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.WebSearch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.olivier.manplus.data.ChatHit
import com.olivier.manplus.data.DOC_TYPES
import com.olivier.manplus.data.NotPdfException
import com.olivier.manplus.data.Settings
import kotlinx.coroutines.launch

// ---------------- Chat ----------------
/** Chat d'un seul document (ouvert depuis la bibliothèque). */
@Composable
fun ChatScreen(nav: NavController, id: String, ask: String = "") {
    val manuals by Repo.manuals.collectAsState()
    val m = manuals.find { it.id == id } ?: return
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(m.product, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = { IconButton({ nav.navigate("viewer/$id") }) { Icon(Icons.AutoMirrored.Filled.List, "Document") } }
        )
    }) { pad ->
        ChatBody(nav, id, Modifier.padding(pad).consumeWindowInsets(pad), ask)
    }
}

/**
 * Corps d'un chat. [id] = Repo.ALL (général), Repo.MED / Repo.MAN (onglets de domaine)
 * ou l'identifiant d'un document. Dans les onglets de domaine, on peut aussi chercher
 * des documents sur internet (résultats affichés dans le chat) et scanner un code.
 */
@Composable
fun ChatBody(nav: NavController, id: String, modifier: Modifier = Modifier, ask: String = "") {
    val manuals by Repo.manuals.collectAsState()
    val global = Repo.isScope(id)
    val docs = remember(manuals, id) { Repo.docsFor(id, manuals) }
    if (!global && docs.isEmpty()) return
    val domainTab = id == Repo.MED || id == Repo.MAN
    val medicine = id == Repo.MED
    val names = remember(manuals) { manuals.associate { it.id to it.product } }
    val msgs = remember(id) { mutableStateListOf<ChatMsg>().apply { addAll(Repo.loadChat(id)) } }
    var input by rememberSaveable(id) { mutableStateOf("") }
    var asked by rememberSaveable(id) { mutableStateOf(false) }
    var finding by rememberSaveable(id) { mutableStateOf(domainTab && docs.isEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var stepsOf by remember { mutableStateOf<ChatMsg?>(null) }
    var showPhotos by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val clip = LocalClipboardManager.current
    val domain = if (medicine) Repo.D_MED else Repo.D_MAN

    LaunchedEffect(msgs.size, status) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    fun post(m: ChatMsg) { msgs += m; Repo.saveChat(id, msgs.toList()) }

    // ---- recherche de documents sur internet, réponse dans le chat ----
    fun find(label: String, query: String) {
        if (status != null) return
        post(ChatMsg(true, label))
        status = "Recherche sur internet…"
        scope.launch {
            val reply = try {
                val type = DOC_TYPES.find {
                    it.id == (if (medicine) Settings.typeMed else Settings.typeMan) && it.medicine == medicine
                }
                val custom = (if (medicine) Settings.customMed else Settings.customMan).trim()
                val res = WebSearch.search(
                    query, Settings.lang, type, custom, medicine,
                    if (medicine) Settings.formatMed else Settings.formatMan
                )
                val hits = res.map { ChatHit(it.title, it.url, it.domain, it.isPdf, it.isDoc, it.coherent, it.trusted) }
                val hidden = hits.count { !it.coherent }
                ChatMsg(
                    false,
                    if (hits.isEmpty()) "Aucun document trouvé pour « $query ». Essaie un autre nom, une autre langue (Accueil) ou un autre format / type de document (⚙ en haut de l'onglet)."
                    else "${hits.size - hidden} résultat(s) pour « $query ». Touche-en un pour l'ajouter." +
                        if (hidden > 0) " $hidden résultat(s) incohérent(s) masqué(s)." else "",
                    query = query, hits = hits
                )
            } catch (e: Exception) {
                ChatMsg(false, "Erreur de recherche : ${e.message}", query = query)
            }
            status = null
            post(reply)
        }
    }

    fun openHit(h: ChatHit, product: String) {
        if (Repo.busy.value != null) return
        if (h.doc) {
            try {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(h.url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Toast.makeText(ctx, "Aucune appli pour ouvrir ce fichier", Toast.LENGTH_LONG).show()
            }
            return
        }
        scope.launch {
            try { nav.openChat(Repo.importUrl(h.url, product.ifBlank { h.title }, null, domain)) }
            catch (e: NotPdfException) {
                nav.navigate("browser?url=${Uri.encode(h.url)}&p=${Uri.encode(product)}&d=$domain")
            } catch (e: Exception) {
                Toast.makeText(ctx, "Échec : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- scanner (onglets Médicament / Manuel uniquement) ----
    fun onCode(b: Barcode) {
        val raw = b.rawValue?.trim().orEmpty()
        if (raw.isEmpty()) return
        val url = b.url?.url ?: raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        when {
            url != null ->
                if (WebSearch.looksPdf(url)) openHit(ChatHit("Document scanné", url, "", true, false, true, false), "")
                else nav.navigate("browser?url=${Uri.encode(url)}&p=&d=$domain")
            !medicine && b.format in setOf(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
            ) -> {
                status = "Code-barres $raw : recherche du produit…"
                scope.launch {
                    val name = WebSearch.productName(raw)
                    status = null
                    find("📷 $raw" + if (name != null) " → $name" else "", name ?: raw)
                }
            }
            else -> find("📷 $raw", raw)
        }
    }

    fun scan() {
        try {
            GmsBarcodeScanning.getClient(ctx).startScan()
                .addOnSuccessListener { onCode(it) }
                .addOnFailureListener { Toast.makeText(ctx, "Scan impossible : ${it.message}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Scanner indisponible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- question sur les documents ----
    fun question(q: String) {
        if (status != null) return
        post(ChatMsg(true, q))
        status = "Recherche dans " + when (id) {
            Repo.ALL -> "tous les documents…"
            Repo.MED -> "tes notices…"
            Repo.MAN -> "tes manuels…"
            else -> "le document…"
        }
        scope.launch {
            val r = try {
                AnswerEngine.answer(docs, q, msgs.toList()) { status = it }
            } catch (e: Exception) {
                ChatMsg(false, "Erreur : ${e.message}", query = q)
            }
            status = null
            post(r)
            if (r.steps.isNotEmpty()) stepsOf = r
        }
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || status != null) return
        input = ""
        if (domainTab && finding) find(q, q) else question(q)
    }

    LaunchedEffect(ask) {
        if (ask.isNotBlank() && !asked) { asked = true; input = ask; send() }
    }

    fun openRef(ref: Ref, q: String) = nav.navigate("viewer/${ref.id}?page=${ref.page}&q=${Uri.encode(q)}")

    Column(modifier.imePadding().fillMaxSize()) {
        BusyBar()
        if (domainTab) DomainPrefs(medicine)
        if (domainTab) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(finding, { finding = true }, { Text("🔎 Trouver") })
            FilterChip(!finding, { finding = false }, { Text("💬 Question") })
            Spacer(Modifier.weight(1f))
            if (msgs.isNotEmpty()) IconButton({ msgs.clear(); Repo.saveChat(id, emptyList()) }) {
                Icon(Icons.Default.Delete, "Effacer")
            }
            }
        if (domainTab) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip({ scan() }, { Text("🔳 Scanner QR / code") })
            AssistChip({ showPhotos = true }, { Text("📸 Photos") })
        }
        if (!domainTab && msgs.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton({ msgs.clear(); Repo.saveChat(id, emptyList()) }) { Icon(Icons.Default.Delete, "Effacer") }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (msgs.isEmpty()) item {
                Text(
                    when (id) {
                        Repo.ALL -> "Pose une question : la recherche porte sur tes ${manuals.size} documents (manuels et notices).\n"
                        Repo.MED -> "Tape le nom d'un médicament (ou scanne la boîte) pour trouver sa notice, puis pose tes questions.\n" +
                            "Ex. : Dafalgan 1 g"
                        Repo.MAN -> "Tape le nom d'un produit (ou scanne son code) pour trouver son manuel, puis pose tes questions.\n" +
                            "Ex. : Bosch WAN282H0FF"
                        else -> "Pose une question sur « ${docs.first().product} » (${docs.first().pages} pages).\n" +
                            "Ex. : Comment détartrer ? Que signifie l'erreur E3 ?"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(msgs) { msg ->
                Bubble(
                    msg, if (global) names else emptyMap(),
                    onRef = { openRef(it, msg.query) },
                    onSteps = { stepsOf = msg },
                    onHit = { openHit(it, msg.query) }
                )
            }
        }
        status?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (domainTab && finding) (if (medicine) "Nom du médicament…" else "Nom / référence du produit…")
                        else "Ta question… (ou colle un texte)"
                    )
                },
                maxLines = 6,
                trailingIcon = {
                    TextButton({ clip.getText()?.text?.let { input = if (input.isBlank()) it else "$input $it" } }) { Text("Coller") }
                }
            )
            IconButton({ send() }, enabled = status == null && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
            }
        }
    }

    if (showPhotos) PhotoSearchDialog(medicine, onDismiss = { showPhotos = false }) { label, query ->
        showPhotos = false
        finding = true
        find(label, query)
    }

    stepsOf?.let { msg ->
        StepDialog(msg.steps, if (global) names else emptyMap(), onDismiss = { stepsOf = null }) { step ->
            stepsOf = null
            step.ref?.let { openRef(it, step.text) }
        }
    }
}

@Composable
private fun Bubble(
    msg: ChatMsg, names: Map<String, String>,
    onRef: (Ref) -> Unit, onSteps: () -> Unit, onHit: (ChatHit) -> Unit
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer { Text(msg.text) }
                if (msg.hits.isNotEmpty()) {
                    var showAll by remember { mutableStateOf(false) }
                    val hidden = msg.hits.count { !it.coherent }
                    val shown = if (showAll) msg.hits else msg.hits.filter { it.coherent }
                    shown.forEach { h ->
                        Column(Modifier.fillMaxWidth().clickable { onHit(h) }.padding(vertical = 6.dp)) {
                            Text(
                                (if (!h.coherent) "⚠ incohérent · " else if (h.trusted) "✔ officiel · " else "") +
                                    (if (h.pdf) "PDF · " else if (h.doc) "Word · " else "Page · ") + h.domain,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                h.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (hidden > 0) Row(
                        Modifier.fillMaxWidth().clickable { showAll = !showAll },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(showAll, { showAll = it })
                        Text("Afficher les résultats incohérents ($hidden)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (msg.steps.isNotEmpty()) {
                    Button(onSteps, Modifier.padding(top = 6.dp)) { Text("▶ Pas à pas (${msg.steps.size} étapes)") }
                }
                if (!msg.fromUser && msg.refs.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        msg.refs.forEach { r ->
                            val n = names[r.id]
                            AssistChip(onClick = { onRef(r) }, label = { Text(if (n != null) "${n.take(18)} p. ${r.page}" else "Voir p. ${r.page}") })
                        }
                    }
                }
                if (msg.engine.isNotBlank()) Text(msg.engine, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- Navigateur intégré ----------------
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(nav: NavController, startUrl: String, product: String, domain: String = Repo.D_MAN) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("Navigateur") }
    var progress by remember { mutableIntStateOf(0) }
    val busy by Repo.busy.collectAsState()

    fun grab(url: String) {
        if (Repo.busy.value != null) return
        scope.launch {
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                nav.openChat(Repo.importUrl(url, product, cookie, domain))
            } catch (e: Exception) {
                Toast.makeText(ctx, "Échec : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler {
        if (web?.canGoBack() == true) web?.goBack() else nav.popBackStack()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Ouvre un lien PDF : il sera importé automatiquement.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall
            )
            if (busy == null && progress in 1..99) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth())
            BusyBar()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                if (WebSearch.looksPdf(u)) { grab(u); return true }
                                return false
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                title = view.title ?: url.orEmpty()
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        setDownloadListener { url, _, disposition, mime, _ ->
                            if (mime?.contains("pdf", true) == true || WebSearch.looksPdf(url) ||
                                disposition?.contains(".pdf", true) == true || mime == "application/octet-stream"
                            ) grab(url)
                            else Toast.makeText(c, "Fichier non PDF ignoré", Toast.LENGTH_SHORT).show()
                        }
                        if (startUrl.isNotBlank()) loadUrl(startUrl)
                        web = this
                    }
                }
            )
        }
    }
}
