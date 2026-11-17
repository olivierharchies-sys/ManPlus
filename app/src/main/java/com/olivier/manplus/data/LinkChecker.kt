package com.olivier.manplus.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Vérifie qu'un lien répond avant de le proposer.
 *
 * Un lien n'est écarté que s'il est CLAIREMENT mort (404/410, domaine introuvable, connexion refusée).
 * Un refus ambigu (403, 429, délai dépassé…) est fréquent pour les sites qui bloquent les robots :
 * le lien reste alors proposé, marqué « non vérifié ».
 */
object LinkChecker {
    enum class State { OK, DEAD, UNKNOWN }

    data class Check(
        val state: State,
        val code: Int = 0,
        val contentType: String = "",
        /** Date de dernière modification annoncée par le serveur (ms), 0 si inconnue. */
        val modified: Long = 0L
    )

    private val gate = Semaphore(8)

    /** Vérifie une liste d'URL en parallèle ; les liens non traités à temps restent UNKNOWN. */
    suspend fun checkAll(urls: List<String>, totalBudgetMs: Long = 15_000): Map<String, Check> {
        val out = java.util.concurrent.ConcurrentHashMap<String, Check>()
        withTimeoutOrNull(totalBudgetMs) {
            coroutineScope {
                urls.map { u -> async { out[u] = check(u) } }.awaitAll()
            }
        }
        return urls.associateWith { out[it] ?: Check(State.UNKNOWN) }
    }

    suspend fun check(url: String, timeoutMs: Int = 5000): Check = withContext(Dispatchers.IO) {
        gate.withPermit {
            try {
                var r = probe(url, "HEAD", timeoutMs)
                // Beaucoup de serveurs refusent HEAD : on retente avec un GET d'un seul octet.
                if (r.code in setOf(400, 403, 405, 501)) r = probe(url, "GET", timeoutMs)
                classify(r)
            } catch (e: UnknownHostException) {
                Check(State.DEAD)
            } catch (e: ConnectException) {
                Check(State.DEAD)
            } catch (e: SocketTimeoutException) {
                Check(State.UNKNOWN)
            } catch (e: Exception) {
                Check(State.UNKNOWN)
            }
        }
    }

    private fun classify(r: Check): Check = when (r.code) {
        in 200..299 -> r.copy(state = State.OK)
        404, 410 -> r.copy(state = State.DEAD)
        else -> r.copy(state = State.UNKNOWN)
    }

    private fun probe(start: String, method: String, timeoutMs: Int): Check {
        var u = start
        var redirects = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.requestMethod = method
                c.setRequestProperty("User-Agent", Repo.UA)
                c.setRequestProperty("Accept", "*/*")
                if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirection invalide")
                    u = URL(URL(u), loc).toString()
                    if (++redirects > 6) throw IOException("Trop de redirections")
                    continue
                }
                return Check(State.UNKNOWN, code, c.contentType ?: "", c.lastModified)
            } finally {
                c.disconnect()
            }
        }
    }
}
