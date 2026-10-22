package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCoordinateMapperTest {
    @Test fun zeroRotationPreservesOriginalBounds() {
        assertEquals(
            OcrMappedBounds(20, 10, 40, 30),
            OcrCoordinateMapper.mapRotatedBounds(20, 10, 40, 30, 100, 80, 0.0)
        )
    }

    @Test fun mapsAngledBoundsBackAroundImageCenter() {
        assertEquals(
            OcrMappedBounds(38, 40, 61, 59),
            OcrCoordinateMapper.mapRotatedBounds(40, 45, 60, 55, 100, 100, 30.0)
        )
    }

    @Test fun clampsOutOfFrameBoundsAndRejectsInvalidInput() {
        val mapped = OcrCoordinateMapper.mapRotatedBounds(-20, -10, 10, 15, 100, 80, 5.0)
        assertTrue(mapped.left >= 0 && mapped.top >= 0)
        assertTrue(mapped.right <= 100 && mapped.bottom <= 80)
        assertEquals(OcrMappedBounds(0, 0, 0, 0), OcrCoordinateMapper.mapRotatedBounds(3, 3, 3, 6, 100, 80, 15.0))
    }
}
