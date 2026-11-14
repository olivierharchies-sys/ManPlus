package com.olivier.manplus.ai

import com.olivier.manplus.data.ChatMsg
import com.olivier.manplus.data.Manual
import com.olivier.manplus.data.Repo
import com.olivier.manplus.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AnswerEngine {

    suspend fun answer(m: Manual, q: String, history: List<ChatMsg>, onStatus: (String) -> Unit): ChatMsg {
        val chunks = withContext(Dispatchers.IO) { Repo.chunks(m.id) }
        if (chunks.isEmpty()) return ChatMsg(
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
                val hits = withContext(Dispatchers.Default) { Retriever.search(m.id, chunks, rq, 5) }
                if (hits.isEmpty()) return notFound(q, "Gemini Nano")
                onStatus("Gemini Nano rédige la réponse…")
                try {
                    val txt = NanoEngine.generate(prompt(m, q, hits, 650))
                    if (txt.isNotBlank()) return build(txt, hits, q, "Gemini Nano")
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
            val hits = withContext(Dispatchers.Default) { Retriever.search(m.id, chunks, rq, 10) }
            if (hits.isEmpty()) return notFound(q, "Gemini")
            onStatus("Gemini (cloud) rédige la réponse…")
            return try {
                val txt = withContext(Dispatchers.IO) { cloud(key, Settings.model, prompt(m, q, hits, 1500)) }
                build(txt, hits, q, "Gemini cloud")
            } catch (e: Exception) {
                if (mode == "CLOUD") ChatMsg(false, "Erreur Gemini : ${e.message}", query = q, engine = "Gemini cloud")
                else extract(rq, q, m, chunks.size)
            }
        } else if (mode == "CLOUD") {
            return ChatMsg(false, "Clé API Gemini manquante (Réglages).", query = q, engine = "—")
        }

        // 3. Extraits seuls
        return extract(rq, q, m, chunks.size)
    }

    private fun notFound(q: String, engine: String) =
        ChatMsg(false, "Aucun passage du manuel ne correspond à cette question. Essaie d'autres mots-clés.", query = q, engine = engine)

    private suspend fun extract(rq: String, q: String, m: Manual, n: Int): ChatMsg {
        val hits = withContext(Dispatchers.Default) { Retriever.search(m.id, Repo.chunks(m.id), rq, 3) }
        if (hits.isEmpty()) return notFound(q, "Extraits")
        val txt = buildString {
            append("Passages les plus pertinents du manuel :\n")
            hits.forEach { h -> append("\n[p. ${h.chunk.page}] ").append(h.chunk.text.take(500).trim()).append("…\n") }
        }
        return ChatMsg(false, txt, hits.map { it.chunk.page }.distinct(), q, "Extraits")
    }

    private fun prompt(m: Manual, q: String, hits: List<Hit>, maxPerChunk: Int) = buildString {
        append("Tu es un assistant technique. Réponds en français, de façon claire et concise, ")
        append("UNIQUEMENT à partir des extraits du manuel « ${m.product} » ci-dessous. ")
        append("Indique la page source entre crochets, par exemple [p. 12]. ")
        append("Si la réponse n'est pas dans les extraits, réponds : « Information non trouvée dans le manuel. »\n\n")
        append("EXTRAITS :\n")
        hits.sortedBy { it.chunk.page }.forEach { h ->
            append("[p. ${h.chunk.page}] ").append(h.chunk.text.take(maxPerChunk)).append("\n---\n")
        }
        append("\nQUESTION : ").append(q).append("\nRÉPONSE :")
    }

    private fun build(raw: String, hits: List<Hit>, q: String, engine: String): ChatMsg {
        val txt = raw.replace("**", "").trim()
        val cited = Regex("""\[\s*p(?:age|\.)?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .findAll(txt).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val hitPages = hits.map { it.chunk.page }
        val pages = (cited.filter { it in hitPages } + hitPages).distinct().take(5)
        return ChatMsg(false, txt, pages, q, engine)
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
