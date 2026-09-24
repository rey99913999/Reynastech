package io.github.nastechresearch.nastech.data.execution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class DeviceStateCache(
    private val maxAgeMs: Long = 2_500L,
) {
    private val mutex = Mutex()
    private var observation: DeviceObservation? = null
    private var invalidatedAtMs: Long = 0L
    private val rememberedTargets = LinkedHashMap<String, DeviceTarget>()

    suspend fun getFresh(maxAgeOverrideMs: Long? = null): DeviceObservation? =
        mutex.withLock {
            val current = observation ?: return@withLock null
            val maxAge = maxAgeOverrideMs ?: maxAgeMs
            val age = System.currentTimeMillis() - current.createdAtMs
            if (current.createdAtMs <= invalidatedAtMs || age > maxAge) {
                null
            } else {
                current
            }
        }

    suspend fun put(value: DeviceObservation) {
        mutex.withLock {
            val oldPath = observation?.screenshotPath
            observation = value
            if (oldPath != null && oldPath != value.screenshotPath) {
                runCatching { File(oldPath).delete() }
            }
        }
    }

    suspend fun invalidate() {
        mutex.withLock { invalidatedAtMs = System.currentTimeMillis() }
    }

    suspend fun rememberTarget(key: String, target: DeviceTarget) {
        mutex.withLock {
            rememberedTargets.remove(key)
            rememberedTargets[key] = target
            while (rememberedTargets.size > 32) {
                rememberedTargets.remove(rememberedTargets.entries.first().key)
            }
        }
    }

    suspend fun rememberedTarget(key: String): DeviceTarget? =
        mutex.withLock { rememberedTargets[key] }

    suspend fun clear() {
        mutex.withLock {
            observation?.screenshotPath?.let { runCatching { File(it).delete() } }
            observation = null
            rememberedTargets.clear()
            invalidatedAtMs = System.currentTimeMillis()
        }
    }
}
