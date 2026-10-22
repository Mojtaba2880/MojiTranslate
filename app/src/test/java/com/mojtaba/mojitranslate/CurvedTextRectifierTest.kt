package com.mojtaba.mojitranslate

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CurvedTextRectifierTest {
    @Test
    fun detectsSmoothCurveInSyntheticTextLine() {
        val width = 160
        val height = 48
        val pixels = IntArray(width * height) { 245 }
        for (x in 0 until width) {
            val center = (height / 2.0 - 7.0 * sin(2.0 * PI * x / (width - 1))).toInt()
            for (y in (center - 3)..(center + 3)) {
                pixels[y * width + x] = 35
            }
        }

        val offsets = CurvedTextRectifier.estimateVerticalOffsets(width, height, pixels)
        assertNotNull("A clear curved baseline should be rectifiable", offsets)
        val values = offsets!!
        assertTrue("Offsets should vary across the curved line", values.maxOrNull()!! - values.minOrNull()!! > 5f)
    }

    @Test
    fun refusesStraightLinesAndTooSmallCrops() {
        val width = 160
        val height = 40
        val straight = IntArray(width * height) { 245 }
        for (x in 0 until width) {
            for (y in 17..23) straight[y * width + x] = 35
        }
        assertNull(CurvedTextRectifier.estimateVerticalOffsets(width, height, straight))
        assertNull(CurvedTextRectifier.estimateVerticalOffsets(79, height, IntArray(79 * height)))
    }
}
