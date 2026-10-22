package com.mojtaba.mojitranslate

/**
 * Geometry for a retry crop. Coordinates are relative to the captured bitmap.
 */
data class OcrCropRange(val left: Int, val right: Int) {
    val width: Int get() = right - left
}

data class OcrCropRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object OcrCropPlanner {
    /** Plans overlapping vertical strips; retained for callers and focused geometry tests. */
    fun plan(
        bitmapWidth: Int,
        minimumStripWidth: Int = 160,
        stripWidthRatio: Float = 0.62f,
        stepRatio: Float = 0.72f,
        minimumBitmapWidth: Int = 240
    ): List<OcrCropRange> {
        if (bitmapWidth < minimumBitmapWidth || bitmapWidth <= 0) return emptyList()
        val stripWidth = (bitmapWidth * stripWidthRatio).toInt()
            .coerceAtLeast(minimumStripWidth).coerceAtMost(bitmapWidth)
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

    /**
     * Plans a bounded 2-D overlapping grid for sparse OCR retries. Shorter crops give
     * small captions a larger fraction of the recognizer input without leaving gaps.
     * The grid is capped to avoid unbounded memory and recognizer work on tall screens.
     */
    fun planRegions(
        bitmapWidth: Int,
        bitmapHeight: Int,
        minimumBitmapWidth: Int = 240,
        minimumBitmapHeight: Int = 320,
        maxRegions: Int = 12
    ): List<OcrCropRegion> {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 ||
            bitmapWidth < minimumBitmapWidth || bitmapHeight < minimumBitmapHeight || maxRegions < 1
        ) return emptyList()

        // A caller requesting exactly one region needs a single, complete image,
        // not a sampled grid that silently exceeds its declared limit.
        if (maxRegions == 1) {
            return listOf(OcrCropRegion(0, 0, bitmapWidth, bitmapHeight))
        }

        val columns = plan(bitmapWidth)
        if (columns.isEmpty()) return emptyList()
        val bandHeight = (bitmapHeight * 0.62f).toInt().coerceAtLeast(240).coerceAtMost(bitmapHeight)
        if (bandHeight >= bitmapHeight) {
            return columns.map { OcrCropRegion(it.left, 0, it.right, bitmapHeight) }.take(maxRegions)
        }
        val stepY = (bandHeight * 0.72f).toInt().coerceAtLeast(1)
        val tops = mutableListOf<Int>()
        var top = 0
        while (top < bitmapHeight) {
            tops.add(top)
            if (top + bandHeight >= bitmapHeight) break
            top += stepY
        }
        val all = mutableListOf<OcrCropRegion>()
        for (y in tops) {
            val bottom = (y + bandHeight).coerceAtMost(bitmapHeight)
            for (x in columns) all.add(OcrCropRegion(x.left, y, x.right, bottom))
        }
        if (all.size <= maxRegions) return all

        // Evenly sample the ordered grid while always preserving its first and last region.
        val sampled = mutableListOf<OcrCropRegion>()
        val count = maxRegions.coerceAtLeast(2)
        for (i in 0 until count) {
            val index = (i.toLong() * (all.size - 1) / (count - 1)).toInt()
            val region = all[index]
            if (sampled.lastOrNull() != region) sampled.add(region)
        }
        return sampled
    }
}
