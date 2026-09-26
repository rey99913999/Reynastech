package io.github.nastechresearch.nastech.data.model

import io.github.nastechresearch.nastech.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitUntilSettingsTest {
    @Test
    fun defaultsMatchContract() {
        val settings = WaitUntilSettings()
        assertEquals(60, settings.maxWaitSeconds)
        assertEquals(1_000L, settings.checkIntervalMs)
        assertEquals(WaitUntilCondition.SCREEN_CHANGED, settings.defaultCondition)
        assertEquals(10, settings.maxConsecutiveWaits)
        assertTrue(settings.useAccessibility)
        assertTrue(settings.useOcr)
        assertTrue(settings.useScreenState)
        assertTrue(settings.useVisionFallback)
    }

    @Test
    fun normalizationClampsBounds() {
        val settings = WaitUntilSettings(
            maxWaitSeconds = 999,
            checkIntervalMs = 1L,
            maxConsecutiveWaits = 9_999,
        ).normalized()

        assertEquals(300, settings.maxWaitSeconds)
        assertEquals(250L, settings.checkIntervalMs)
        assertEquals(1_200, settings.maxConsecutiveWaits)
    }

    @Test
    fun existingAssistantJsonWithoutWaitSettingsStillDecodes() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"id":"00000000-0000-0000-0000-000000000001"}"""
        )
        assertEquals(WaitUntilSettings(), assistant.waitUntilSettings)
    }
}
