package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrContrastTest {
    @Test
    fun expandsLowContrastRange() {
        val input = intArrayOf(80, 90, 100, 110, 120, 130, 140, 150, 160, 170)
        val output = OcrContrast.stretch(input)
        assertEquals(input.size, output.size)
        assertTrue(output.first() < output.last())
        assertTrue(output.all { it in 0..255 })
        assertTrue(output.last() - output.first() > input.last() - input.first())
    }

    @Test
    fun handlesUniformAndEmptyInputs() {
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

    @Test
    fun increasesContrastForDarkAndLightLowContrastSamples() {
        val lowContrast = intArrayOf(80, 90, 100, 110, 120, 130, 140, 150, 160, 170)
        val darkTextBackground = OcrContrast.stretch(lowContrast)
        val lightTextBackground = OcrContrast.stretch(IntArray(lowContrast.size) { 255 - lowContrast[it] })
        assertTrue(darkTextBackground.maxOrNull()!! - darkTextBackground.minOrNull()!! > 200)
        assertTrue(lightTextBackground.maxOrNull()!! - lightTextBackground.minOrNull()!! > 200)
    }

    @Test
    fun malformedPercentilesStayBoundedAndPreserveSampleCount() {
        val input = intArrayOf(20, 40, 60, 80, 100, 120, 140, 160)
        val cases = listOf(
            OcrContrast.stretch(input, Float.NaN, Float.POSITIVE_INFINITY),
            OcrContrast.stretch(input, -1f, 2f),
            OcrContrast.stretch(input, 0.9f, 0.1f)
        )
        cases.forEach { output ->
            assertEquals(input.size, output.size)
            assertTrue(output.all { it in 0..255 })
        }
    }
}