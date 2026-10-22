package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OcrLineLayoutTest {
    @Test
    fun returnsLinesInTopThenLeftReadingOrder() {
        val right = OcrLineCandidate("right", 100, 10, 150, 30)
        val lower = OcrLineCandidate("lower", 10, 60, 80, 80)
        val left = OcrLineCandidate("left", 10, 10, 60, 30)

        assertEquals(listOf(left, right, lower), OcrLineLayout.mergeAdjacent(listOf(lower, right, left)))
    }

    @Test
    fun joinsNearbyFragmentsOnSameVisualRow() {
        val merged = OcrLineLayout.mergeAdjacent(
            listOf(
                OcrLineCandidate("Hello", 10, 10, 60, 30),
                OcrLineCandidate("world", 64, 11, 120, 31)
            )
        )

        assertEquals(1, merged.size)
        assertEquals("Hello world", merged.single().text)
        assertEquals(10, merged.single().left)
        assertEquals(120, merged.single().right)
    }

    @Test
    fun keepsDistantUiLabelsAndSentenceBoundariesSeparate() {
        val labels = OcrLineLayout.mergeAdjacent(
            listOf(
                OcrLineCandidate("HP", 10, 10, 30, 30),
                OcrLineCandidate("250", 60, 10, 90, 30)
            )
        )
        assertEquals(2, labels.size)

        val sentences = OcrLineLayout.mergeAdjacent(
            listOf(
                OcrLineCandidate("Hello.", 10, 40, 65, 60),
                OcrLineCandidate("Next", 69, 40, 115, 60)
            )
        )
        assertEquals(2, sentences.size)
    }

    @Test
    fun removesOverlappingDuplicateRecognitionsAndKeepsLongerText() {
        val lines = OcrLineLayout.deduplicate(
            listOf(
                OcrLineCandidate("HELLO!", 10, 10, 90, 35),
                OcrLineCandidate("Hello", 12, 11, 88, 34),
                OcrLineCandidate("Different", 200, 10, 280, 35)
            )
        )

        assertEquals(2, lines.size)
        assertEquals("HELLO!", lines.first().text)
        assertFalse(lines.any { it.text == "Hello" })
    }
}