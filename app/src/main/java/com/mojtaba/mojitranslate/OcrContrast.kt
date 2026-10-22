package com.mojtaba.mojitranslate

/** Pure grayscale contrast normalization so percentile behavior can be regression-tested on the JVM. */
internal object OcrContrast {
    fun stretch(grayValues: IntArray, lowPercent: Float = 0.04f, highPercent: Float = 0.96f): IntArray {
        if (grayValues.isEmpty()) return intArrayOf()
        val histogram = IntArray(256)
        grayValues.forEach { histogram[it.coerceIn(0, 255)]++ }
        val lowTarget = (grayValues.size * lowPercent).toInt().coerceAtLeast(1)
        val highTarget = (grayValues.size * highPercent).toInt().coerceAtLeast(lowTarget)
        var cumulative = 0
        var low = 0
        var high = 255
        for (level in histogram.indices) {
            cumulative += histogram[level]
            if (cumulative >= lowTarget) { low = level; break }
        }
        cumulative = 0
        for (level in histogram.indices) {
            cumulative += histogram[level]
            if (cumulative >= highTarget) { high = level; break }
        }
        if (high - low < 24) { low = 0; high = 255 }
        val range = (high - low).coerceAtLeast(1)
        return IntArray(grayValues.size) { index ->
            val stretched = ((grayValues[index].coerceIn(0, 255) - low) * 255f / range).toInt().coerceIn(0, 255)
            (((stretched - 128) * 1.18f) + 128).toInt().coerceIn(0, 255)
        }
    }
}