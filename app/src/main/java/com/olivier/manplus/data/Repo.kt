package com.olivier.manplus.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    val pages: Int, val chunks: Int, val added: Long, val domain: String = Repo.D_MAN
) {
    fun toJson() = JSONObject().put("id", id).put("product", product).put("title", title)
        .put("source", source).put("pages", pages).put("chunks", chunks).put("added", added)
        .put("domain", domain)

    companion object {
        fun from(o: JSONObject) = Manual(
            o.getString("id"), o.optString("product"), o.optString("title"), o.optString("source"),
            o.optInt("pages"), o.optInt("chunks"), o.optLong("added"),
            o.optString("domain").ifBlank { Repo.D_MAN }
        )
    }
}

data class Chunk(val page: Int, val text: String)

/** Référence vers une page d'un manuel. */
data class Ref(val id: String, val page: Int) {
    fun toJson() = JSONObject().put("i", id).put("p", page)
    companion object { fun from(o: JSONObject) = Ref(o.getString("i"), o.getInt("p")) }
}

data class Step(val text: String, val ref: Ref?)

/** Résultat de recherche internet affiché dans un chat. */
data class ChatHit(
    val title: String, val url: String, val domain: String,
    val pdf: Boolean, val doc: Boolean, val coherent: Boolean, val trusted: Boolean
) {
    fun toJson() = JSONObject().put("t", title).put("u", url).put("d", domain)
        .put("p", pdf).put("w", doc).put("c", coherent).put("o", trusted)

    companion object {
        fun from(o: JSONObject) = ChatHit(
            o.optString("t"), o.optString("u"), o.optString("d"),
            o.optBoolean("p"), o.optBoolean("w"), o.optBoolean("c", true), o.optBoolean("o")
        )
    }
}

data class ChatMsg(
    val fromUser: Boolean, val text: String,
    val refs: List<Ref> = emptyList(), val query: String = "", val engine: String = "",
    val steps: List<Step> = emptyList(), val hits: List<ChatHit> = emptyList()
) {
    fun toJson(): JSONObject {
        val h = JSONArray(); hits.forEach { h.put(it.toJson()) }
        val r = JSONArray(); refs.forEach { r.put(it.toJson()) }
        val s = JSONArray()
        steps.forEach { st -> s.put(JSONObject().put("t", st.text).apply { st.ref?.let { put("r", it.toJson()) } }) }
        return JSONObject().put("u", fromUser).put("t", text).put("r", r)
            .put("q", query).put("e", engine).put("s", s).put("h", h)
    }

    companion object {
        fun from(o: JSONObject, defId: String): ChatMsg {
            val refs = o.optJSONArray("r")?.let { a -> (0 until a.length()).map { Ref.from(a.getJSONObject(it)) } }
                ?: o.optJSONArray("p")?.let { a ->
                    if (Repo.isScope(defId)) emptyList() else (0 until a.length()).map { Ref(defId, a.getInt(it)) }
                } ?: emptyList()
            val steps = o.optJSONArray("s")?.let { a ->
                (0 until a.length()).map { i ->
                    val so = a.getJSONObject(i)
                    Step(so.optString("t"), so.optJSONObject("r")?.let { Ref.from(it) })
                }
            } ?: emptyList()
            val hits = o.optJSONArray("h")?.let { a -> (0 until a.length()).map { ChatHit.from(a.getJSONObject(it)) } }
                ?: emptyList()
            return ChatMsg(o.optBoolean("u"), o.optString("t"), refs, o.optString("q"), o.optString("e"), steps, hits)
        }
    }
}

data class Lang(val code: String, val label: String, val kw1: String, val kw2: String, val ddg: String)

val LANGS = listOf(
    Lang("fr", "Français", "manuel utilisateur", "notice mode d'emploi", "fr-fr"),
    Lang("en", "English", "user manual", "instruction manual", "us-en"),
    Lang("nl", "Nederlands", "handleiding", "gebruiksaanwijzing", "nl-nl"),
    Lang("de", "Deutsch", "Bedienungsanleitung", "Handbuch", "de-de"),
    Lang("es", "Español", "manual de usuario", "instrucciones", "es-es"),
    Lang("it", "Italiano", "manuale d'uso", "istruzioni", "it-it")
)

/**
 * Type de document recherché. [kw] = mots-clés par code de langue (repli : "en").
 * [id] "custom" = texte libre saisi par la personne.
 */
data class DocType(val id: String, val label: String, val medicine: Boolean, val kw: Map<String, String>) {
    fun keyword(code: String): String = kw[code] ?: kw["en"] ?: ""
}

val DOC_TYPES = listOf(
    DocType("manual", "Manuel / notice", false, emptyMap()), // utilise Lang.kw1 / kw2
    DocType("quick", "Guide rapide", false, mapOf(
        "fr" to "guide de démarrage rapide", "en" to "quick start guide", "nl" to "snelstartgids",
        "de" to "Kurzanleitung", "es" to "guía de inicio rápido", "it" to "guida rapida")),
    DocType("install", "Installation / montage", false, mapOf(
        "fr" to "guide d'installation", "en" to "installation guide", "nl" to "installatiehandleiding",
        "de" to "Installationsanleitung", "es" to "guía de instalación", "it" to "guida all'installazione")),
    DocType("datasheet", "Fiche technique", false, mapOf(
        "fr" to "fiche technique", "en" to "datasheet", "nl" to "technische fiche",
        "de" to "Datenblatt", "es" to "ficha técnica", "it" to "scheda tecnica")),
    DocType("service", "Réparation / service", false, mapOf(
        "fr" to "manuel de réparation", "en" to "service manual", "nl" to "servicehandleiding",
        "de" to "Servicehandbuch", "es" to "manual de servicio", "it" to "manuale di servizio")),
    DocType("parts", "Pièces détachées", false, mapOf(
        "fr" to "pièces détachées schéma", "en" to "parts diagram", "nl" to "onderdelentekening",
        "de" to "Explosionszeichnung", "es" to "despiece", "it" to "esploso")),
    DocType("warranty", "Garantie", false, mapOf(
        "fr" to "garantie", "en" to "warranty", "nl" to "garantie",
        "de" to "Garantie", "es" to "garantía", "it" to "garanzia")),
    DocType("safety", "Fiche de sécurité", false, mapOf(
        "fr" to "fiche de données de sécurité", "en" to "safety data sheet", "nl" to "veiligheidsinformatieblad",
        "de" to "Sicherheitsdatenblatt", "es" to "hoja de datos de seguridad", "it" to "scheda di sicurezza")),
    DocType("catalog", "Catalogue / brochure", false, mapOf(
        "fr" to "catalogue brochure", "en" to "catalog brochure", "nl" to "catalogus brochure",
        "de" to "Katalog Prospekt", "es" to "catálogo folleto", "it" to "catalogo brochure")),
    // --- médicaments ---
    DocType("leaflet", "Notice patient", true, mapOf(
        "fr" to "notice patient", "en" to "package leaflet", "nl" to "bijsluiter",
        "de" to "Packungsbeilage", "es" to "prospecto", "it" to "foglio illustrativo")),
    DocType("smpc", "RCP (professionnels)", true, mapOf(
        "fr" to "résumé des caractéristiques du produit", "en" to "summary of product characteristics",
        "nl" to "samenvatting van de productkenmerken", "de" to "Fachinformation",
        "es" to "ficha técnica", "it" to "riassunto delle caratteristiche del prodotto"))
)

const val CUSTOM_TYPE = "custom"

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
    var lang: String
        get() = p.getString("lang", "fr") ?: "fr"
        set(v) = p.edit().putString("lang", v).apply()
    /** Format de fichier cherché (onglet Manuel) : pdf, doc ou all. Ancienne clé « format » reprise par défaut. */
    var formatMan: String
        get() = p.getString("formatMan", p.getString("format", "pdf")) ?: "pdf"
        set(v) = p.edit().putString("formatMan", v).apply()
    /** Format de fichier cherché (onglet Médicament). */
    var formatMed: String
        get() = p.getString("formatMed", p.getString("format", "pdf")) ?: "pdf"
        set(v) = p.edit().putString("formatMed", v).apply()
    var typeMan: String
        get() = p.getString("typeMan", "manual") ?: "manual"
        set(v) = p.edit().putString("typeMan", v).apply()
    var typeMed: String
        get() = p.getString("typeMed", "leaflet") ?: "leaflet"
        set(v) = p.edit().putString("typeMed", v).apply()
    var customMan: String
        get() = p.getString("customMan", "") ?: ""
        set(v) = p.edit().putString("customMan", v).apply()
    var customMed: String
        get() = p.getString("customMed", "") ?: ""
        set(v) = p.edit().putString("customMed", v).apply()
}

object Repo {
    const val ALL = "ALL"   // chat général (tous les documents)
    const val MED = "MED"   // chat de l'onglet Médicament
    const val MAN = "MAN"   // chat de l'onglet Manuel
    const val D_MAN = "manual"
    const val D_MED = "medicine"

    fun isScope(id: String) = id == ALL || id == MED || id == MAN

    /** Documents couverts par un chat : tous, ceux d'un domaine, ou un seul document. */
    fun docsFor(id: String, all: List<Manual>): List<Manual> = when (id) {
        ALL -> all
        MED -> all.filter { it.domain == D_MED }
        MAN -> all.filter { it.domain != D_MED }
        else -> all.filter { it.id == id }
    }
    const val UA ="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

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
    suspend fun importUri(uri: Uri, product: String?, domain: String = D_MAN): String = withContext(Dispatchers.IO) {
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
        index(id, product?.takeIf { it.isNotBlank() } ?: name.removeSuffix(".pdf"), name, "Téléphone", domain)
    }

    suspend fun importUrl(url: String, product: String, cookie: String? = null, domain: String = D_MAN): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dir(id).mkdirs()
        busy.value = "Téléchargement…"
        try {
            fetchPdf(url, pdf(id), cookie)
        } catch (e: Exception) {
            dir(id).deleteRecursively(); busy.value = null; throw e
        }
        val name = Uri.decode(url.substringBefore('?').substringAfterLast('/')).ifBlank { "Manuel.pdf" }
        index(id, product.ifBlank { name.removeSuffix(".pdf") }, name, url, domain)
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

    private fun index(id: String, product: String, title: String, source: String, domain: String): String {
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
            val m = Manual(id, product.trim(), title, source, n, chunks.size, System.currentTimeMillis(), domain)
            manuals.value = listOf(m) + manuals.value
            saveIndex()
            exportCopy(id, product)
            return id
        } catch (e: Exception) {
            dir(id).deleteRecursively()
            throw IOException("PDF illisible : ${e.message}")
        } finally {
            busy.value = null
        }
    }

    // ---------- copie dans Documents/ManPlus ----------
    const val PUBLIC_DIR = "ManPlus"

    /**
     * Copie le PDF dans le dossier public Documents/ManPlus (visible depuis les Fichiers).
     * Échec silencieux : la copie interne de l'appli reste la référence.
     */
    private fun exportCopy(id: String, product: String) {
        try {
            val name = product.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(80)
                .ifBlank { "Document" } + ".pdf"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + PUBLIC_DIR)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), v) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { o -> pdf(id).inputStream().use { it.copyTo(o) } }
            } else {
                // Android 8-9 : écriture directe (nécessite l'autorisation de stockage, sinon ignoré)
                val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), PUBLIC_DIR)
                d.mkdirs()
                pdf(id).copyTo(File(d, name), overwrite = true)
            }
        } catch (_: Exception) {}
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
            (0 until a.length()).map { ChatMsg.from(a.getJSONObject(it), id) }
        }
    } catch (e: Exception) { emptyList() }

    fun saveChat(id: String, msgs: List<ChatMsg>) {
        val a = JSONArray(); msgs.forEach { a.put(it.toJson()) }
        try { dir(id).mkdirs(); File(dir(id), "chat.json").writeText(a.toString()) } catch (_: Exception) {}
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
