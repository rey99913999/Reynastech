package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceCapabilityContractTest {
    private fun tool(name: String): Tool = Tool(
        name = name,
        description = name,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("value", buildJsonObject {
                        put("type", "string")
                    })
                },
            )
        },
        execute = { emptyList() },
    )

    private fun registry(vararg names: String): ExecutionToolRegistry =
        ExecutionToolRegistry(names.map(::tool)).also {
            it.loadTools(names.toList())
        }

    @Test
    fun launchAppProviderMakesAppLaunchAvailable() {
        val capability = DeviceCapabilityContractBuilder.appLaunchCapability(
            registry("launch_app"),
        )

        assertEquals(DeviceCapabilityStatus.AVAILABLE, capability.status)
        assertEquals(null, capability.reason)
    }

    @Test
    fun missingAppLaunchProvidersRemainUnavailable() {
        val capability = DeviceCapabilityContractBuilder.appLaunchCapability(
            registry(),
        )

        assertNotEquals(DeviceCapabilityStatus.AVAILABLE, capability.status)
        assertEquals(
            "no_executable_provider:open_app,launch_app",
            capability.reason,
        )
    }

    @Test
    fun genericCapabilityStatusSemanticsRemainStable() {
        val registered = registry("some_tool")

        assertEquals(
            DeviceCapabilityStatus.AVAILABLE,
            DeviceCapabilityContractBuilder.capability(
                name = "generic",
                registry = registered,
                toolName = "some_tool",
                available = true,
                reason = "not available",
            ).status,
        )
        assertEquals(
            DeviceCapabilityStatus.SERVICE_UNAVAILABLE,
            DeviceCapabilityContractBuilder.capability(
                name = "generic",
                registry = registered,
                toolName = "some_tool",
                available = false,
                reason = "service unavailable",
            ).status,
        )
        assertEquals(
            DeviceCapabilityStatus.UNAVAILABLE,
            DeviceCapabilityContractBuilder.capability(
                name = "generic",
                registry = registry(),
                toolName = "some_tool",
                available = true,
                reason = "not registered",
            ).status,
        )
    }
}
