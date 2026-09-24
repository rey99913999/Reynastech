package io.github.nastechresearch.nastech.data.execution

import android.content.Context
import io.github.nastechresearch.nastech.utils.readClipboardText

fun interface DevicePostconditionVerifier {
    suspend fun verify(
        postcondition: DevicePostcondition,
        before: DeviceObservation? = null,
    ): DeviceVerificationResult
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
                postcondition.type == DevicePostconditionType.NODE_PRESENT ||
                postcondition.type == DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY,
        )

        val value = postcondition.value?.let(::normalizeDeviceText)
        val verified = when (postcondition.type) {
            DevicePostconditionType.APP_FOREGROUND ->
                current.foregroundPackage.equals(postcondition.value.orEmpty(), ignoreCase = true) ||
                    current.foregroundPackage.equals(resolvePackageByLabel(postcondition.value.orEmpty()), ignoreCase = true)

            DevicePostconditionType.NODE_PRESENT,
            DevicePostconditionType.TEXT_PRESENT ->
                !value.isNullOrBlank() &&
                    current.visibleText.any { normalizeDeviceText(it).contains(value) }

            DevicePostconditionType.KEYBOARD_VISIBLE ->
                current.keyboardVisible == postcondition.expected

            DevicePostconditionType.SCREEN_CHANGED,
            DevicePostconditionType.SCREEN_FINGERPRINT_CHANGED ->
                before != null &&
                    before.screenFingerprint.isNotBlank() &&
                    current.screenFingerprint != before.screenFingerprint

            DevicePostconditionType.CLIPBOARD_NON_EMPTY ->
                (context.readClipboardText().trim().isNotEmpty()) == postcondition.expected

            DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY ->
                (current.visibleText.any { it.trim().length >= 2 }) == postcondition.expected

            DevicePostconditionType.TARGET_PROPERTY_TRUE ->
                !value.isNullOrBlank() &&
                    (current.visibleText.any { normalizeDeviceText(it) == value }) == postcondition.expected
        }

        return DeviceVerificationResult(
            verified = verified,
            reason = if (verified) "postcondition_verified" else {
                "postcondition_not_verified:${postcondition.type.name.lowercase()}"
            },
            observationSource = when (postcondition.type) {
                DevicePostconditionType.TEXT_PRESENT,
                DevicePostconditionType.NODE_PRESENT,
                DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY -> "accessibility+ocr"
                else -> "accessibility"
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
