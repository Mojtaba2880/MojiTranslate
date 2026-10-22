package com.mojtaba.mojitranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun invalidMetricsFallBackToHeavyPasses() {
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = -1, recognizedCharacters = 20))
        assertTrue(OcrPassPlanner.shouldRunHeavyPasses(lineCount = 3, recognizedCharacters = -1))
    }
}
