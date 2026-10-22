package com.mojtaba.mojitranslate

/** Pure policy for deciding whether a screen-capture surface still matches current display metrics. */
object CaptureSurfacePolicy {
    fun needsRecreate(
        readerPresent: Boolean,
        displayPresent: Boolean,
        currentWidth: Int,
        currentHeight: Int,
        currentDensityDpi: Int,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedDensityDpi: Int
    ): Boolean =
        !readerPresent || !displayPresent ||
            currentWidth != requestedWidth ||
            currentHeight != requestedHeight ||
            currentDensityDpi != requestedDensityDpi
}
