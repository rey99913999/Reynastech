package io.github.nastechresearch.nastech.service

import android.app.UiAutomation
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Accessibility integration tests exercise the app's real service. UiAutomation normally
 * suppresses accessibility services, so force the preserving flag for every automation
 * connection, including any connection requested by AndroidJUnitRunner itself.
 */
class AccessibilityPreservingAndroidJUnitRunner : AndroidJUnitRunner() {

    override fun getUiAutomation(): UiAutomation =
        super.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    override fun getUiAutomation(flags: Int): UiAutomation =
        super.getUiAutomation(flags or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    override fun onCreate(arguments: Bundle?) {
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        super.onCreate(arguments)
    }
}
