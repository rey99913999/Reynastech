
package me.rerere.ai.provider

/**
 * Central protocol/adaptor catalogue. This is deliberately metadata-driven so adding a new
 * compatible service means adding a descriptor or a future adapter, not another hard-coded UI path.
 */
@kotlinx.serialization.Serializable
enum class ProviderAdapterType {
    OPENAI,
    GOOGLE,
    CLAUDE,
    CODEX_OAUTH,
    GROK_OAUTH,
    GEMINI_OAUTH,
    OPENAI_COMPATIBLE,
    HUGGING_FACE,
}

data class ProviderProtocolDescriptor(
    val id: String,
    val displayName: String,
    val adapterType: ProviderAdapterType,
    val defaultBaseUrl: String,
    val requiresApiKey: Boolean,
    val supportsDiscovery: Boolean = true,
    val local: Boolean = false,
)

object ProviderRegistry {
    val descriptors: List<ProviderProtocolDescriptor> = listOf(
        ProviderProtocolDescriptor("openai", "OpenAI", ProviderAdapterType.OPENAI, "https://api.openai.com/v1", true),
        ProviderProtocolDescriptor("google", "Google / Gemini", ProviderAdapterType.GOOGLE, "https://generativelanguage.googleapis.com/v1beta", true),
        ProviderProtocolDescriptor("anthropic", "Claude / Anthropic", ProviderAdapterType.CLAUDE, "https://api.anthropic.com/v1", true),
        ProviderProtocolDescriptor("codex", "Codex OAuth", ProviderAdapterType.CODEX_OAUTH, "", false, supportsDiscovery = false),
        ProviderProtocolDescriptor("grok", "Grok OAuth", ProviderAdapterType.GROK_OAUTH, "", false, supportsDiscovery = false),
        ProviderProtocolDescriptor("gemini_oauth", "Gemini OAuth", ProviderAdapterType.GEMINI_OAUTH, "", false, supportsDiscovery = false),
        ProviderProtocolDescriptor("openai_compatible", "OpenAI Compatible", ProviderAdapterType.OPENAI_COMPATIBLE, "https://api.example.com/v1", true),
        ProviderProtocolDescriptor("hugging_face", "Hugging Face Inference Providers", ProviderAdapterType.HUGGING_FACE, "https://router.huggingface.co/v1", true),
        ProviderProtocolDescriptor("ollama", "Ollama", ProviderAdapterType.OPENAI_COMPATIBLE, "http://127.0.0.1:11434/v1", false, local = true),
        ProviderProtocolDescriptor("llama_cpp", "llama.cpp server", ProviderAdapterType.OPENAI_COMPATIBLE, "http://127.0.0.1:8080/v1", false, local = true),
        ProviderProtocolDescriptor("lm_studio", "LM Studio", ProviderAdapterType.OPENAI_COMPATIBLE, "http://127.0.0.1:1234/v1", false, local = true),
    )

    val addProviderDescriptors: List<ProviderProtocolDescriptor> =
        descriptors.filter { it.adapterType in setOf(
            ProviderAdapterType.OPENAI_COMPATIBLE,
            ProviderAdapterType.HUGGING_FACE,
        ) || it.local }

    fun descriptor(id: String): ProviderProtocolDescriptor? =
        descriptors.firstOrNull { it.id == id }

    fun descriptorFor(setting: ProviderSetting): ProviderProtocolDescriptor? =
        when (setting) {
            is ProviderSetting.OpenAI -> descriptor("openai")
            is ProviderSetting.Google -> descriptor("google")
            is ProviderSetting.Claude -> descriptor("anthropic")
            is ProviderSetting.Codex -> descriptor("codex")
            is ProviderSetting.Grok -> descriptor("grok")
            is ProviderSetting.GeminiOAuth -> descriptor("gemini_oauth")
            is ProviderSetting.Custom -> descriptor(setting.protocolId)
        }

    fun isKnownProtocol(protocolId: String): Boolean = descriptor(protocolId) != null

    fun defaultUrl(protocolId: String): String = descriptor(protocolId)?.defaultBaseUrl.orEmpty()
}
