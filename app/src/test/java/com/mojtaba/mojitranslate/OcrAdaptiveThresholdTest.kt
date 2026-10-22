package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrAdaptiveThresholdTest {
    @Test
    fun separatesDarkGlyphsFromBrightBackground() {
        val width = 9
        val height = 9
        val gray = ByteArray(width * height) { 220.toByte() }
        for (y in 3..5) {
            for (x in 3..5) gray[y * width + x] = 15
        }

        val result = OcrAdaptiveThreshold.binarize(gray, width, height, radius = 2)

        assertEquals(gray.size, result.size)
        assertEquals(0, result[4 * width + 4].toInt() and 0xFF)
        assertEquals(255, result[0].toInt() and 0xFF)
        assertTrue(result.all { (it.toInt() and 0xFF) in setOf(0, 255) })
    }

    @Test
    fun handlesTinyImagesAndUniformExtremeValues() {
        val black = OcrAdaptiveThreshold.binarize(byteArrayOf(0), 1, 1, radius = 15)
        val white = OcrAdaptiveThreshold.binarize(byteArrayOf(255.toByte()), 1, 1, radius = 15)
        assertEquals(0, black[0].toInt() and 0xFF)
        assertEquals(255, white[0].toInt() and 0xFF)

        val dark = OcrAdaptiveThreshold.binarize(ByteArray(12) { 20 }, 4, 3, radius = 1)
        val bright = OcrAdaptiveThreshold.binarize(ByteArray(12) { 230.toByte() }, 4, 3, radius = 1)
        assertTrue(dark.all { (it.toInt() and 0xFF) == 0 })
        assertTrue(bright.all { (it.toInt() and 0xFF) == 255 })
    }

    @Test
    fun rejectsInvalidDimensionsAndRadius() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrAdaptiveThreshold.binarize(byteArrayOf(1, 2), 3, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrAdaptiveThreshold.binarize(byteArrayOf(1), 1, 1, radius = -1)
        }
    }
}
