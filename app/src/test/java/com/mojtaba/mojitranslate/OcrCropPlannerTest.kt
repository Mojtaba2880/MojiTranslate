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
    @Test
    fun twoDimensionalRetryCropsOverlapCoverEdgesAndStayBounded() {
        val width = 1080
        val height = 2400
        val regions = OcrCropPlanner.planRegions(width, height)
        assertTrue("Tall screenshots should create multiple crop regions", regions.size > 2)
        assertTrue("Retry crop count must stay bounded", regions.size <= 12)
        assertEquals(0, regions.first().left)
        assertEquals(0, regions.first().top)
        assertTrue(regions.any { it.right == width })
        assertTrue(regions.any { it.bottom == height })
        regions.forEach { region ->
            assertTrue(region.left >= 0 && region.top >= 0)
            assertTrue(region.right <= width && region.bottom <= height)
            assertTrue(region.width > 0 && region.height > 0)
        }
    }

    @Test
    fun twoDimensionalRetryPlannerSkipsSmallImagesAndHonorsLimit() {
        assertTrue(OcrCropPlanner.planRegions(200, 1000).isEmpty())
        assertTrue(OcrCropPlanner.planRegions(1080, 2400, maxRegions = 5).size <= 5)
    }

    @Test
    fun denseHighResolutionRetryStaysBoundedAndReachesBottomRight() {
        val width = 4096
        val height = 3200
        val regions = OcrCropPlanner.planRegions(width, height, maxRegions = 12)
        assertTrue(regions.isNotEmpty())
        assertTrue("Dense-screen retry work must remain bounded", regions.size <= 12)
        assertEquals(0, regions.first().left)
        assertEquals(0, regions.first().top)
        assertEquals(width, regions.last().right)
        assertEquals(height, regions.last().bottom)
        assertTrue("Busy screen should receive multiple distinct regions", regions.distinct().size >= 4)
    }

    @Test
    fun denseCoverageCheckStaysBoundedAndTouchesBothScreenEdges() {
        val width = 1080
        val height = 2400
        val regions = OcrCropPlanner.planRegions(
            width,
            height,
            maxRegions = OcrRecoveryPlanner.COVERAGE_MAX_REGIONS
        )

        assertTrue("Coverage verification should create more than one region", regions.size > 1)
        assertTrue("Coverage verification must stay bounded", regions.size <= OcrRecoveryPlanner.COVERAGE_MAX_REGIONS)
        assertEquals(0, regions.first().left)
        assertEquals(0, regions.first().top)
        assertTrue("Coverage verification must reach the right edge", regions.any { it.right == width })
        assertTrue("Coverage verification must reach the bottom edge", regions.any { it.bottom == height })
        assertTrue("Coverage should sample distinct regions", regions.distinct().size >= 4)
    }

}
