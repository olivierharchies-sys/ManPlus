package com.olivier.manplus.data

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object WebSearch {
    data class Result(val title: String, val url: String, val snippet: String) {
        val isPdf get() = looksPdf(url)
        val domain get() = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")
    }

    fun looksPdf(u: String) = u.lowercase().substringBefore('?').substringBefore('#').endsWith(".pdf")

    fun lang(code: String) = LANGS.find { it.code == code } ?: LANGS.first()

    fun googleUrl(product: String, code: String): String {
        val l = lang(code)
        return "https://www.google.com/search?hl=${l.code}&lr=lang_${l.code}&q=" +
            URLEncoder.encode("$product ${l.kw1} pdf", "UTF-8")
    }

    suspend fun search(product: String, code: String): List<Result> = withContext(Dispatchers.IO) {
        val l = lang(code)
        val qs = listOf(
            "$product ${l.kw1} filetype:pdf",
            "$product ${l.kw2} pdf",
            "$product ${l.kw1}"
        )
        val all = coroutineScope {
            val jobs = qs.map { q -> async { runCatching { bing(q, l.code) }.getOrDefault(emptyList()) } } +
                async { runCatching { ddg("$product ${l.kw1} pdf", l.ddg) }.getOrDefault(emptyList()) }
            jobs.awaitAll().flatten()
        }
        all.distinctBy { it.url.substringBefore('#') }
            .sortedByDescending { it.isPdf }
            .take(40)
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
