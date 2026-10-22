package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRecoveryPlannerTest {
    @Test
    fun denseResultGetsBoundedLatinCoverageCheck() {
        val plan = OcrRecoveryPlanner.plan(lineCount = 5, recognizedCharacters = 78)

        assertEquals(OcrRecoveryMode.LATIN_COVERAGE, plan.mode)
        assertEquals(6, plan.maxRegions)
        assertFalse(plan.useAllRecognizers)
        assertFalse(plan.rectifyDetectedLines)
    }

    @Test
    fun emptyResultGetsDeepMultilingualRecovery() {
        val plan = OcrRecoveryPlanner.plan(lineCount = 0, recognizedCharacters = 0)

        assertEquals(OcrRecoveryMode.SPARSE_MULTILINGUAL, plan.mode)
        assertEquals(12, plan.maxRegions)
        assertTrue(plan.useAllRecognizers)
        assertTrue(plan.rectifyDetectedLines)
    }

    @Test
    fun exactDenseThresholdUsesCoverageCheck() {
        assertEquals(
            OcrRecoveryMode.LATIN_COVERAGE,
            OcrRecoveryPlanner.plan(lineCount = 3, recognizedCharacters = 40).mode
        )
    }

    @Test
    fun shortTextBelowCharacterThresholdUsesDeepRecovery() {
        assertEquals(
            OcrRecoveryMode.SPARSE_MULTILINGUAL,
            OcrRecoveryPlanner.plan(lineCount = 3, recognizedCharacters = 39).mode
        )
    }

    @Test
    fun tooFewLinesUseDeepRecoveryEvenWhenCharacterCountIsHigh() {
        assertEquals(
            OcrRecoveryMode.SPARSE_MULTILINGUAL,
            OcrRecoveryPlanner.plan(lineCount = 2, recognizedCharacters = 120).mode
        )
    }

    @Test
    fun invalidMetricsFallBackToDeepRecovery() {
        assertTrue(OcrRecoveryPlanner.isSparseResult(-1, 20))
        assertTrue(OcrRecoveryPlanner.isSparseResult(3, -1))
        assertEquals(
            OcrRecoveryMode.SPARSE_MULTILINGUAL,
            OcrRecoveryPlanner.plan(-1, 20).mode
        )
    }
}
