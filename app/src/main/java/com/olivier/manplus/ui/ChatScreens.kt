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
import kotlinx.coroutines.launch

// ---------------- Chat ----------------
@Composable
fun ChatScreen(nav: NavController, id: String, ask: String = "") {
    val manuals by Repo.manuals.collectAsState()
    val global = id == Repo.ALL
    val m = manuals.find { it.id == id }
    if (!global && m == null) return
    val scope_ = if (global) manuals else listOfNotNull(m)
    val names = remember(manuals) { manuals.associate { it.id to it.product } }
    val msgs = remember(id) { mutableStateListOf<ChatMsg>().apply { addAll(Repo.loadChat(id)) } }
    var input by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var stepsOf by remember { mutableStateOf<ChatMsg?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val clip = LocalClipboardManager.current

    LaunchedEffect(msgs.size, status) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || status != null) return
        input = ""
        msgs += ChatMsg(true, q)
        Repo.saveChat(id, msgs.toList())
        status = "Recherche dans " + (if (global) "tous les manuels…" else "le manuel…")
        scope.launch {
            val r = try {
                AnswerEngine.answer(scope_, q, msgs.toList()) { status = it }
            } catch (e: Exception) {
                ChatMsg(false, "Erreur : ${e.message}", query = q)
            }
            status = null
            msgs += r
            Repo.saveChat(id, msgs.toList())
            if (r.steps.isNotEmpty()) stepsOf = r
        }
    }

    LaunchedEffect(ask) {
        if (ask.isNotBlank() && !asked) { asked = true; input = ask; send() }
    }

    fun openRef(ref: Ref, q: String) = nav.navigate("viewer/${ref.id}?page=${ref.page}&q=${Uri.encode(q)}")

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(if (global) "Assistant – tous les manuels" else m!!.product, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = {
                if (!global) IconButton({ nav.navigate("viewer/$id") }) { Icon(Icons.AutoMirrored.Filled.List, "Document") }
                IconButton({ msgs.clear(); Repo.saveChat(id, emptyList()) }) { Icon(Icons.Default.Delete, "Effacer") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (msgs.isEmpty()) item {
                    Text(
                        (if (global) "Pose une question : la recherche porte sur tes ${manuals.size} manuels.\n"
                        else "Pose une question sur « ${m!!.product} » (${m.pages} pages).\n") +
                            "Ex. : Comment détartrer ? Que signifie l'erreur E3 ?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                items(msgs) { msg ->
                    Bubble(
                        msg, if (global) names else emptyMap(),
                        onRef = { openRef(it, msg.query) },
                        onSteps = { stepsOf = msg }
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
                    placeholder = { Text("Ta question… (ou colle un texte)") }, maxLines = 6,
                    trailingIcon = {
                        TextButton({ clip.getText()?.text?.let { input = if (input.isBlank()) it else "$input $it" } }) { Text("Coller") }
                    }
                )
                IconButton({ send() }, enabled = status == null && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Envoyer")
                }
            }
        }
    }

    stepsOf?.let { msg ->
        StepDialog(msg.steps, if (global) names else emptyMap(), onDismiss = { stepsOf = null }) { step ->
            stepsOf = null
            step.ref?.let { openRef(it, step.text) }
        }
    }
}

@Composable
private fun Bubble(msg: ChatMsg, names: Map<String, String>, onRef: (Ref) -> Unit, onSteps: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer { Text(msg.text) }
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
fun BrowserScreen(nav: NavController, startUrl: String, product: String) {
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
                nav.openChat(Repo.importUrl(url, product, cookie))
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
