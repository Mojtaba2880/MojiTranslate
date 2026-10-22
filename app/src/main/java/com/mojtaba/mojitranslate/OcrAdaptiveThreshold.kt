package com.mojtaba.mojitranslate

/**
 * Memory-bounded local-mean thresholding for grayscale OCR images.
 *
 * Input and output are grayscale bytes (0..255). A sliding vertical sum per column
 * and horizontal sliding sum keep working memory O(width), rather than allocating
 * a full-frame Long integral image.
 */
internal object OcrAdaptiveThreshold {
    fun binarize(
        grayValues: ByteArray,
        width: Int,
        height: Int,
        radius: Int = 15,
        bias: Int = 12
    ): ByteArray {
        require(width > 0 && height > 0) { "Width and height must be positive" }
        require(width.toLong() * height == grayValues.size.toLong()) {
            "Grayscale data size must match width × height"
        }
        require(radius >= 0) { "Radius must be non-negative" }

        val output = ByteArray(grayValues.size)
        val columnSums = IntArray(width)

        // Initialize the vertical window centered on the first row.
        val initialBottom = minOf(height, radius + 1)
        for (y in 0 until initialBottom) {
            val rowOffset = y * width
            for (x in 0 until width) {
                columnSums[x] += grayValues[rowOffset + x].toInt() and 0xFF
            }
        }

        for (y in 0 until height) {
            if (y > 0) {
                val leavingRow = y - radius - 1
                if (leavingRow >= 0) {
                    val rowOffset = leavingRow * width
                    for (x in 0 until width) {
                        columnSums[x] -= grayValues[rowOffset + x].toInt() and 0xFF
                    }
                }
                val enteringRow = y + radius
                if (enteringRow < height) {
                    val rowOffset = enteringRow * width
                    for (x in 0 until width) {
                        columnSums[x] += grayValues[rowOffset + x].toInt() and 0xFF
                    }
                }
            }

            val verticalCount = minOf(height, y + radius + 1) - maxOf(0, y - radius)
            val initialRight = minOf(width - 1, radius)
            var horizontalSum = 0
            for (x in 0..initialRight) horizontalSum += columnSums[x]

            for (x in 0 until width) {
                val left = maxOf(0, x - radius)
                val right = minOf(width, x + radius + 1)
                val sampleCount = (right - left) * verticalCount
                val localMean = if (sampleCount > 0) horizontalSum / sampleCount else 128
                val threshold = (localMean - bias).coerceIn(70, 205)
                val gray = grayValues[y * width + x].toInt() and 0xFF
                output[y * width + x] = if (gray >= threshold) 0xFF.toByte() else 0

                val leavingColumn = x - radius
                if (leavingColumn >= 0) horizontalSum -= columnSums[leavingColumn]
                val enteringColumn = x + radius + 1
                if (enteringColumn < width) horizontalSum += columnSums[enteringColumn]
            }
        }

        return output
    }
}