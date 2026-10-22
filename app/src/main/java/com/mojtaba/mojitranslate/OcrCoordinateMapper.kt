package com.mojtaba.mojitranslate

import kotlin.math.cos
import kotlin.math.sin

data class OcrMappedBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** JVM-testable inverse mapping of OCR bounds from a rotated bitmap back to source coordinates. */
object OcrCoordinateMapper {
    fun mapRotatedBounds(
        left: Int, top: Int, right: Int, bottom: Int,
        width: Int, height: Int, degrees: Double
    ): OcrMappedBounds {
        if (width <= 0 || height <= 0 || right <= left || bottom <= top) {
            return OcrMappedBounds(0, 0, 0, 0)
        }
        val centerX = width / 2.0
        val centerY = height / 2.0
        val radians = Math.toRadians(-degrees)
        val cosine = cos(radians)
        val sine = sin(radians)
        val sourceXs = doubleArrayOf(left.toDouble(), right.toDouble(), right.toDouble(), left.toDouble())
        val sourceYs = doubleArrayOf(top.toDouble(), top.toDouble(), bottom.toDouble(), bottom.toDouble())
        val mappedXs = DoubleArray(4)
        val mappedYs = DoubleArray(4)
        for (index in 0..3) {
            val dx = sourceXs[index] - centerX
            val dy = sourceYs[index] - centerY
            mappedXs[index] = centerX + dx * cosine - dy * sine
            mappedYs[index] = centerY + dx * sine + dy * cosine
        }
        return OcrMappedBounds(
            mappedXs.minOrNull()!!.toInt().coerceIn(0, width),
            mappedYs.minOrNull()!!.toInt().coerceIn(0, height),
            mappedXs.maxOrNull()!!.toInt().coerceIn(0, width),
            mappedYs.maxOrNull()!!.toInt().coerceIn(0, height)
        )
    }
}
