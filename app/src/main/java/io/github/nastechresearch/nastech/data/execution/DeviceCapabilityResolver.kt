package io.github.nastechresearch.nastech.data.execution

import me.rerere.ai.core.Tool

/**
 * Resolves logical Device Core capabilities to the provider that is actually executable in
 * the current runtime registry.
 *
 * This is deliberately independent of the LLM and of tool discovery: the registry remains the
 * source of truth for runtime availability while this object owns the logical capability mapping.
 */
data class ResolvedDeviceCapability(
    val logicalCapability: String,
    val providerName: String,
    val tool: Tool,
)

object DeviceCapabilityResolver {
    const val APP_LAUNCH = "app_launch"

    private val providerNamesByCapability: Map<String, List<String>> = mapOf(
        APP_LAUNCH to listOf("open_app", "launch_app"),
    )

    fun candidateProviderNames(logicalCapability: String): List<String> =
        providerNamesByCapability[logicalCapability].orEmpty()

    fun resolve(
        logicalCapability: String,
        registry: ExecutionToolRegistry,
    ): ResolvedDeviceCapability? =
        candidateProviderNames(logicalCapability)
            .asSequence()
            .mapNotNull { providerName ->
                registry.findLoaded(providerName)?.let { tool ->
                    ResolvedDeviceCapability(
                        logicalCapability = logicalCapability,
                        providerName = providerName,
                        tool = tool,
                    )
                }
            }
            .firstOrNull()

    fun unavailableReason(logicalCapability: String): String {
        val candidates = candidateProviderNames(logicalCapability)
        return if (candidates.isEmpty()) {
            "unknown_capability"
        } else {
            "no_executable_provider:" + candidates.joinToString(",")
        }
    }
}
