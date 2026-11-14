package com.olivier.manplus.ai

import com.olivier.manplus.data.ChatMsg
import com.olivier.manplus.data.Chunk
import com.olivier.manplus.data.Manual
import com.olivier.manplus.data.Ref
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings
import com.olivier.manplus.data.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Extrait trouvé dans un manuel. */
data class Src(val manual: Manual, val chunk: Chunk, val score: Double) {
    val ref get() = Ref(manual.id, chunk.page)
}

object AnswerEngine {

    /** [ms] = manuels interrogés (1 seul ou tous). */
    suspend fun answer(ms: List<Manual>, q: String, history: List<ChatMsg>, onStatus: (String) -> Unit): ChatMsg {
        if (ms.isEmpty()) return ChatMsg(false, "Aucun manuel importé. Ajoute d'abord un manuel.", query = q, engine = "—")
        val multi = ms.size > 1
        val hasText = withContext(Dispatchers.IO) { ms.any { Repo.chunks(it.id).isNotEmpty() } }
        if (!hasText) return ChatMsg(
            false, "Ce PDF ne contient pas de texte exploitable (document scanné ?). Consulte le document directement.",
            query = q, engine = "—"
        )
        // question courte → on complète avec la question précédente pour la recherche
        val prev = history.dropLast(1).lastOrNull { it.fromUser }?.text
        val rq = if (Retriever.tokens(q).size < 3 && prev != null) "$q $prev" else q
        val mode = Settings.mode

        // 1. Gemini Nano
        if (mode == "AUTO" || mode == "NANO") {
            onStatus("Vérification de Gemini Nano…")
            val ready = try { NanoEngine.ensureReady(onStatus) } catch (e: Throwable) { false }
            if (ready) {
                val srcs = retrieve(ms, rq, 5)
                if (srcs.isEmpty()) return notFound(q, "Gemini Nano")
                onStatus("Gemini Nano rédige la réponse…")
                try {
                    val txt = NanoEngine.generate(prompt(q, srcs, 600, multi))
                    if (txt.isNotBlank()) return build(txt, srcs, q, "Gemini Nano", multi)
                } catch (e: Throwable) {
                    if (mode == "NANO") return ChatMsg(false, "Erreur Gemini Nano : ${e.message}", query = q, engine = "Gemini Nano")
                }
            } else if (mode == "NANO") {
                return ChatMsg(false, "Gemini Nano n'est pas disponible sur cet appareil. Choisis un autre moteur dans Réglages.", query = q, engine = "—")
            }
        }

        // 2. Gemini cloud (clé gratuite AI Studio)
        val key = Settings.apiKey
        if ((mode == "AUTO" || mode == "CLOUD") && key.isNotBlank()) {
            val srcs = retrieve(ms, rq, 10)
            if (srcs.isEmpty()) return notFound(q, "Gemini")
            onStatus("Gemini (cloud) rédige la réponse…")
            return try {
                val txt = withContext(Dispatchers.IO) { cloud(key, Settings.model, prompt(q, srcs, 1500, multi)) }
                build(txt, srcs, q, "Gemini cloud", multi)
            } catch (e: Exception) {
                if (mode == "CLOUD") ChatMsg(false, "Erreur Gemini : ${e.message}", query = q, engine = "Gemini cloud")
                else extract(ms, rq, q, multi)
            }
        } else if (mode == "CLOUD") {
            return ChatMsg(false, "Clé API Gemini manquante (Réglages).", query = q, engine = "—")
        }

        // 3. Extraits seuls
        return extract(ms, rq, q, multi)
    }

    private suspend fun retrieve(ms: List<Manual>, q: String, k: Int): List<Src> = withContext(Dispatchers.Default) {
        ms.flatMap { m -> Retriever.search(m.id, Repo.chunks(m.id), q, k).map { Src(m, it.chunk, it.score) } }
            .sortedByDescending { it.score }.take(k)
    }

    private fun notFound(q: String, engine: String) =
        ChatMsg(false, "Aucun passage des manuels ne correspond à cette question. Essaie d'autres mots-clés.", query = q, engine = engine)

    private fun label(s: Src, multi: Boolean) =
        if (multi) "${s.manual.product} p. ${s.chunk.page}" else "p. ${s.chunk.page}"

    private suspend fun extract(ms: List<Manual>, rq: String, q: String, multi: Boolean): ChatMsg {
        val srcs = retrieve(ms, rq, 3)
        if (srcs.isEmpty()) return notFound(q, "Extraits")
        val txt = buildString {
            append("Passages les plus pertinents :\n")
            srcs.forEach { s -> append("\n[${label(s, multi)}] ").append(s.chunk.text.take(500).trim()).append("…\n") }
        }
        return ChatMsg(false, txt, srcs.map { it.ref }.distinct(), q, "Extraits")
    }

    private fun prompt(q: String, srcs: List<Src>, maxPerChunk: Int, multi: Boolean) = buildString {
        append("Tu es un assistant technique. Réponds en français, de façon claire et concise, ")
        append("UNIQUEMENT à partir des extraits de manuels ci-dessous.\n")
        append("Cite la source de chaque information avec son repère entre crochets, par exemple [S2].\n")
        append("Si la question demande une procédure (comment faire, installer, régler, nettoyer, réparer, programmer…), ")
        append("réponds UNIQUEMENT par des étapes numérotées, une étape courte par ligne, au format :\n")
        append("1. action [S1]\n2. action [S3]\n")
        append("Sinon, réponds en quelques phrases.\n")
        append("Si l'information n'est pas dans les extraits, réponds : « Information non trouvée dans le manuel. »\n\n")
        append("EXTRAITS :\n")
        srcs.forEachIndexed { i, s ->
            append("[S${i + 1}] (")
            if (multi) append(s.manual.product).append(", ")
            append("p. ${s.chunk.page}) ").append(s.chunk.text.take(maxPerChunk)).append("\n---\n")
        }
        append("\nQUESTION : ").append(q).append("\nRÉPONSE :")
    }

    private val bracket = Regex("""\[([^\]\n]{1,40})]""")
    private val sTag = Regex("""S\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val stepLine = Regex("""^\s*(?:[ée]tape\s*)?(\d{1,2})\s*[.):\-–]\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** Sources citées dans un texte, dans l'ordre. */
    private fun cited(t: String, srcs: List<Src>): List<Src> =
        bracket.findAll(t).flatMap { b -> sTag.findAll(b.groupValues[1]) }
            .mapNotNull { it.groupValues[1].toIntOrNull()?.let { n -> srcs.getOrNull(n - 1) } }.toList()

    /** Remplace [S1] par [p. 12] (ou [Produit p. 12]). */
    private fun readable(t: String, srcs: List<Src>, multi: Boolean): String =
        bracket.replace(t) { b ->
            val list = sTag.findAll(b.groupValues[1])
                .mapNotNull { it.groupValues[1].toIntOrNull()?.let { n -> srcs.getOrNull(n - 1) } }
                .map { label(it, multi) }.distinct().toList()
            if (list.isEmpty()) b.value else "[" + list.joinToString(", ") + "]"
        }

    private fun build(raw: String, srcs: List<Src>, q: String, engine: String, multi: Boolean): ChatMsg {
        val clean = raw.replace("**", "").replace("__", "").trim()
        val refs = (cited(clean, srcs) + srcs).map { it.ref }.distinct().take(6)

        val steps = clean.lines().mapNotNull { line ->
            stepLine.find(line)?.let { m ->
                val body = m.groupValues[2].trim()
                val src = cited(body, srcs).firstOrNull()
                Step(readable(body, srcs, multi), src?.ref)
            }
        }
        // étape sans référence → reprend la référence précédente (ou la meilleure source)
        var last: Ref? = srcs.firstOrNull()?.ref
        val filled = steps.map { s -> (s.ref ?: last).also { last = it }.let { Step(s.text, it) } }

        return ChatMsg(
            false, readable(clean, srcs, multi), refs, q, engine,
            if (filled.size >= 2) filled else emptyList()
        )
    }

    private fun cloud(key: String, model: String, prompt: String): String {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 90000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(resp).getJSONObject("error").getString("message") }.getOrDefault(resp.take(200))
            throw IOException("HTTP $code – $msg")
        }
        val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }
}
