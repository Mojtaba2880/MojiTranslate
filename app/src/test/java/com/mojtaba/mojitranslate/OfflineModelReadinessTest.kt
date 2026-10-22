package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineModelReadinessTest {
    @Test fun reportsSourceAndTargetPacksThatAreMissing() {
        assertEquals(listOf("en", "fa"), OfflineModelReadiness.missingLanguages(emptySet<String>(), "en", "fa"))
    }

    @Test fun reportsOnlyMissingPackWhenOneIsInstalled() {
        assertEquals(listOf("fa"), OfflineModelReadiness.missingLanguages(listOf("EN"), "en", "fa"))
    }

    @Test fun returnsNoMissingPacksWhenBothAreInstalledCaseInsensitively() {
        assertEquals(emptyList<String>(), OfflineModelReadiness.missingLanguages(listOf("EN", "FA"), "en", "fa"))
    }

    @Test fun doesNotDuplicateIdenticalSourceAndTargetLanguage() {
        assertEquals(emptyList<String>(), OfflineModelReadiness.missingLanguages(listOf("fa"), "fa", "FA"))
    }
}
