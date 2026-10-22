package com.mojtaba.mojitranslate

/** Robust color estimate for translation-cover backgrounds, independent of Android UI classes. */
object OcrBackgroundEstimator {
    fun estimate(argbColors: IntArray): Int {
        if (argbColors.isEmpty()) return 0xFF000000.toInt()
        val red = IntArray(argbColors.size) { (argbColors[it] ushr 16) and 0xFF }
        val green = IntArray(argbColors.size) { (argbColors[it] ushr 8) and 0xFF }
        val blue = IntArray(argbColors.size) { argbColors[it] and 0xFF }
        return (0xFF shl 24) or
            (median(red) shl 16) or
            (median(green) shl 8) or
            median(blue)
    }

    private fun median(values: IntArray): Int {
        values.sort()
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle]
        else (values[middle - 1] + values[middle]) / 2
    }
}
