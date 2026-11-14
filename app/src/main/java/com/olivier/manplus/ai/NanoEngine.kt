package com.olivier.manplus.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel

/** Gemini Nano on-device via ML Kit GenAI Prompt API. */
object NanoEngine {
    private var model: GenerativeModel? = null
    private fun m(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    /** Texte lisible de l'état. */
    suspend fun statusText(): String = when (m().checkStatus()) {
        FeatureStatus.AVAILABLE -> "Disponible"
        FeatureStatus.DOWNLOADABLE -> "Téléchargeable"
        FeatureStatus.DOWNLOADING -> "Téléchargement en cours"
        else -> "Non supporté sur cet appareil"
    }

    /** true si Nano est prêt (télécharge si nécessaire). */
    suspend fun ensureReady(onStatus: (String) -> Unit): Boolean {
        return when (m().checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                var ok = false
                onStatus("Téléchargement de Gemini Nano…")
                m().download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadCompleted -> ok = true
                        is DownloadStatus.DownloadFailed -> ok = false
                        else -> onStatus("Téléchargement de Gemini Nano…")
                    }
                }
                ok
            }
            else -> false
        }
    }

    suspend fun generate(prompt: String): String {
        val r = m().generateContent(prompt)
        return r.candidates.firstOrNull()?.text.orEmpty().trim()
    }
}
