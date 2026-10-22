package com.mojtaba.mojitranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFailureClassifierTest {
    @Test fun distinguishesRateLimiting() {
        assertTrue(TranslationFailureClassifier.userMessage("Translation HTTP 429").contains("محدود"))
    }

    @Test fun distinguishesNetworkFailure() {
        assertTrue(TranslationFailureClassifier.userMessage("UnknownHostException secretText=ABC").contains("اتصال"))
    }

    @Test fun givesActionableOfflineModelMessage() {
        assertTrue(TranslationFailureClassifier.userMessage("مدل آفلاین آماده نیست").contains("مدل"))
    }

    @Test fun doesNotEchoSensitiveExceptionDetails() {
        val message = TranslationFailureClassifier.userMessage("UnknownHost secret.user.text.example")
        assertFalse(message.contains("secret.user.text"))
    }
}
