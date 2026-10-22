package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextPolicyTest {
    @Test fun preservesNumbersAndGameIdentifiers() {
        listOf("123", "12.5%", "HP: 250", "UID_ABC123", "FPS-60", "v2.1.0").forEach {
            assertTrue("Expected literal preservation for: $it", TranslationTextPolicy.shouldPreserveIdentifier(it))
        }
    }

    @Test fun doesNotPreserveOrdinarySentences() {
        listOf("Thank you for your help", "Missive of Grace", "Open the ancient door").forEach {
            assertFalse("Should translate: $it", TranslationTextPolicy.shouldPreserveIdentifier(it))
        }
    }

    @Test fun joinsTranslatedChunksWithoutBreakingPunctuation() {
        assertEquals("Hello, world! Next", TranslationTextPolicy.joinTranslatedChunks(listOf("Hello", ",", "world", "!", "Next")))
    }

    @Test fun ignoresEmptyChunks() {
        assertEquals("Alpha Beta", TranslationTextPolicy.joinTranslatedChunks(listOf("Alpha", " ", "Beta")))
    }
}
