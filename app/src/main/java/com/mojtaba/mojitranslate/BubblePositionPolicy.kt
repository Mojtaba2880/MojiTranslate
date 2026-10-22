package com.mojtaba.mojitranslate

data class BubblePosition(val x: Int, val y: Int)

/** Keeps the draggable floating control fully inside the available display bounds. */
object BubblePositionPolicy {
    fun clamp(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        bubbleWidth: Int,
        bubbleHeight: Int
    ): BubblePosition = BubblePosition(
        x.coerceIn(0, (screenWidth - bubbleWidth).coerceAtLeast(0)),
        y.coerceIn(0, (screenHeight - bubbleHeight).coerceAtLeast(0))
    )
}
