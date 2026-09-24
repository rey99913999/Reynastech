package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTargetResolverTest {
    private fun target(source: DeviceTargetSource, confidence: Float) = DeviceTarget(
        semanticName = "Open",
        clickX = 100f,
        clickY = 200f,
        boundsLeft = 50,
        boundsTop = 150,
        boundsRight = 150,
        boundsBottom = 250,
        confidence = confidence,
        source = source,
        clickable = true,
    )

    @Test
    fun accessibilityIsPreferredBeforeVision() = runBlocking {
        val resolver = DeviceTargetResolver(
            cache = DeviceStateCache(),
            accessibilityLookup = AccessibilityTargetLookup {
                DeviceTargetResolution.Resolved(target(DeviceTargetSource.ACCESSIBILITY, 1f))
            },
            visionLookup = VisionTargetLookup { _, _, _ ->
                DeviceTargetResolution.Resolved(target(DeviceTargetSource.VISION, 0.99f))
            },
        )

        val result = resolver.resolve("Open")

        assertTrue(result is DeviceTargetResolution.Resolved)
        assertEquals(
            DeviceTargetSource.ACCESSIBILITY,
            (result as DeviceTargetResolution.Resolved).target.source,
        )
    }

    @Test
    fun visionCanResolveWhenAccessibilityHasNoResult() = runBlocking {
        val resolver = DeviceTargetResolver(
            cache = DeviceStateCache(),
            accessibilityLookup = AccessibilityTargetLookup { null },
            visionLookup = VisionTargetLookup { _, _, _ ->
                DeviceTargetResolution.Resolved(target(DeviceTargetSource.VISION, 0.95f))
            },
        )

        val result = resolver.resolve("Open", 0.90f)

        assertEquals(DeviceTargetSource.VISION, (result as DeviceTargetResolution.Resolved).target.source)
    }

    @Test
    fun explicitCoordinatesHaveDeterministicFallback() = runBlocking {
        val resolver = DeviceTargetResolver(
            cache = DeviceStateCache(),
            accessibilityLookup = AccessibilityTargetLookup { null },
            visionLookup = VisionTargetLookup { _, _, _ -> null },
        )

        val result = resolver.resolve("800, 1800")

        assertTrue(result is DeviceTargetResolution.Resolved)
        assertEquals(DeviceTargetSource.COORDINATE, (result as DeviceTargetResolution.Resolved).target.source)
        assertEquals(800f, (result as DeviceTargetResolution.Resolved).target.clickX, 0f)
    }
}
