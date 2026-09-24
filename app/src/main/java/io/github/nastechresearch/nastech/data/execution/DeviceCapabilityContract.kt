package io.github.nastechresearch.nastech.data.execution

import android.content.Context
import android.os.Build
import io.github.nastechresearch.nastech.data.keyboard.KeyboardApiClient
import io.github.nastechresearch.nastech.shizuku.ShizukuManager
import io.github.nastechresearch.nastech.service.RikkaAccessibilityService

object DeviceCapabilityContractBuilder {
    fun build(
        context: Context,
        registry: ExecutionToolRegistry,
        requiredCapabilities: Set<String>,
        requiredConstraints: Set<String>,
    ): DeviceCapabilityContract {
        val capabilities = listOf(
            capability(
                name = "device_control",
                registry = registry,
                toolName = "tap",
                available = RikkaAccessibilityService.instance != null,
                reason = "AccessibilityService is not active",
            ),
            capability(
                name = "app_launch",
                registry = registry,
                toolName = "open_app",
                available = registry.find("open_app") != null,
                reason = "open_app is not registered for this Assistant",
            ),
            capability(
                name = "keyboard",
                registry = registry,
                toolName = "keyboard_type",
                available = registry.find("keyboard_type") != null &&
                    KeyboardApiClient(context).isKeyboardInstalled(),
                reason = "agent-keyboard is unavailable",
            ),
            capability(
                name = "screen_reading",
                registry = registry,
                toolName = "read_window_tree",
                available = registry.find("read_window_tree") != null &&
                    RikkaAccessibilityService.instance != null,
                reason = "screen reader is unavailable",
            ),
            capability(
                name = "screenshot",
                registry = registry,
                toolName = "take_screenshot",
                available = registry.find("take_screenshot") != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    RikkaAccessibilityService.instance != null,
                reason = "screenshot API is unavailable",
            ),
            capability(
                name = "clipboard",
                registry = registry,
                toolName = "clipboard_tool",
                available = registry.find("clipboard_tool") != null,
                reason = "clipboard tool is not enabled",
            ),
            capability(
                name = "shizuku",
                registry = registry,
                toolName = "shizuku_exec",
                available = registry.find("shizuku_exec") != null &&
                    ShizukuManager.status(context).name == "READY",
                reason = "Shizuku is not ready",
            ),
        )

        return DeviceCapabilityContract(
            capabilities = capabilities,
            requiredCapabilities = requiredCapabilities,
            requiredConstraints = requiredConstraints,
        )
    }

    private fun capability(
        name: String,
        registry: ExecutionToolRegistry,
        toolName: String,
        available: Boolean,
        reason: String,
    ): DeviceCapability {
        if (registry.find(toolName) == null) {
            return DeviceCapability(
                name = name,
                status = DeviceCapabilityStatus.UNAVAILABLE,
                reason = reason,
            )
        }
        return DeviceCapability(
            name = name,
            status = if (available) DeviceCapabilityStatus.AVAILABLE
            else DeviceCapabilityStatus.SERVICE_UNAVAILABLE,
            reason = if (available) null else reason,
        )
    }
}
