package com.mojtaba.mojitranslate

/** Decides whether expensive OCR variants are justified by the fast-pass result. */
object OcrPassPlanner {
    fun shouldRunHeavyPasses(lineCount: Int, recognizedCharacters: Int): Boolean {
        if (lineCount < 0 || recognizedCharacters < 0) return true
        return lineCount <= 2 || recognizedCharacters < 40
    }
}
