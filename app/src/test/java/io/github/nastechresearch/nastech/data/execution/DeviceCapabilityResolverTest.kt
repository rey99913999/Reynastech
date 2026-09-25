package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCapabilityResolverTest {
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
    fun appLaunchUsesOpenAppWhenOnlyOpenAppExists() {
        val resolved = DeviceCapabilityResolver.resolve(
            DeviceCapabilityResolver.APP_LAUNCH,
            registry("open_app"),
        )

        assertEquals("open_app", resolved?.providerName)
    }

    @Test
    fun appLaunchFallsBackToLaunchApp() {
        val resolved = DeviceCapabilityResolver.resolve(
            DeviceCapabilityResolver.APP_LAUNCH,
            registry("launch_app"),
        )

        assertEquals("launch_app", resolved?.providerName)
    }

    @Test
    fun appLaunchPrefersOpenAppWhenBothExist() {
        val resolved = DeviceCapabilityResolver.resolve(
            DeviceCapabilityResolver.APP_LAUNCH,
            registry("launch_app", "open_app"),
        )

        assertEquals("open_app", resolved?.providerName)
    }

    @Test
    fun appLaunchIsUnavailableWhenNeitherProviderExists() {
        val resolved = DeviceCapabilityResolver.resolve(
            DeviceCapabilityResolver.APP_LAUNCH,
            registry(),
        )

        assertNull(resolved)
        assertEquals(
            "no_executable_provider:open_app,launch_app",
            DeviceCapabilityResolver.unavailableReason(DeviceCapabilityResolver.APP_LAUNCH),
        )
    }
}
