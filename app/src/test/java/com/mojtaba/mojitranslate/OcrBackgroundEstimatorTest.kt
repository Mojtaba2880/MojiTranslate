package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrBackgroundEstimatorTest {
    @Test fun findsMedianForUniformBackground() {
        val background = rgb(35, 42, 50)
        assertEquals(background, OcrBackgroundEstimator.estimate(intArrayOf(background, background, background, background, background)))
    }

    @Test fun isolatedBrightPixelDoesNotDominateBackground() {
        val background = rgb(40, 60, 80)
        val noisy = intArrayOf(background, background, background, background, rgb(255, 0, 0))
        assertEquals(background, OcrBackgroundEstimator.estimate(noisy))
    }

    @Test fun medianCombinesChannelsAndReturnsOpaqueColor() {
        val estimate = OcrBackgroundEstimator.estimate(
            intArrayOf(rgb(10, 20, 30), rgb(30, 40, 50), rgb(20, 30, 40))
        )
        assertEquals(rgb(20, 30, 40), estimate)
    }

    @Test fun emptySamplesHaveSafeOpaqueFallback() {
        assertEquals(0xFF000000.toInt(), OcrBackgroundEstimator.estimate(intArrayOf()))
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
