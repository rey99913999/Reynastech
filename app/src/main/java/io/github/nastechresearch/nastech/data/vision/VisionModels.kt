package io.github.nastechresearch.nastech.data.vision

import kotlinx.serialization.Serializable

@Serializable
enum class ScreenElementType {
    TEXT, BUTTON, INPUT, SWITCH, CHECKBOX, LIST, DIALOG, ICON, CLICKABLE_REGION
}

@Serializable
data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f
    fun width(): Int = (right - left).coerceAtLeast(0)
    fun height(): Int = (bottom - top).coerceAtLeast(0)
}

@Serializable
data class ScreenElement(
    val type: ScreenElementType,
    val text: String = "",
    val bounds: ScreenBounds,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val checked: Boolean? = null,
    val confidence: Float = 1f,
    val source: String,
)

@Serializable
data class OcrTextBlock(
    val text: String,
    val bounds: ScreenBounds,
    val confidence: Float,
    val source: String = "ocr",
)

@Serializable
data class ScreenObservation(
    val packageName: String = "",
    val windowTitle: String = "",
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val elements: List<ScreenElement> = emptyList(),
    val ocr: List<OcrTextBlock> = emptyList(),
    val createdAtMs: Long = 0L,
    val fingerprint: String = "",
)

@Serializable
data class VisionDecision(
    val target: String,
    val type: ScreenElementType? = null,
    val bounds: ScreenBounds? = null,
    val confidence: Float,
    val state: String = "",
    val source: String,
    val relation: String? = null,
    val enabled: Boolean = true,
    val safeToUseForSensitiveAction: Boolean = false,
)

data class VisionConfidencePolicy(
    val high: Float = 0.90f,
    val medium: Float = 0.70f,
) {
    init {
        require(high in 0f..1f && medium in 0f..1f && medium <= high)
    }

    fun band(confidence: Float): String = when {
        confidence >= high -> "high"
        confidence >= medium -> "medium"
        else -> "low"
    }

    fun sensitiveActionAllowed(confidence: Float): Boolean = confidence >= high
}
