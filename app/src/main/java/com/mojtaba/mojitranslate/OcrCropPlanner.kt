package com.mojtaba.mojitranslate

/**
 * Pure crop geometry for the sparse-result OCR fallback.
 * Each range is a horizontal slice along the bitmap's x-axis.
 */
data class OcrCropRange(val left: Int, val right: Int) {
    val width: Int get() = right - left
}

object OcrCropPlanner {
    /**
     * Plans overlapping vertical strips. The parameters retain the app's existing
     * heuristic while making coverage and overlap independently unit-testable.
     */
    fun plan(
        bitmapWidth: Int,
        minimumStripWidth: Int = 160,
        stripWidthRatio: Float = 0.62f,
        stepRatio: Float = 0.72f,
        minimumBitmapWidth: Int = 240
    ): List<OcrCropRange> {
        if (bitmapWidth < minimumBitmapWidth || bitmapWidth <= 0) return emptyList()

        val stripWidth = (bitmapWidth * stripWidthRatio).toInt()
            .coerceAtLeast(minimumStripWidth)
            .coerceAtMost(bitmapWidth)
        if (stripWidth >= bitmapWidth) return emptyList()

        val step = (stripWidth * stepRatio).toInt().coerceAtLeast(1)
        val ranges = mutableListOf<OcrCropRange>()
        var left = 0
        while (left < bitmapWidth) {
            val right = (left + stripWidth).coerceAtMost(bitmapWidth)
            ranges.add(OcrCropRange(left, right))
            if (right == bitmapWidth) break
            left += step
        }
        return ranges
    }
}
