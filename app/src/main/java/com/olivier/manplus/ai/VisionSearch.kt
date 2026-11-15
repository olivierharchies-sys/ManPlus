package com.olivier.manplus.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.olivier.manplus.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Identification d'UN produit à partir de 1 à 4 photos (face, dos, étiquette, boîte…),
 * éventuellement complétées par des codes lus (code-barres / QR) et un indice texte.
 * Utilise Gemini cloud (multimodal) avec la clé des Réglages.
 */
object VisionSearch {
    const val MAX_PHOTOS = 4
    private const val MAX_SIDE = 1280

    data class Guess(val name: String, val reference: String, val confidence: String) {
        /** Requête de recherche de document : nom + référence si elle n'y figure pas déjà. */
        val query: String get() =
            if (reference.isBlank() || name.contains(reference, ignoreCase = true)) name else "$name $reference"
    }

    suspend fun identify(
        ctx: Context, photos: List<Uri>, medicine: Boolean, codes: List<String>, hint: String
    ): Guess = withContext(Dispatchers.IO) {
        require(photos.isNotEmpty()) { "Aucune photo" }
        val key = Settings.apiKey
        if (key.isBlank()) throw IOException("Clé API Gemini manquante (Réglages)")

        val prompt = buildString {
            append("Les images (1 à 4) montrent UN SEUL et même produit sous différents angles ")
            append("(face, dos, étiquette, boîte, notice). Identifie ce produit à partir de tout le texte ")
            append("et de tous les indices visibles sur l'ensemble des images.\n")
            if (medicine) append("C'est un médicament : donne le nom commercial, le dosage et la forme si lisibles.\n")
            else append("Donne la marque et la référence / le modèle exact (c'est ce qui sert à trouver le manuel).\n")
            if (codes.isNotEmpty()) append("Codes lus sur le produit : ${codes.joinToString(", ")}.\n")
            if (hint.isNotBlank()) append("Indice donné par l'utilisateur : $hint.\n")
            append("N'invente rien : si le produit n'est pas identifiable, laisse name vide.\n")
            append("Réponds UNIQUEMENT en JSON : {\"name\": \"marque + nom\", \"reference\": \"référence ou modèle\", ")
            append("\"confidence\": \"high|medium|low\"}")
        }

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        photos.take(MAX_PHOTOS).forEach { uri ->
            val jpeg = jpeg(ctx, uri) ?: throw IOException("Photo illisible")
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/${Settings.model}:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 90000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(resp).getJSONObject("error").getString("message") }.getOrDefault(resp.take(200))
            throw IOException("HTTP $code – $msg")
        }
        val out = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").let { arr ->
                (0 until arr.length()).joinToString("") { arr.getJSONObject(it).optString("text") }
            }
        val j = JSONObject(out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        Guess(j.optString("name").trim(), j.optString("reference").trim(), j.optString("confidence", "low"))
    }

    /** Image réduite (côté max 1280 px) en JPEG, pour limiter le poids de la requête. */
    private fun jpeg(ctx: Context, uri: Uri): ByteArray? {
        val bmp = decode(ctx, uri, MAX_SIDE) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    /** Miniature pour l'aperçu. */
    fun thumb(ctx: Context, uri: Uri, side: Int = 200): Bitmap? = decode(ctx, uri, side)

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
        val scale = maxSide.toFloat() / maxOf(raw.width, raw.height)
        if (scale >= 1f) raw else Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
    }.getOrNull()
}
