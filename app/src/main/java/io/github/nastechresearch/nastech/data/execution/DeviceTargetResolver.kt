package io.github.nastechresearch.nastech.data.execution

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nastechresearch.nastech.data.vision.VisionPipeline
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService

fun interface AccessibilityTargetLookup {
    suspend fun resolve(
        target: String,
        minConfidence: Float = 0.70f,
        visionModelId: String? = null,
    ): DeviceTargetResolution {
        val key = normalizeDeviceText(target)

        parseCoordinates(target)?.let { coordinate ->
            cache.rememberTarget(key, coordinate)
            return DeviceTargetResolution.Resolved(coordinate)
        }

        cache.rememberedTarget(key)?.let { remembered ->
            if (remembered.confidence >= minConfidence) {
                return DeviceTargetResolution.Resolved(remembered)
            }
        }

        var accessibilityAmbiguous: DeviceTargetResolution.Ambiguous? = null

        accessibilityLookup.resolve(target)?.let { result ->
            when (result) {
                is DeviceTargetResolution.Resolved -> {
                    cache.rememberTarget(key, result.target)
                    return result
                }
                is DeviceTargetResolution.Ambiguous -> {
                    accessibilityAmbiguous = result
                }
                is DeviceTargetResolution.NotFound -> Unit
            }
        }

        visionLookup.resolve(target, minConfidence, visionModelId)?.let { result ->
            when (result) {
                is DeviceTargetResolution.Resolved -> {
                    cache.rememberTarget(key, result.target)
                    return result
                }
                is DeviceTargetResolution.Ambiguous -> return result
                is DeviceTargetResolution.NotFound -> Unit
            }
        }

        return accessibilityAmbiguous ?: DeviceTargetResolution.NotFound(target, "target_not_found")
    }

    private fun parseCoordinates(value: String): DeviceTarget? {
        val match = Regex("""^\(?\s*(\d+(?:\.\d+)?)\s*[, ]\s*(\d+(?:\.\d+)?)\s*\)?$""")
            .matchEntire(normalizeDeviceText(value)) ?: return null
        val x = match.groupValues[1].toFloatOrNull() ?: return null
        val y = match.groupValues[2].toFloatOrNull() ?: return null
        return DeviceTarget(
            semanticName = value,
            clickX = x,
            clickY = y,
            boundsLeft = x.toInt(),
            boundsTop = y.toInt(),
            boundsRight = x.toInt(),
            boundsBottom = y.toInt(),
            confidence = 1f,
            source = DeviceTargetSource.COORDINATE,
            clickable = true,
        )
    }
}
