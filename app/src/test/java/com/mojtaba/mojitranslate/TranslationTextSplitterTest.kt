package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextSplitterTest {
    @Test
    fun keepsShortTextTogether() {
        assertEquals(listOf("Hello world!"), TranslationTextSplitter.split("  Hello world!  "))
    }

    @Test
    fun splitsLongTextAtWordBoundariesAndPreservesOrder() {
        val source = "one two three four five six seven eight nine ten"
        val chunks = TranslationTextSplitter.split(source, maxWords = 3, maxChars = 100)
        assertEquals(listOf("one two three", "four five six", "seven eight nine", "ten"), chunks)
        assertEquals(source, chunks.joinToString(" "))
    }

    @Test
    fun handlesPunctuationAndEmptyInput() {
        assertTrue(TranslationTextSplitter.split("   ").isEmpty())
        assertEquals(listOf("Alpha.", "Beta!", "Gamma"), TranslationTextSplitter.split("Alpha. Beta! Gamma", maxWords = 1))
    }

    @Test
    fun respectsCharacterLimitForLongWords() {
        val chunks = TranslationTextSplitter.split("abcdefghij", maxWords = 2, maxChars = 4)
        assertEquals(listOf("abcd", "efgh", "ij"), chunks)
        assertTrue(chunks.all { it.length <= 4 })
    }
}