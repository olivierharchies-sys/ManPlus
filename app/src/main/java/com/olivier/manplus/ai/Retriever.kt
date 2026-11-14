package com.olivier.manplus.ai

import com.olivier.manplus.data.Chunk
import java.text.Normalizer
import kotlin.math.ln

data class Hit(val index: Int, val chunk: Chunk, val score: Double)

/** Recherche BM25 locale sur les morceaux du manuel. */
object Retriever {
    private val stop = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "d", "l", "et", "ou", "en", "au", "aux", "a",
        "ce", "ces", "cet", "cette", "se", "sa", "son", "ses", "sur", "pour", "par", "dans", "avec", "sans",
        "est", "sont", "il", "elle", "on", "je", "tu", "nous", "vous", "ils", "qui", "que", "quoi", "quel",
        "quelle", "quels", "quelles", "comment", "pourquoi", "quand", "ne", "pas", "plus", "mon", "ma", "mes",
        "faut", "faire", "peut", "peux", "dois", "doit", "y", "t", "s", "c", "j", "n", "m", "qu",
        "the", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "it", "this", "that", "with",
        "how", "what", "why", "when", "do", "does", "can", "my", "be", "by", "as", "at", "from", "not"
    )

    private class Index(val tfs: List<Map<String, Int>>, val lens: IntArray, val df: Map<String, Int>, val avg: Double)

    private val cache = HashMap<String, Pair<Int, Index>>()

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun stem(w: String) = if (w.length > 6) w.take(6) else w.removeSuffix("s")

    fun tokens(s: String): List<String> =
        norm(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in stop }.map { stem(it) }

    private fun index(id: String, chunks: List<Chunk>): Index = synchronized(cache) {
        cache[id]?.let { if (it.first == chunks.size) return it.second }
        val tfs = chunks.map { c -> tokens(c.text).groupingBy { it }.eachCount() }
        val lens = IntArray(tfs.size) { tfs[it].values.sum() }
        val df = HashMap<String, Int>()
        tfs.forEach { m -> m.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val avg = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
        Index(tfs, lens, df, avg).also { cache[id] = chunks.size to it }
    }

    fun search(id: String, chunks: List<Chunk>, query: String, k: Int): List<Hit> {
        if (chunks.isEmpty()) return emptyList()
        val q = tokens(query).distinct()
        val ix = index(id, chunks)
        val n = chunks.size.toDouble()
        val k1 = 1.4; val b = 0.75
        val scored = chunks.indices.map { i ->
            var s = 0.0
            val tf = ix.tfs[i]
            var matched = 0
            for (t in q) {
                val f = tf[t] ?: continue
                matched++
                val d = ix.df[t] ?: 0
                val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
                s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * ix.lens[i] / ix.avg))
            }
            // bonus si plusieurs termes de la question présents
            if (q.size > 1) s *= 1 + 0.3 * matched / q.size
            Hit(i, chunks[i], s)
        }
        val best = scored.filter { it.score > 0 }.sortedByDescending { it.score }.take(k)
        return best
    }

    fun bestOnPage(id: String, chunks: List<Chunk>, page: Int, query: String): Chunk? {
        val onPage = chunks.withIndex().filter { it.value.page == page }
        if (onPage.isEmpty()) return null
        val hits = search(id, chunks, query, chunks.size).filter { it.chunk.page == page }
        return hits.firstOrNull()?.chunk ?: onPage.first().value
    }
}
