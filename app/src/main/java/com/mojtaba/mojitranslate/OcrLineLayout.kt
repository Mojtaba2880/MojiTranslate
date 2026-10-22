package com.mojtaba.mojitranslate

/**
 * Android-independent ordering, de-duplication and joining rules for OCR text blocks.
 * Coordinates are in the source screenshot's pixel space.
 */
internal data class OcrLineCandidate(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal object OcrLineLayout {
    private val visualOrder = compareBy<OcrLineCandidate> { it.top }.thenBy { it.left }

    fun deduplicate(lines: List<OcrLineCandidate>): List<OcrLineCandidate> {
        val result = mutableListOf<OcrLineCandidate>()
        for (line in lines.sortedWith(visualOrder)) {
            val index = result.indexOfFirst { old ->
                val left = maxOf(old.left, line.left)
                val top = maxOf(old.top, line.top)
                val right = minOf(old.right, line.right)
                val bottom = minOf(old.bottom, line.bottom)
                val intersects = right > left && bottom > top
                val oldArea = old.width.toLong().coerceAtLeast(0L) * old.height.toLong().coerceAtLeast(0L)
                val lineArea = line.width.toLong().coerceAtLeast(0L) * line.height.toLong().coerceAtLeast(0L)
                val minimumArea = minOf(oldArea, lineArea).coerceAtLeast(1L)
                val intersectionArea = (right - left).toLong().coerceAtLeast(0L) *
                    (bottom - top).toLong().coerceAtLeast(0L)
                intersects && intersectionArea > minimumArea * 0.45 &&
                    normalizedComparableText(old.text) == normalizedComparableText(line.text)
            }
            if (index < 0) result.add(line)
            else if (line.text.length > result[index].text.length) result[index] = line
        }
        return result.sortedWith(visualOrder)
    }

    fun mergeAdjacent(lines: List<OcrLineCandidate>): List<OcrLineCandidate> {
        if (lines.size < 2) return lines.sortedWith(visualOrder)
        val ordered = lines.sortedWith(visualOrder)
        val merged = mutableListOf<OcrLineCandidate>()
        for (line in ordered) {
            val previous = merged.lastOrNull()
            if (previous == null) {
                merged.add(line)
                continue
            }
            val overlapTop = maxOf(previous.top, line.top)
            val overlapBottom = minOf(previous.bottom, line.bottom)
            val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0)
            val minHeight = minOf(previous.height, line.height).coerceAtLeast(1)
            val horizontalGap = line.left - previous.right
            val sameVisualRow = overlapHeight.toFloat() / minHeight >= 0.62f
            val closeGap = horizontalGap >= -minHeight / 3 && horizontalGap <= minHeight * 1.15f
            val previousText = previous.text.trim()
            val nextText = line.text.trim()
            val hasSentenceBoundary = previousText.lastOrNull() in
                listOf('.', '!', '?', '。', '！', '？', ':', ';', '：', '；')
            val startsAsContinuation = nextText.firstOrNull()?.let { ch ->
                ch.isLowerCase() || ch in ",.!?，。！？:;、"
            } ?: false
            val likelySameSentence = !hasSentenceBoundary &&
                nextText.isNotBlank() &&
                previousText.length + nextText.length <= 220 &&
                (horizontalGap <= minHeight * 0.55 || startsAsContinuation ||
                    previousText.endsWith("-") || previousText.endsWith("—"))
            if (sameVisualRow && closeGap && likelySameSentence) {
                merged[merged.lastIndex] = OcrLineCandidate(
                    previous.text.trimEnd() + " " + line.text.trimStart(),
                    minOf(previous.left, line.left),
                    minOf(previous.top, line.top),
                    maxOf(previous.right, line.right),
                    maxOf(previous.bottom, line.bottom)
                )
            } else {
                merged.add(line)
            }
        }
        return merged
    }

    private fun normalizedComparableText(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}