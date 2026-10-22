package com.mojtaba.mojitranslate

/** Computes local translation-pack readiness without initiating any downloads. */
object OfflineModelReadiness {
    fun missingLanguages(
        downloadedLanguages: Collection<String>,
        sourceLanguage: String,
        targetLanguage: String = "fa"
    ): List<String> {
        val downloaded = downloadedLanguages.map { it.trim().lowercase() }.toSet()
        return listOf(sourceLanguage.trim().lowercase(), targetLanguage.trim().lowercase())
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { it in downloaded }
    }
}
