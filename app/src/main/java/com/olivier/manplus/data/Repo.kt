package com.olivier.manplus.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Manual(
    val id: String, val product: String, val title: String, val source: String,
    val pages: Int, val chunks: Int, val added: Long
) {
    fun toJson() = JSONObject().put("id", id).put("product", product).put("title", title)
        .put("source", source).put("pages", pages).put("chunks", chunks).put("added", added)

    companion object {
        fun from(o: JSONObject) = Manual(
            o.getString("id"), o.optString("product"), o.optString("title"), o.optString("source"),
            o.optInt("pages"), o.optInt("chunks"), o.optLong("added")
        )
    }
}

data class Chunk(val page: Int, val text: String)

data class ChatMsg(
    val fromUser: Boolean, val text: String,
    val pages: List<Int> = emptyList(), val query: String = "", val engine: String = ""
) {
    fun toJson() = JSONObject().put("u", fromUser).put("t", text)
        .put("p", JSONArray(pages)).put("q", query).put("e", engine)

    companion object {
        fun from(o: JSONObject): ChatMsg {
            val a = o.optJSONArray("p") ?: JSONArray()
            return ChatMsg(
                o.optBoolean("u"), o.optString("t"),
                (0 until a.length()).map { a.getInt(it) }, o.optString("q"), o.optString("e")
            )
        }
    }
}

class NotPdfException : IOException("Le lien n'est pas un PDF")

object Settings {
    private val p get() = Repo.ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var mode: String
        get() = p.getString("mode", "AUTO") ?: "AUTO"
        set(v) = p.edit().putString("mode", v).apply()
    var apiKey: String
        get() = p.getString("key", "") ?: ""
        set(v) = p.edit().putString("key", v.trim()).apply()
    var model: String
        get() = p.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) = p.edit().putString("model", v.trim()).apply()
}

object Repo {
    const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    lateinit var ctx: Context
    private lateinit var root: File
    val manuals = MutableStateFlow<List<Manual>>(emptyList())
    val busy = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunkCache = HashMap<String, List<Chunk>>()

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        PDFBoxResourceLoader.init(ctx)
        root = File(ctx.filesDir, "manuals").apply { mkdirs() }
        manuals.value = loadIndex()
    }

    private fun dir(id: String) = File(root, id)
    fun pdf(id: String) = File(dir(id), "doc.pdf")
    fun get(id: String) = manuals.value.find { it.id == id }

    // ---------- index ----------
    private fun loadIndex(): List<Manual> = try {
        val f = File(root, "index.json")
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { Manual.from(a.getJSONObject(it)) }.sortedByDescending { it.added }
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    private fun saveIndex() {
        val a = JSONArray(); manuals.value.forEach { a.put(it.toJson()) }
        File(root, "index.json").writeText(a.toString())
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        synchronized(chunkCache) { chunkCache.remove(id) }
        manuals.value = manuals.value.filterNot { it.id == id }
        saveIndex()
    }

    // ---------- import ----------
    suspend fun importUri(uri: Uri, product: String?): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        busy.value = "Copie du fichier…"
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> pdf(id).outputStream().use { i.copyTo(it) } }
            if (!isPdf(pdf(id))) throw NotPdfException()
        } catch (e: Exception) {
            dir(id).deleteRecursively(); busy.value = null; throw e
        }
        val name = displayName(uri) ?: "Document.pdf"
        index(id, product?.takeIf { it.isNotBlank() } ?: name.removeSuffix(".pdf"), name, "Téléphone")
    }

    suspend fun importUrl(url: String, product: String, cookie: String? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        busy.value = "Téléchargement…"
        try {
            fetchPdf(url, pdf(id), cookie)
        } catch (e: Exception) {
            dir(id).deleteRecursively(); busy.value = null; throw e
        }
        val name = Uri.decode(url.substringBefore('?').substringAfterLast('/')).ifBlank { "Manuel.pdf" }
        index(id, product.ifBlank { name.removeSuffix(".pdf") }, name, url)
    }

    private fun fetchPdf(start: String, dest: File, cookie: String?) {
        var u = start
        var redirects = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/pdf,*/*")
            if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirection invalide")
                u = URL(URL(u), loc).toString(); c.disconnect()
                if (++redirects > 8) throw IOException("Trop de redirections")
                continue
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            val type = c.contentType ?: ""
            if (type.contains("html", true)) { c.disconnect(); throw NotPdfException() }
            val total = c.contentLengthLong
            c.inputStream.use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        busy.value = if (total > 0) "Téléchargement ${done * 100 / total} %"
                        else "Téléchargement ${done / 1024} Ko"
                    }
                }
            }
            break
        }
        if (!isPdf(dest)) throw NotPdfException()
    }

    private fun isPdf(f: File): Boolean {
        if (!f.exists() || f.length() < 100) return false
        val head = ByteArray(1024)
        val n = f.inputStream().use { it.read(head) }
        return String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1).contains("%PDF")
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun index(id: String, product: String, title: String, source: String): String {
        try {
            busy.value = "Lecture du PDF…"
            val chunks = mutableListOf<Chunk>()
            var n = 0
            PDDocument.load(pdf(id)).use { doc ->
                n = doc.numberOfPages
                val st = PDFTextStripper()
                for (p in 1..n) {
                    busy.value = "Indexation page $p/$n"
                    st.startPage = p; st.endPage = p
                    val t = try { st.getText(doc) } catch (e: Exception) { "" }
                    chunks += Chunker.split(p, t)
                }
            }
            saveChunks(id, chunks)
            val m = Manual(id, product.trim(), title, source, n, chunks.size, System.currentTimeMillis())
            manuals.value = listOf(m) + manuals.value
            saveIndex()
            return id
        } catch (e: Exception) {
            dir(id).deleteRecursively()
            throw IOException("PDF illisible : ${e.message}")
        } finally {
            busy.value = null
        }
    }

    // ---------- chunks ----------
    private fun saveChunks(id: String, list: List<Chunk>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("p", it.page).put("t", it.text)) }
        File(dir(id), "chunks.json").writeText(a.toString())
        synchronized(chunkCache) { chunkCache[id] = list }
    }

    fun chunks(id: String): List<Chunk> {
        synchronized(chunkCache) { chunkCache[id]?.let { return it } }
        val f = File(dir(id), "chunks.json")
        val list = if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Chunk(o.getInt("p"), o.getString("t")) } }
        }
        synchronized(chunkCache) { chunkCache[id] = list }
        return list
    }

    // ---------- chat ----------
    fun loadChat(id: String): List<ChatMsg> = try {
        val f = File(dir(id), "chat.json")
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { ChatMsg.from(a.getJSONObject(it)) }
        }
    } catch (e: Exception) { emptyList() }

    fun saveChat(id: String, msgs: List<ChatMsg>) {
        val a = JSONArray(); msgs.forEach { a.put(it.toJson()) }
        try { File(dir(id), "chat.json").writeText(a.toString()) } catch (_: Exception) {}
    }
}

object Chunker {
    fun split(page: Int, raw: String, size: Int = 900, overlap: Int = 150): List<Chunk> {
        val t = raw.replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n").trim()
        if (t.length < 20) return emptyList()
        val out = mutableListOf<Chunk>()
        var start = 0
        while (start < t.length) {
            var end = minOf(t.length, start + size)
            if (end < t.length) {
                val cut = t.lastIndexOfAny(charArrayOf('\n', '.'), end)
                if (cut > start + size / 2) end = cut + 1
            }
            out += Chunk(page, t.substring(start, end).trim())
            if (end >= t.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }
}
