package io.github.nastechresearch.nastech.data.execution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAgentCoreOpenAppTest {
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

    private fun catalog(vararg apps: InstalledAppCandidate): AppResolver =
        AppResolver(InstalledAppCatalog { apps.toList() })

    @Test
    fun openAppProviderGetsHumanReadableNameDirectly() {
        val result = resolveDeviceAppLaunchInvocation(
            registry = registry("open_app"),
            appName = "Gemini",
            appResolver = catalog(
                InstalledAppCandidate("Gemini", "com.google.android.apps.bard", "MainActivity"),
            ),
        )

        assertTrue(result is DeviceAppLaunchInvocation.ToolInvocation)
        val invocation = result as DeviceAppLaunchInvocation.ToolInvocation
        assertEquals("open_app", invocation.provider.providerName)
        assertEquals("Gemini", invocation.args["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun launchAppFallbackResolvesHumanNameToPackage() {
        val result = resolveDeviceAppLaunchInvocation(
            registry = registry("launch_app"),
            appName = "Gemini",
            appResolver = catalog(
                InstalledAppCandidate("Gemini", "com.google.android.apps.bard", "MainActivity"),
            ),
        )

        assertTrue(result is DeviceAppLaunchInvocation.ToolInvocation)
        val invocation = result as DeviceAppLaunchInvocation.ToolInvocation
        assertEquals("launch_app", invocation.provider.providerName)
        assertEquals(
            "com.google.android.apps.bard",
            invocation.args["package_name"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun launchAppFallbackReturnsNotFoundWithoutGuessing() {
        val result = resolveDeviceAppLaunchInvocation(
            registry = registry("launch_app"),
            appName = "Gemini",
            appResolver = catalog(
                InstalledAppCandidate("Calculator", "com.example.calc", "MainActivity"),
            ),
        )

        assertTrue(result is DeviceAppLaunchInvocation.Failure)
        assertEquals("app_not_found", (result as DeviceAppLaunchInvocation.Failure).error)
    }

    @Test
    fun launchAppFallbackReturnsAmbiguousWithoutGuessing() {
        val result = resolveDeviceAppLaunchInvocation(
            registry = registry("launch_app"),
            appName = "Gemini",
            appResolver = catalog(
                InstalledAppCandidate("Gemini", "com.google.android.apps.bard", "MainActivity"),
                InstalledAppCandidate("Gemini", "com.other.gemini", "MainActivity"),
            ),
        )

        assertTrue(result is DeviceAppLaunchInvocation.Failure)
        assertEquals("app_ambiguous", (result as DeviceAppLaunchInvocation.Failure).error)
    }
}
