package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRegressionTest {
    @Test fun knownGamePhraseSurvivesTranslationPreparation() {
        val phrase = "Missive of Grace: A Thank-You Gift"
        val chunks = TranslationTextSplitter.split(phrase)
        assertTrue(chunks.isNotEmpty())
        assertEquals(phrase, TranslationTextPolicy.joinTranslatedChunks(chunks))
        assertEquals(phrase, TranslationTokenProtector.protect(phrase).text)
        assertFalse(TranslationTextPolicy.shouldPreserveIdentifier(phrase))
    }

    @Test fun cropPlannerNeverExceedsAOneRegionRequest() {
        val region = OcrCropPlanner.planRegions(4096, 3200, maxRegions = 1)
        assertEquals(1, region.size)
        assertEquals(OcrCropRegion(0, 0, 4096, 3200), region.single())
    }

    @Test fun denseRetryCoverageRemainsBoundedForLargeScreenshots() {
        val regions = OcrCropPlanner.planRegions(4096, 3200, maxRegions = 12)
        assertTrue(regions.size in 1..12)
        assertEquals(4096, regions.last().right)
        assertEquals(3200, regions.last().bottom)
    }
}
