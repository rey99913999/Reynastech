package io.github.nastechresearch.nastech.data.execution

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nastechresearch.nastech.data.vision.VisionPipeline
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService

fun interface AccessibilityTargetLookup {
    suspend fun resolve(target: String): DeviceTargetResolution?
}

fun interface VisionTargetLookup {
    suspend fun resolve(
        target: String,
        minConfidence: Float,
        visionModelId: String?,
    ): DeviceTargetResolution?
}

class AndroidAccessibilityTargetLookup : AccessibilityTargetLookup {
    override suspend fun resolve(target: String): DeviceTargetResolution? {
        val service = RikkaAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val needle = normalizeDeviceText(target)

        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val textNorm = normalizeDeviceText(text)
            val descriptionNorm = normalizeDeviceText(description)

            when {
                textNorm == needle || descriptionNorm == needle -> exact += node
                textNorm.contains(needle) || descriptionNorm.contains(needle) -> partial += node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)
        val nodes = (exact + partial.take(10)).distinct()
        if (nodes.isEmpty()) return null

        val candidates = nodes.mapNotNull { node ->
            val clickable = service.resolveClickable(node)
            val chosen = clickable ?: node
            val bounds = Rect()
            chosen.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return@mapNotNull null
            DeviceTarget(
                semanticName = target,
                clickX = bounds.centerX().toFloat(),
                clickY = bounds.centerY().toFloat(),
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
                confidence = if (normalizeDeviceText(node.text?.toString().orEmpty()) == needle) 1f else 0.94f,
                source = DeviceTargetSource.ACCESSIBILITY,
                clickable = clickable != null,
            )
        }.distinctBy {
            "${it.boundsLeft}:${it.boundsTop}:${it.boundsRight}:${it.boundsBottom}"
        }

        return when (candidates.size) {
            0 -> null
            1 -> DeviceTargetResolution.Resolved(candidates.single())
            else -> DeviceTargetResolution.Ambiguous(target, candidates.take(8))
        }
    }
}

class VisionPipelineTargetLookup(
    private val visionPipeline: VisionPipeline,
) : VisionTargetLookup {
    override suspend fun resolve(
        target: String,
        minConfidence: Float,
        visionModelId: String?,
    ): DeviceTargetResolution? {
        val decision = runCatching {
            visionPipeline.findTarget(
                target = target,
                displayId = 0,
                highConfidence = minConfidence.coerceAtLeast(0.90f),
                mediumConfidence = (minConfidence - 0.10f).coerceAtLeast(0.50f),
                visionModelId = visionModelId,
            )
        }.getOrNull() ?: return null

        val bounds = decision.bounds ?: return DeviceTargetResolution.NotFound(
            target = target,
            reason = "target_not_found:${decision.source}",
        )
        if (decision.confidence < minConfidence) {
            return DeviceTargetResolution.NotFound(
                target = target,
                reason = "target_confidence_below_threshold:${decision.confidence}",
            )
        }

        val source = when (decision.source) {
            "ui_tree" -> DeviceTargetSource.ACCESSIBILITY
            "ocr" -> DeviceTargetSource.OCR
            "vision" -> DeviceTargetSource.VISION
            else -> DeviceTargetSource.NONE
        }
        return DeviceTargetResolution.Resolved(
            DeviceTarget(
                semanticName = target,
                clickX = bounds.centerX(),
                clickY = bounds.centerY(),
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
                confidence = decision.confidence,
                source = source,
                clickable = decision.type?.name != "TEXT",
            ),
        )
    }
}

class DeviceTargetResolver(
    private val cache: DeviceStateCache,
    private val accessibilityLookup: AccessibilityTargetLookup,
    private val visionLookup: VisionTargetLookup,
) {
    suspend fun resolve(
        target: String,
        minConfidence: Float = 0.70f,
        visionModelId: String? = null,
    ): DeviceTargetResolution {
        val key = normalizeDeviceText(target)
        cache.rememberedTarget(key)?.let { remembered ->
            if (remembered.confidence >= minConfidence) {
                return DeviceTargetResolution.Resolved(remembered)
            }
        }

        parseCoordinates(target)?.let { coordinate ->
            cache.rememberTarget(key, coordinate)
            return DeviceTargetResolution.Resolved(coordinate)
        }

        accessibilityLookup.resolve(target)?.let { result ->
            when (result) {
                is DeviceTargetResolution.Resolved -> {
                    cache.rememberTarget(key, result.target)
                    return result
                }
                is DeviceTargetResolution.Ambiguous -> return result
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

        return DeviceTargetResolution.NotFound(target, "target_not_found")
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
