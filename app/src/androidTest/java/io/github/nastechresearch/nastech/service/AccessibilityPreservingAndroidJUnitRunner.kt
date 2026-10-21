package io.github.nastechresearch.nastech.service

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keep accessibility services enabled during instrumentation. The default UiAutomation
 * connection suppresses them; request the preserving flag before AndroidJUnitRunner can
 * create its default automation connection.
 */
class AccessibilityPreservingAndroidJUnitRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
        super.onCreate(arguments)
    }
}
