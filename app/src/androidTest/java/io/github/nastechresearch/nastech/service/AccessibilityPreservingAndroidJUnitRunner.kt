package io.github.nastechresearch.nastech.service

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keep accessibility services enabled during instrumentation. The default UiAutomation
 * connection suppresses them; acquiring it with this flag before AndroidJUnitRunner starts
 * lets the accessibility integration test exercise the real bound service.
 */
class AccessibilityPreservingAndroidJUnitRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // AndroidJUnitRunner may initialize its UiAutomation during super.onCreate;
            // request the preserving flag afterward so the final connection uses it.
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
    }
}
