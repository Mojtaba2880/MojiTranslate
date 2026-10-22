package com.mojtaba.mojitranslate

/** Decides whether expensive image transforms are justified by the fast-pass result. */
object OcrPassPlanner {
    fun shouldRunHeavyPasses(lineCount: Int, recognizedCharacters: Int): Boolean =
        OcrRecoveryPlanner.isSparseResult(lineCount, recognizedCharacters)
}
