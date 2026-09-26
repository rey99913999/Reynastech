package io.github.nastechresearch.nastech.data.execution

import android.content.Context
import io.github.nastechresearch.nastech.utils.readClipboardText

fun interface DevicePostconditionVerifier {
    suspend fun verify(
        postcondition: DevicePostcondition,
        before: DeviceObservation?,
    ): DeviceVerificationResult
}

object DeviceObservationVerification {
    private fun textCandidates(
        current: DeviceObservation,
        source: DeviceTextSource,
    ): List<String> = when (source) {
        DeviceTextSource.ACCESSIBILITY -> current.accessibilityText.ifEmpty { current.visibleText }
        DeviceTextSource.OCR -> current.ocrText
        DeviceTextSource.BOTH -> current.visibleText
    }

    fun verify(
        postcondition: DevicePostcondition,
        current: DeviceObservation,
        before: DeviceObservation? = null,
        clipboardNonEmpty: Boolean = false,
        foregroundMatches: Boolean = false,
        accessibilityNodePresent: Boolean = false,
    ): DeviceVerificationResult {
        val value = postcondition.value?.let(::normalizeDeviceText)
        val verified = when (postcondition.type) {
            DevicePostconditionType.APP_FOREGROUND -> foregroundMatches
            DevicePostconditionType.NODE_PRESENT,
            DevicePostconditionType.TEXT_PRESENT ->
                !value.isNullOrBlank() &&
                    textCandidates(current, postcondition.textSource)
                        .any { normalizeDeviceText(it).contains(value) }
            DevicePostconditionType.TEXT_ABSENT ->
                !value.isNullOrBlank() &&
                    textCandidates(current, postcondition.textSource)
                        .none { normalizeDeviceText(it).contains(value) }
            DevicePostconditionType.UI_ELEMENT_PRESENT ->
                accessibilityNodePresent == postcondition.expected
            DevicePostconditionType.KEYBOARD_VISIBLE ->
                current.keyboardVisible == postcondition.expected
            DevicePostconditionType.SCREEN_CHANGED,
            DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED ->
                before != null &&
                    before.screenFingerprint.isNotBlank() &&
                    current.screenFingerprint != before.screenFingerprint
            DevicePostconditionType.SCREEN_STABLE ->
                before != null &&
                    before.screenFingerprint.isNotBlank() &&
                    current.screenFingerprint.isNotBlank() &&
                    before.screenFingerprint == current.screenFingerprint
            DevicePostconditionType.CLIPBOARD_NON_EMPTY ->
                clipboardNonEmpty == postcondition.expected
            DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY ->
                current.visibleText.any { it.trim().length >= 2 } == postcondition.expected
            DevicePostconditionType.TARGET_PROPERTY_TRUE ->
                !value.isNullOrBlank() &&
                    current.visibleText.any { normalizeDeviceText(it) == value } == postcondition.expected
        }

        return DeviceVerificationResult(
            verified = verified,
            reason = if (verified) {
                "postcondition_verified"
            } else {
                "postcondition_not_verified:" + postcondition.type.name.lowercase()
            },
            observationSource = when (postcondition.type) {
                DevicePostconditionType.TEXT_PRESENT,
                DevicePostconditionType.NODE_PRESENT,
                DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY -> "accessibility+ocr"
                else -> "accessibility"
            },
        )
    }
}

class AndroidDeviceStateVerifier(
    private val context: Context,
    private val observer: DeviceObserver,
) : DevicePostconditionVerifier {

    override suspend fun verify(
        postcondition: DevicePostcondition,
        before: DeviceObservation?,
    ): DeviceVerificationResult {
        val current = observer.observe(
            captureScreenshot = postcondition.type == DevicePostconditionType.SCREEN_CHANGED ||
                postcondition.type == DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED,
            captureOcr = postcondition.type == DevicePostconditionType.TEXT_PRESENT ||
                postcondition.type == DevicePostconditionType.TEXT_ABSENT ||
                postcondition.type == DevicePostconditionType.NODE_PRESENT ||
                postcondition.type == DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY,
        )

        if (postcondition.type == DevicePostconditionType.UI_ELEMENT_PRESENT) {
            val selector = postcondition.selector
                ?: return DeviceVerificationResult(false, "selector_required", "accessibility")
            return when (val lookup = observer.findAccessibilityNode(selector)) {
                DeviceAccessibilityQueryResult.Matched ->
                    DeviceObservationVerification.verify(
                        postcondition = postcondition,
                        current = current,
                        before = before,
                        clipboardNonEmpty = context.readClipboardText().trim().isNotEmpty(),
                        accessibilityNodePresent = true,
                    )
                is DeviceAccessibilityQueryResult.NotFound ->
                    DeviceObservationVerification.verify(
                        postcondition = postcondition,
                        current = current,
                        before = before,
                        clipboardNonEmpty = context.readClipboardText().trim().isNotEmpty(),
                        accessibilityNodePresent = false,
                    )
                is DeviceAccessibilityQueryResult.WrongForeground ->
                    DeviceVerificationResult(false, "wrong_foreground_app:" + lookup.currentPackage, "accessibility")
                DeviceAccessibilityQueryResult.Unavailable ->
                    DeviceVerificationResult(false, "accessibility_unavailable", "accessibility")
            }
        }

        return DeviceObservationVerification.verify(
            postcondition = postcondition,
            current = current,
            before = before,
            clipboardNonEmpty = context.readClipboardText().trim().isNotEmpty(),
            accessibilityNodePresent = false,
            foregroundMatches = if (postcondition.type == DevicePostconditionType.APP_FOREGROUND) {
                val expected = postcondition.value.orEmpty()
                current.foregroundPackage.equals(expected, ignoreCase = true) ||
                    current.foregroundPackage.equals(resolvePackageByLabel(expected), ignoreCase = true)
            } else {
                false
            },
        )
    }

    private fun resolvePackageByLabel(label: String): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledApplications(0)
                .firstOrNull {
                    normalizeDeviceText(it.loadLabel(context.packageManager).toString()) ==
                        normalizeDeviceText(label)
                }
                ?.packageName
                .orEmpty()
        }.getOrDefault("")
}
