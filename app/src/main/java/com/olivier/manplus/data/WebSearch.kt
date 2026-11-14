package com.olivier.manplus.data

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

object WebSearch {
    data class Result(
        val title: String, val url: String, val snippet: String,
        val forcePdf: Boolean = false,
        val coherent: Boolean = true,
        val trusted: Boolean = false
    ) {
        val isPdf get() = forcePdf || looksPdf(url)
        val isDoc get() = looksDoc(url)
        val domain get() = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")
    }

    fun looksPdf(u: String) = u.lowercase().substringBefore('?').substringBefore('#').endsWith(".pdf")

    fun looksDoc(u: String): Boolean {
        val e = u.lowercase().substringBefore('?').substringBefore('#')
        return e.endsWith(".doc") || e.endsWith(".docx") || e.endsWith(".odt") || e.endsWith(".rtf")
    }

    fun lang(code: String) = LANGS.find { it.code == code } ?: LANGS.first()

    /** Sources officielles pour les médicaments (par langue) ; "all" s'applique à toutes. */
    private val MED_SITES = mapOf(
        "all" to listOf("ema.europa.eu"),
        "fr" to listOf("base-donnees-publique.medicaments.gouv.fr", "ansm.sante.fr", "pharmainfo.be", "vidal.fr"),
        "nl" to listOf("pharmainfo.be", "geneesmiddeleninformatiebank.nl", "cbip.be"),
        "en" to listOf("dailymed.nlm.nih.gov", "medicines.org.uk", "mhra.gov.uk", "pharmainfo.be"),
        "de" to listOf("pharmnet-bund.de", "gelbe-liste.de", "swissmedicinfo.ch"),
        "es" to listOf("cima.aemps.es"),
        "it" to listOf("farmaci.agenziafarmaco.gov.it")
    )
    private val ALL_MED_SITES = MED_SITES.values.flatten().toSet()

    private fun medSites(code: String) = ((MED_SITES[code] ?: emptyList()) + MED_SITES.getValue("all")).distinct()

    /** Mots-clés (principal, secondaire) selon le type choisi ou le texte libre. */
    fun keywords(code: String, type: DocType?, custom: String): Pair<String, String> {
        val l = lang(code)
        val c = custom.trim()
        return when {
            c.isNotEmpty() -> c to c
            type == null || type.id == "manual" -> l.kw1 to l.kw2
            else -> type.keyword(code).let { it to it }
        }
    }

    fun googleUrl(subject: String, code: String, type: DocType?, custom: String, medicine: Boolean): String {
        val l = lang(code)
        val k = keywords(code, type, custom).first
        val q = if (medicine) "$subject $k" else "$subject $k pdf"
        return "https://www.google.com/search?hl=${l.code}&lr=lang_${l.code}&q=" + URLEncoder.encode(q, "UTF-8")
    }

    suspend fun search(
        subject: String, code: String,
        type: DocType? = null, custom: String = "", medicine: Boolean = false, format: String = "pdf"
    ): List<Result> = withContext(Dispatchers.IO) {
        val l = lang(code)
        val (k1, k2) = keywords(code, type, custom)
        val qs = mutableListOf<String>()
        val fileFilter = when (format) {
            "pdf" -> " filetype:pdf"
            "doc" -> " (filetype:doc OR filetype:docx)"
            else -> ""
        }
        val suffix = when (format) { "pdf" -> " pdf"; "doc" -> " doc"; else -> "" }
        qs += "$subject $k1$fileFilter"
        qs += "$subject ${if (k2 != k1) k2 else k1}$suffix"
        qs += "$subject $k1"
        if (medicine) {
            val sites = medSites(code).take(5).joinToString(" OR ") { "site:$it" }
            qs += "$subject $k1 ($sites)"
        }
        val all = coroutineScope {
            val jobs = qs.map { q -> async { runCatching { bing(q, l.code) }.getOrDefault(emptyList()) } } +
                async { runCatching { ddg("$subject $k1" + if (medicine) "" else suffix, l.ddg) }.getOrDefault(emptyList()) } +
                async { if (medicine && code == "en" && format != "doc") runCatching { dailyMed(subject) }.getOrDefault(emptyList()) else emptyList() }
            jobs.awaitAll().flatten()
        }
        val tokens = tokens(subject, medicine)
        val joined = squash(subject)
        all.distinctBy { it.url.substringBefore('#') }
            .map { r ->
                r.copy(
                    coherent = isCoherent(r, tokens, joined, medicine),
                    trusted = medicine && ALL_MED_SITES.any { d -> r.domain == d || r.domain.endsWith(".$d") }
                )
            }
            .sortedWith(
                compareByDescending<Result> { it.coherent }
                    .thenByDescending { it.trusted }
                    .thenByDescending { if (format == "doc") it.isDoc else if (format == "pdf") it.isPdf else false }
            )
            .take(60)
    }

    // ---------- cohérence des résultats ----------
    private val STOP = setOf("de", "du", "des", "la", "le", "les", "et", "pour", "the", "for", "and", "of", "a", "un", "une")
    private val UNITS = setOf(
        "mg", "g", "kg", "ml", "mcg", "ug", "µg", "ui", "iu", "cp", "comprime", "comprimes", "tablet", "tablets",
        "gelule", "gelules", "capsule", "capsules", "sirop", "solution", "sachet", "sachets", "gel", "creme", "pommade"
    )

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun squash(s: String) = norm(s).replace(Regex("[^a-z0-9]"), "")

    private fun tokens(subject: String, medicine: Boolean): List<String> =
        norm(subject).split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP }
            .filter { !medicine || (it !in UNITS && !it.any { c -> c.isDigit() }) }
            .distinct()

    private fun hit(t: String, hay: String, squashed: String): Boolean =
        if (t.length < 3) Regex("(^|[^a-z0-9])" + Regex.escape(t) + "($|[^a-z0-9])").containsMatchIn(hay)
        else hay.contains(t) || squashed.contains(t)

    private fun isCoherent(r: Result, tokens: List<String>, joined: String, medicine: Boolean): Boolean {
        if (tokens.isEmpty()) return true
        val decodedUrl = runCatching { URLDecoder.decode(r.url, "UTF-8") }.getOrDefault(r.url)
        val hay = norm(r.title + " " + decodedUrl + " " + r.snippet)
        val sq = hay.replace(Regex("[^a-z0-9]"), "")
        if (joined.length >= 4 && sq.contains(joined)) return true
        val hits = tokens.count { hit(it, hay, sq) }
        val enough = if (tokens.size <= 2) hits == tokens.size else hits * 10 >= tokens.size * 6
        return enough && (!medicine || hit(tokens.first(), hay, sq))
    }

    /** Notices américaines (FDA) via l'API publique DailyMed. */
    private fun dailyMed(name: String): List<Result> {
        val j = JSONObject(get("https://dailymed.nlm.nih.gov/dailymed/services/v2/spls.json?pagesize=10&drug_name=" +
            URLEncoder.encode(name, "UTF-8")))
        val a = j.optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("setid"); if (id.isBlank()) return@mapNotNull null
            Result(
                o.optString("title").trim().ifBlank { name },
                "https://dailymed.nlm.nih.gov/dailymed/downloadpdffile.cfm?setId=$id",
                "DailyMed (FDA)", forcePdf = true
            )
        }
    }

    /** Nom du produit à partir d'un EAN/UPC (Open Products Facts, puis UPCitemdb gratuit). */
    suspend fun productName(code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://world.openproductsfacts.org/api/v2/product/$code.json"))
            if (j.optInt("status") != 1) null else {
                val p = j.getJSONObject("product")
                val brand = p.optString("brands").substringBefore(',').trim()
                val name = p.optString("product_name").trim()
                listOf(brand, name).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null }
            }
        }.getOrNull() ?: runCatching {
            val it0 = JSONObject(get("https://api.upcitemdb.com/prod/trial/lookup?upc=$code"))
                .optJSONArray("items")?.optJSONObject(0) ?: return@runCatching null
            val brand = it0.optString("brand").trim()
            val model = it0.optString("model").trim()
            val title = it0.optString("title").trim()
            when {
                model.isNotBlank() -> "$brand $model".trim()
                title.isNotBlank() -> title.take(80)
                else -> null
            }
        }.getOrNull()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", Repo.UA)
        c.setRequestProperty("Accept-Language", "fr-BE,fr;q=0.9,en;q=0.8")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun bing(q: String, code: String): List<Result> {
        val xml = get("https://www.bing.com/search?format=rss&setlang=$code&q=" + URLEncoder.encode(q, "UTF-8"))
        val out = mutableListOf<Result>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var inItem = false; var tag = ""
        var title = ""; var link = ""; var desc = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; title = ""; link = ""; desc = "" }
                }
                XmlPullParser.TEXT -> if (inItem) when (tag) {
                    "title" -> title += p.text
                    "link" -> link += p.text
                    "description" -> desc += p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        if (link.startsWith("http")) out += Result(clean(title), link.trim(), clean(desc))
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun ddg(q: String, kl: String): List<Result> {
        val html = get("https://html.duckduckgo.com/html/?kl=$kl&q=" + URLEncoder.encode(q, "UTF-8"))
        val out = mutableListOf<Result>()
        val re = Regex("<a([^>]*class=\"result__a\"[^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(html)) {
            var href = Regex("href=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            href = href.replace("&amp;", "&")
            if (href.contains("uddg=")) href = URLDecoder.decode(href.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) continue
            out += Result(clean(m.groupValues[2]), href, "")
        }
        return out
    }
}
