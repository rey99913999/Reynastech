package io.github.nastechresearch.nastech.data.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateVerifierTest {
    private val before = DeviceObservation(
        foregroundPackage = "com.example.app",
        visibleText = listOf("Open"),
        screenFingerprint = "before",
        keyboardVisible = false,
    )

    private val after = DeviceObservation(
        foregroundPackage = "com.example.app",
        visibleText = listOf("Open", "Done"),
        screenFingerprint = "after",
        keyboardVisible = true,
    )

    @Test
    fun screenChangeAndTextPresenceVerifyLocally() {
        val changed = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(DevicePostconditionType.SCREEN_CHANGED),
            current = after,
            before = before,
        )
        val text = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(DevicePostconditionType.TEXT_PRESENT, "done"),
            current = after,
            before = before,
        )

        assertTrue(changed.verified)
        assertTrue(text.verified)
    }

    @Test
    fun keyboardVisibilityAndResponseNonEmptyAreStructuredChecks() {
        val keyboard = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(
                DevicePostconditionType.KEYBOARD_VISIBLE,
                expected = true,
            ),
            current = after,
            before = before,
        )
        val response = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(
                DevicePostconditionType.RESPONSE_TEXT_NON_EMPTY,
                expected = true,
            ),
            current = after,
            before = before,
        )
        val clipboard = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(
                DevicePostconditionType.CLIPBOARD_NON_EMPTY,
                expected = true,
            ),
            current = after,
            before = before,
            clipboardNonEmpty = false,
        )

        assertTrue(keyboard.verified)
        assertTrue(response.verified)
        assertFalse(clipboard.verified)
    }
    @Test
    fun textAbsenceAndScreenStabilityAreStructuredChecks() {
        val absent = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(DevicePostconditionType.TEXT_ABSENT, "loading"),
            current = after,
            before = before,
        )
        val stable = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(DevicePostconditionType.SCREEN_STABLE),
            current = DeviceObservation(
                foregroundPackage = after.foregroundPackage,
                visibleText = after.visibleText,
                screenFingerprint = after.screenFingerprint,
                keyboardVisible = after.keyboardVisible,
            ),
            before = after,
        )

        assertTrue(absent.verified)
        assertTrue(stable.verified)
    }

    @Test
    fun screenStabilityRequiresTwoObservations() {
        val single = DeviceObservationVerification.verify(
            postcondition = DevicePostcondition(DevicePostconditionType.SCREEN_STABLE),
            current = before,
            before = null,
        )
        assertFalse(single.verified)
    }

}
