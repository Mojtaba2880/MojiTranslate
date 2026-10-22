package com.mojtaba.mojitranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPassPlannerTest {
    @Test fun skipsHeavyPassesWhenFastPassFindsEnoughText() {
        assertFalse(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 5, recognizedCharacters = 78))
        assertFalse(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 3, recognizedCharacters = 40))
    }

    @Test fun enablesHeavyPassesForSparseOrShortResults() {
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 0, recognizedCharacters = 0))
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 2, recognizedCharacters = 100))
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 4, recognizedCharacters = 39))
    }

    @Test fun heavyPassGateMatchesRecoveryPolicyAtTheBoundaries() {
        val cases = listOf(
            0 to 0,
            2 to 100,
            3 to 39,
            3 to 40,
            5 to 78,
            -1 to 20,
            3 to -1
        )
        cases.forEach { (lines, chars) ->
            assertEquals(
                "Heavy-pass gate and crop-recovery strategy must agree for $lines lines / $chars chars",
                OcrRecoveryPlanner.isSparseResult(lines, chars),
                OcrPassPlanner.shouldRunHeavyPasses(lines, chars)
            )
        }
    }

    @Test fun invalidMetricsFallBackToHeavyPasses() {
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = -1, recognizedCharacters = 20))
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 3, recognizedCharacters = -1))
    }
}
