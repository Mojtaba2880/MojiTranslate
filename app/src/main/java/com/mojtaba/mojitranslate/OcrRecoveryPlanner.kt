package com.mojtaba.mojitranslate

/** Determines how much extra OCR work is justified by the first recognition result. */
internal enum class OcrRecoveryMode {
    /** Run bounded, multi-script recovery and retry detected lines after curve correction. */
    SPARSE_MULTILINGUAL,
    /** Verify screen coverage with a small set of Latin-only overlapping crops. */
    LATIN_COVERAGE
}

internal data class OcrRecoveryPlan(
    val mode: OcrRecoveryMode,
    val maxRegions: Int,
    val useAllRecognizers: Boolean,
    val rectifyDetectedLines: Boolean
)

/**
 * Separates recovery policy from Android bitmap/OCR APIs so thresholds and work limits
 * can be tested without a device. Unknown/invalid metrics deliberately take the safer path.
 */
internal object OcrRecoveryPlanner {
    const val MIN_DENSE_LINE_COUNT = 3
    const val MIN_DENSE_CHARACTER_COUNT = 40
    const val SPARSE_MAX_REGIONS = 12
    const val COVERAGE_MAX_REGIONS = 6

    fun isSparseResult(lineCount: Int, recognizedCharacters: Int): Boolean {
        if (lineCount < 0 || recognizedCharacters < 0) return true
        return lineCount < MIN_DENSE_LINE_COUNT ||
            recognizedCharacters < MIN_DENSE_CHARACTER_COUNT
    }

    fun plan(lineCount: Int, recognizedCharacters: Int): OcrRecoveryPlan =
        if (isSparseResult(lineCount, recognizedCharacters)) {
            OcrRecoveryPlan(
                mode = OcrRecoveryMode.SPARSE_MULTILINGUAL,
                maxRegions = SPARSE_MAX_REGIONS,
                useAllRecognizers = true,
                rectifyDetectedLines = true
            )
        } else {
            OcrRecoveryPlan(
                mode = OcrRecoveryMode.LATIN_COVERAGE,
                maxRegions = COVERAGE_MAX_REGIONS,
                useAllRecognizers = false,
                rectifyDetectedLines = false
            )
        }
}
