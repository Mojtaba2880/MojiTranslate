package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTokenProtectorTest {
    @Test fun protectsNumbersPercentagesAndMixedAlphaNumericCodes() {
        val source = "Deal 25% damage with UID: ABC-123 and Item7 at 2.5x."
        val protected = TranslationTokenProtector.protect(source)
        assertEquals(4, protected.protectedTokenCount)
        assertTrue(protected.text.contains("ZXQMOJITOKEN0XZQ"))
        assertTrue(protected.text.contains("ZXQMOJITOKEN1XZQ"))
        val restored = protected.restore("متن ترجمه‌شده " + protected.text)
        listOf("25%", "UID: ABC-123", "Item7", "2.5x").forEach { token ->
            assertTrue("Missing protected token: $token", restored.contains(token))
        }
    }

    @Test fun restorationAcceptsPlaceholderCaseChanges() {
        val protected = TranslationTokenProtector.protect("Use code AB-123 now")
        assertEquals("use code AB-123 now", protected.restore(protected.text.lowercase()))
    }

    @Test fun failsSafelyWhenTranslationDropsOrDuplicatesProtectedTokens() {
        val protected = TranslationTokenProtector.protect("Deal 25% damage")
        val placeholder = protected.text.substringAfter("Deal ").substringBefore(" damage")

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            protected.restore("ترجمهٔ بدون شناسه")
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            protected.restore(protected.text + " " + placeholder)
        }
    }

    @Test fun leavesOrdinaryProseUntouched() {
        val source = "Thank you for your help."
        val protected = TranslationTokenProtector.protect(source)
        assertEquals(0, protected.protectedTokenCount)
        assertEquals(source, protected.text)
        assertEquals(source, protected.restore(protected.text))
    }
}
