package com.mojtaba.mojitranslate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates a smooth vertical displacement for a single cropped text line.
 * It is intentionally conservative: ambiguous/noisy crops return null instead of being warped.
 */
internal object CurvedTextRectifier {
    fun estimateVerticalOffsets(width: Int, height: Int, grayscale: IntArray): FloatArray? {
        if (width < 80 || height < 10 || grayscale.size != width * height) return null

        val centers = FloatArray(width)
        val valid = BooleanArray(width)
        var validCount = 0
        for (x in 0 until width) {
            var weightedY = 0.0
            var totalWeight = 0.0
            for (y in 1 until height) {
                val before = grayscale[(y - 1) * width + x].coerceIn(0, 255)
                val after = grayscale[y * width + x].coerceIn(0, 255)
                val gradient = abs(after - before)
                if (gradient >= 14) {
                    val weight = (gradient - 10).toDouble()
                    weightedY += y * weight
                    totalWeight += weight
                }
            }
            if (totalWeight >= 24.0) {
                centers[x] = (weightedY / totalWeight).toFloat()
                valid[x] = true
                validCount++
            }
        }
        if (validCount < max(12, width / 4)) return null

        // Interpolate columns containing spaces so glyph gaps do not break the baseline.
        val first = valid.indexOfFirst { it }
        val last = valid.indexOfLast { it }
        if (first < 0 || last <= first) return null
        for (x in first..last) {
            if (valid[x]) continue
            var left = x - 1
            while (left >= first && !valid[left]) left--
            var right = x + 1
            while (right <= last && !valid[right]) right++
            centers[x] = when {
                left < first -> centers[right]
                right > last -> centers[left]
                else -> {
                    val fraction = (x - left).toFloat() / (right - left)
                    centers[left] + (centers[right] - centers[left]) * fraction
                }
            }
        }
        for (x in 0 until first) centers[x] = centers[first]
        for (x in last + 1 until width) centers[x] = centers[last]

        val radius = min(18, max(2, width / 16))
        val smoothed = FloatArray(width) { x ->
            val from = max(0, x - radius)
            val to = min(width - 1, x + radius)
            var sum = 0f
            for (i in from..to) sum += centers[i]
            sum / (to - from + 1)
        }
        val reference = smoothed.average().toFloat()
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        for (value in smoothed) {
            minimum = min(minimum, value)
            maximum = max(maximum, value)
        }
        // Avoid modifying straight/noisy text lines where a warp is unlikely to help.
        if (maximum - minimum < max(2.5f, height * 0.04f)) return null

        val limit = height * 0.20f
        return FloatArray(width) { x -> (smoothed[x] - reference).coerceIn(-limit, limit) }
    }
}
