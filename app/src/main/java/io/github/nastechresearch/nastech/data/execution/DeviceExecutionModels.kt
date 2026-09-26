package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceCapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    SERVICE_UNAVAILABLE,
    AUTHORIZATION_REQUIRED,
    AUTHORIZATION_DENIED,
}

@Serializable
data class DeviceCapability(
    val name: String,
    val status: DeviceCapabilityStatus,
    val reason: String? = null,
)

@Serializable
data class DeviceCapabilityContract(
    val capabilities: List<DeviceCapability> = emptyList(),
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredConstraints: Set<String> = emptySet(),
) {
    fun capability(name: String): DeviceCapability? = capabilities.firstOrNull { it.name == name }

    fun requiredCapabilityFailure(): DeviceCapability? =
        requiredCapabilities.firstNotNullOfOrNull { name ->
            capability(name)?.takeIf { it.status != DeviceCapabilityStatus.AVAILABLE }
                ?: DeviceCapability(
                    name = name,
                    status = DeviceCapabilityStatus.UNAVAILABLE,
                    reason = "required_capability_not_registered",
                )
        }
}

@Serializable
enum class DevicePostconditionType {
    APP_FOREGROUND,
    NODE_PRESENT,
    TEXT_PRESENT,
    TEXT_ABSENT,
    UI_ELEMENT_PRESENT,
    KEYBOARD_VISIBLE,
    SCREEN_CHANGED,
    SCREEN_FINGERPRINT_CHANGED,
    SCREEN_STABLE,
    CLIPBOARD_NON_EMPTY,
    RESPONSE_TEXT_NON_EMPTY,
    TARGET_PROPERTY_TRUE,
}

@Serializable
@Serializable
@Serializable
enum class DeviceTextSource {
    ACCESSIBILITY,
    OCR,
    BOTH,
}

data class DeviceAccessibilitySelector(
    val by: String,
    val value: String,
    val nth: Int = 0,
    val packageName: String? = null,
)

@Serializable
data class DevicePostcondition(
    val type: DevicePostconditionType,
    val value: String? = null,
    val expected: Boolean = true,
    val timeoutMs: Long = 5_000L,
    val selector: DeviceAccessibilitySelector? = null,
    val textSource: DeviceTextSource = DeviceTextSource.BOTH,
)

@Serializable
enum class DeviceAction {
    OPEN_APP,
    FIND_TARGET,
    TAP_TARGET,
    TYPE_AND_SUBMIT,
    WAIT_FOR_RESPONSE,
    CAPTURE_SCREENSHOT,
    EXTRACT_RESPONSE,
    RETURN_RESULT,
}

enum class DeviceTargetSource {
    ACCESSIBILITY,
    OCR,
    VISION,
    COORDINATE,
    NONE,
}

data class DeviceTarget(
    val semanticName: String,
    val clickX: Float,
    val clickY: Float,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val confidence: Float,
    val source: DeviceTargetSource,
    val clickable: Boolean,
)

sealed interface DeviceTargetResolution {
    data class Resolved(val target: DeviceTarget) : DeviceTargetResolution
    data class Ambiguous(val target: String, val candidates: List<DeviceTarget>) : DeviceTargetResolution
    data class NotFound(val target: String, val reason: String) : DeviceTargetResolution
}

@Serializable
enum class DeviceActionLifecycle {
    DISPATCHED,
    EXECUTED,
    EFFECT_OBSERVED,
    VERIFIED,
    RECOVERED,
}

@Serializable
enum class DeviceArtifactState {
    NONE,
    CAPTURED_NOT_DELIVERED,
    DELIVERED,
}

data class DeviceVerificationResult(
    val verified: Boolean,
    val reason: String,
    val observationSource: String? = null,
)

data class DeviceObservation(
    val foregroundPackage: String = "",
    val windowTitle: String = "",
    val visibleText: List<String> = emptyList(),
    val accessibilityText: List<String> = emptyList(),
    val ocrText: List<String> = emptyList(),
    val focusedText: String? = null,
    val keyboardVisible: Boolean = false,
    val screenFingerprint: String = "",
    val windowTreeFingerprint: String = "",
    val screenshotPath: String? = null,
    val screenshotState: DeviceArtifactState = DeviceArtifactState.NONE,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class DeviceIntentPreflight(
    val isDeviceTask: Boolean = false,
    val confidence: Float = 0f,
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredConstraints: Set<String> = emptySet(),
    val requiresVerifiedOutcome: Boolean = false,
)

internal fun normalizeDeviceText(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase()

internal fun analyzeDeviceIntent(text: String): DeviceIntentPreflight {
    val normalized = normalizeDeviceText(text)
    if (normalized.isBlank()) return DeviceIntentPreflight()

    val appCue =
        Regex("""\b(open|launch|start|switch to|go to)\b""").containsMatchIn(normalized) ||
            listOf("افتح", "شغّل", "شغل", "تشغيل", "انتقل إلى", "انتقل الى").any(normalized::contains)

    val actionCue = listOf(
        "tap", "click", "press", "swipe", "scroll", "screenshot", "take a screenshot",
        "capture screenshot", "read the screen", "find the button", "find the target",
        "type with the keyboard", "use the keyboard", "copy the response",
        "اضغط", "انقر", "اسحب", "مرر", "لقطة شاشة", "التقط لقطة", "اقرأ الشاشة",
        "ابحث عن الزر", "استخدم لوحة المفاتيح", "اكتب باستخدام لوحة المفاتيح", "انسخ الرد",
    ).any(normalized::contains)

    val keyboardCue = listOf(
        "use the keyboard", "use keyboard", "type with the keyboard",
        "keyboard tool", "type this", "enter this",
        "استخدم لوحة المفاتيح", "لوحة المفاتيح", "اكتب باستخدام لوحة المفاتيح",
    ).any(normalized::contains)

    val screenshotCue = listOf(
        "screenshot", "take a screenshot", "capture screenshot", "لقطة شاشة", "التقط لقطة",
    ).any(normalized::contains)

    val phoneCue = appCue || actionCue || keyboardCue ||
        listOf("clipboard", "copy the response", "on my phone", "on the device", "الحافظة", "انسخ")
            .any(normalized::contains)

    val requiresVerifiedOutcome =
        listOf(
            "response", "response content", "copy the response", "clipboard",
            "نسخ الرد", "الحافظة", "النتيجة",
        ).any(normalized::contains)

    val requiredCapabilities = buildSet {
        if (appCue) add("app_launch")
        if (actionCue) add("device_control")
        if (keyboardCue) add("keyboard")
        if (screenshotCue) add("screenshot")
        if (listOf("clipboard", "copy", "الحافظة", "انسخ").any(normalized::contains)) add("clipboard")
    }

    val requiredConstraints = buildSet {
        if (keyboardCue) add("use_keyboard")
        if ("screenshot tool" in normalized) add("use_screenshot_tool")
    }

    return DeviceIntentPreflight(
        isDeviceTask = phoneCue,
        confidence = when {
            !phoneCue -> 0f
            keyboardCue && appCue -> 0.98f
            appCue || actionCue -> 0.94f
            else -> 0.90f
        },
        requiredCapabilities = requiredCapabilities,
        requiredConstraints = requiredConstraints,
        requiresVerifiedOutcome = requiresVerifiedOutcome,
    )
}
