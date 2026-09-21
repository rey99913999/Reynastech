package io.github.nastechresearch.nastech.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelsTest {

    @Test
    fun highConfidenceIsOnlyBandAllowedForSensitiveActions() {
        val policy = VisionConfidencePolicy(high = 0.90f, medium = 0.70f)

        assertEquals("high", policy.band(0.94f))
        assertEquals("medium", policy.band(0.80f))
        assertEquals("low", policy.band(0.40f))
        assertTrue(policy.sensitiveActionAllowed(0.91f))
        assertFalse(policy.sensitiveActionAllowed(0.89f))
    }

    @Test
    fun screenBoundsRemainIndependentOfAbsoluteResolution() {
        val first = ScreenBounds(100, 200, 500, 400)
        val second = ScreenBounds(300, 600, 1500, 1200)

        assertEquals(300f, first.centerX(), 0.01f)
        assertEquals(300f, first.centerY(), 0.01f)
        assertEquals(900f, second.centerX(), 0.01f)
        assertEquals(900f, second.centerY(), 0.01f)
    }

    @Test
    fun visionDecisionReportsSourceAndConfidenceStructurally() {
        val decision = VisionDecision(
            target = "Install",
            type = ScreenElementType.BUTTON,
            bounds = ScreenBounds(10, 20, 110, 70),
            confidence = 0.96f,
            state = "enabled",
            source = "ocr",
        )

        assertEquals("Install", decision.target)
        assertEquals(ScreenElementType.BUTTON, decision.type)
        assertEquals("ocr", decision.source)
        assertTrue(decision.confidence >= 0.95f)
    }
}
