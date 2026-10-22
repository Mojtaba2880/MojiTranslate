package com.mojtaba.mojitranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class BubblePositionPolicyTest {
    @Test fun clampsNegativeCoordinatesToTopLeft() {
        assertEquals(BubblePosition(0, 0), BubblePositionPolicy.clamp(-10, -20, 1080, 2400, 70, 70))
    }

    @Test fun clampsBubbleFullyInsideBottomRightEdge() {
        assertEquals(BubblePosition(1010, 2330), BubblePositionPolicy.clamp(1500, 3000, 1080, 2400, 70, 70))
    }

    @Test fun keepsValidCoordinatesUnchanged() {
        assertEquals(BubblePosition(120, 600), BubblePositionPolicy.clamp(120, 600, 1080, 2400, 70, 70))
    }

    @Test fun handlesBubbleLargerThanDisplay() {
        assertEquals(BubblePosition(0, 0), BubblePositionPolicy.clamp(20, 30, 50, 60, 80, 90))
    }
}
