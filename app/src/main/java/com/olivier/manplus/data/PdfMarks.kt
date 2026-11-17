package com.olivier.manplus.data

import com.olivier.manplus.ai.Retriever
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Zone à surligner : page (à partir de 0) et rectangle normalisé 0..1, origine en haut à gauche. */
data class Mark(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** Entrée de sommaire : titre, page (à partir de 0), niveau d'imbrication. */
data class Toc(val title: String, val page: Int, val depth: Int = 0)

object PdfMarks {

    private class Stripper(private val terms: Set<String>) : PDFTextStripper() {
        val marks = ArrayList<Mark>()

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            val t = text ?: return
            val pos = textPositions ?: return
            if (t.isEmpty() || pos.isEmpty()) return
            val pw = pos[0].pageWidth
            val ph = pos[0].pageHeight
            if (pw <= 0f || ph <= 0f) return
            val page = currentPageNo - 1

            var i = 0
            while (i < t.length) {
                if (!t[i].isLetterOrDigit()) { i++; continue }
                var j = i
                while (j < t.length && t[j].isLetterOrDigit()) j++
                val stem = Retriever.stem(Retriever.norm(t.substring(i, j)))
                if (stem in terms) {
                    val a = pos[idx(i, t.length, pos.size)]
                    val b = pos[idx(j - 1, t.length, pos.size)]
                    val x0 = minOf(a.xDirAdj, b.xDirAdj)
                    val x1 = maxOf(a.xDirAdj + a.widthDirAdj, b.xDirAdj + b.widthDirAdj)
                    val hgt = maxOf(a.heightDir, b.heightDir)
                    val top = minOf(a.yDirAdj, b.yDirAdj) - hgt
                    if (x1 > x0 && hgt > 0f) {
                        marks += Mark(
                            page,
                            (x0 / pw).coerceIn(0f, 1f), (top / ph).coerceIn(0f, 1f),
                            ((x1 - x0) / pw).coerceIn(0f, 1f), ((hgt * 1.2f) / ph).coerceIn(0f, 1f)
                        )
                    }
                }
                i = j
            }
        }

        /** Les ligatures ou espaces ajoutés peuvent décaler texte et positions : on répartit proportionnellement. */
        private fun idx(i: Int, len: Int, n: Int): Int =
            if (len == n) i else if (len <= 1) 0 else (i.toLong() * (n - 1) / (len - 1)).toInt().coerceIn(0, n - 1)
    }

    /** Toutes les occurrences des mots de [query] dans le PDF, dans l'ordre de lecture. */
    suspend fun find(file: File, query: String): List<Mark> = withContext(Dispatchers.IO) {
        val terms = Retriever.tokens(query).toSet()
        if (terms.isEmpty()) return@withContext emptyList()
        runCatching {
            PDDocument.load(file).use { doc ->
                val s = Stripper(terms)
                s.startPage = 1
                s.endPage = doc.numberOfPages
                s.getText(doc)
                s.marks.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Signets du PDF s'il y en a. */
    suspend fun outline(file: File): List<Toc> = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(file).use { doc ->
                val root = doc.documentCatalog.documentOutline ?: return@use emptyList<Toc>()
                val out = ArrayList<Toc>()
                fun walk(first: PDOutlineItem?, depth: Int) {
                    var next: PDOutlineItem? = first
                    while (next != null) {
                        val node: PDOutlineItem = next
                        val page = runCatching { node.findDestinationPage(doc) }.getOrNull()
                        val idx = if (page != null) doc.pages.indexOf(page) else -1
                        val title = node.title?.trim().orEmpty()
                        if (idx >= 0 && title.isNotEmpty()) out += Toc(title, idx, depth)
                        walk(node.firstChild, depth + 1)
                        next = node.nextSibling
                    }
                }
                walk(root.firstChild, 0)
                out.toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Repli quand le PDF n'a pas de signets : rubriques numérotées « 1. Titre » (format des notices de médicaments).
     * Il faut au moins 3 rubriques de numéros différents, sinon on considère que ce n'est pas un vrai sommaire.
     */
    fun headings(chunks: List<Chunk>): List<Toc> {
        val re = Regex("^\\s*(\\d{1,2})\\.\\s+(\\p{Lu}[^\\n]{4,100})$", RegexOption.MULTILINE)
        val seen = HashSet<String>()
        val out = ArrayList<Toc>()
        for (c in chunks) {
            for (m in re.findAll(c.text)) {
                val title = m.groupValues[1] + ". " + m.groupValues[2].trim()
                if (seen.add(title)) out += Toc(title, c.page - 1)
            }
        }
        return if (out.map { it.title.substringBefore('.') }.toSet().size >= 3) out else emptyList()
    }

    /** Texte d'une page reconstitué à partir des morceaux (en retirant le chevauchement entre morceaux). */
    fun pageTexts(chunks: List<Chunk>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (c in chunks) {
            val prev = out[c.page]
            out[c.page] = if (prev == null) c.text else prev + "\n" + stripOverlap(prev, c.text)
        }
        return out
    }

    private fun stripOverlap(prev: String, next: String): String {
        var k = minOf(prev.length, next.length, 220)
        while (k >= 20) {
            if (prev.endsWith(next.substring(0, k))) return next.substring(k)
            k--
        }
        return next
    }
}
