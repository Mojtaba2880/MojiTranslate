package com.mojtaba.mojitranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSurfacePolicyTest {
    @Test fun reusesSurfaceWhenMetricsMatch() {
        assertFalse(CaptureSurfacePolicy.needsRecreate(true, true, 1080, 2400, 440, 1080, 2400, 440))
    }

    @Test fun recreatesWhenReaderOrDisplayIsMissing() {
        assertTrue(CaptureSurfacePolicy.needsRecreate(false, true, 1080, 2400, 440, 1080, 2400, 440))
        assertTrue(CaptureSurfacePolicy.needsRecreate(true, false, 1080, 2400, 440, 1080, 2400, 440))
    }

    @Test fun recreatesOnWidthHeightOrDensityChange() {
        assertTrue(CaptureSurfacePolicy.needsRecreate(true, true, 1080, 2400, 440, 2400, 1080, 440))
        assertTrue(CaptureSurfacePolicy.needsRecreate(true, true, 1080, 2400, 440, 1080, 2200, 440))
        assertTrue(CaptureSurfacePolicy.needsRecreate(true, true, 1080, 2400, 440, 1080, 2400, 420))
    }
}
