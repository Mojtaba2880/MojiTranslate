package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrContrastTest {
    @Test
    fun stretchesLowContrastRangeWithoutChangingArrayLength() {
        val input = intArrayOf(80, 90, 100, 110, 120, 130, 140, 150, 160, 170)
        val output = OcrContrast.stretch(input)
        assertEquals(input.size, output.size)
        assertTrue("Dark glyph values should remain darker", output.first() < output.last())
        assertTrue("Output must stay in grayscale range", output.all { it in 0..255 })
        assertTrue("Contrast should be expanded", output.last() - output.first() > input.last() - input.first())
    }

    @Test
    fun handlesUniformAndEmptyInputsSafely() {
        assertTrue(OcrContrast.stretch(intArrayOf()).isEmpty())
        val uniform = OcrContrast.stretch(IntArray(100) { 120 })
        assertEquals(100, uniform.size)
        assertTrue(uniform.all { it in 0..255 })
    }

    @Test
    fun clampsOutOfRangeSamples() {
        val output = OcrContrast.stretch(intArrayOf(-50, 0, 100, 255, 300))
        assertEquals(5, output.size)
        assertTrue(output.all { it in 0..255 })
    }
}
