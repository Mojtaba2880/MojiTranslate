package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCropPlannerTest {
    @Test
    fun refusesWidthsThatCannotBenefitFromCropping() {
        assertTrue(OcrCropPlanner.plan(0).isEmpty())
        assertTrue(OcrCropPlanner.plan(239).isEmpty())
    }

    @Test
    fun cropsOverlapAndCoverBothEdges() {
        val ranges = OcrCropPlanner.plan(1000)
        assertTrue("Must produce multiple crops", ranges.size >= 2)
        assertEquals(0, ranges.first().left)
        assertEquals(1000, ranges.last().right)

        ranges.forEach { range ->
            assertTrue("Crop must have positive width", range.left >= 0 && range.right > range.left)
            assertTrue("Crop must stay inside bitmap", range.right <= 1000)
        }
        ranges.zipWithNext().forEach { (first, second) ->
            assertTrue("Adjacent crops must overlap", second.left < first.right)
            assertTrue("Overlap must have a positive width", first.right - second.left > 0)
        }
    }

    @Test
    fun allSupportedWidthsRemainCoveredWithoutGaps() {
        for (width in 240..4096) {
            val ranges = OcrCropPlanner.plan(width)
            assertTrue("Width $width should be split", ranges.size >= 2)
            assertEquals("Width $width starts at zero", 0, ranges.first().left)
            assertEquals("Width $width covers right edge", width, ranges.last().right)
            ranges.zipWithNext().forEach { (first, second) ->
                assertTrue("Width $width has a gap", second.left <= first.right)
                assertTrue("Width $width has no overlap", second.left < first.right)
            }
        }
    }
}
