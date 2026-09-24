package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStateCacheTest {
    @Test
    fun cacheReturnsFreshObservationAndInvalidatesAfterAction() = runBlocking {
        val cache = DeviceStateCache(maxAgeMs = 10_000L)
        val observation = DeviceObservation(
            foregroundPackage = "com.example",
            screenFingerprint = "before",
        )

        cache.put(observation)

        assertNotNull(cache.getFresh())
        cache.invalidate()
        assertNull(cache.getFresh())
    }

    @Test
    fun rememberedTargetsAreBounded() = runBlocking {
        val cache = DeviceStateCache()
        repeat(40) { index ->
            cache.rememberTarget(
                "target-$index",
                DeviceTarget(
                    semanticName = "target-$index",
                    clickX = index.toFloat(),
                    clickY = index.toFloat(),
                    boundsLeft = index,
                    boundsTop = index,
                    boundsRight = index + 10,
                    boundsBottom = index + 10,
                    confidence = 1f,
                    source = DeviceTargetSource.ACCESSIBILITY,
                    clickable = true,
                ),
            )
        }

        assertNull(cache.rememberedTarget("target-0"))
        assertEquals(39f, cache.rememberedTarget("target-39")!!.clickX, 0f)
    }
}
