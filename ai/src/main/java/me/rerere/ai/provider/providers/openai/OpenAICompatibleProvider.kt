
package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderCredentialResolver
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.StreamChunk
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationItem
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ProviderCredential
import android.content.Context
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json

/**
 * Adapter for user-owned OpenAI-compatible endpoints and current Hugging Face inference-provider
 * OpenAI-compatible chat endpoints. It deliberately delegates protocol execution to the existing
 * OpenAI implementation so streaming/tool/vision/message handling does not fork.
 */
class OpenAICompatibleProvider(
    client: OkHttpClient,
    context: Context,
    private val credentialResolver: ProviderCredentialResolver,
) : Provider<ProviderSetting.Custom> {

    private val delegate = OpenAIProvider(client = client, context = context)

    private suspend fun runtimeSetting(setting: ProviderSetting.Custom): ProviderSetting.OpenAI {
        val resolved: ProviderCredential? =
            setting.credentialRef.takeIf { it.isNotBlank() }?.let { credentialResolver.resolve(it) }
        return ProviderSetting.OpenAI(
            id = setting.id,
            enabled = setting.enabled,
            name = setting.name,
            models = setting.models,
            balanceOption = setting.balanceOption,
            builtIn = false,
            apiKey = resolved?.apiKey ?: setting.apiKey,
            baseUrl = setting.baseUrl.trimEnd('/'),
            chatCompletionsPath = setting.chatCompletionsPath.ifBlank { "/chat/completions" },
            useResponseApi = false,
            promptCaching = false,
            includeHistoryReasoning = true,
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Custom): List<Model> =
        delegate.listModels(runtimeSetting(providerSetting))

    override suspend fun getBalance(providerSetting: ProviderSetting.Custom): String =
        delegate.getBalance(runtimeSetting(providerSetting))

    override suspend fun generateText(
        providerSetting: ProviderSetting.Custom,
        messages: List<me.rerere.ai.ui.UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult =
        delegate.generateText(runtimeSetting(providerSetting), messages, params)

    override suspend fun streamText(
        providerSetting: ProviderSetting.Custom,
        messages: List<me.rerere.ai.ui.UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> =
        delegate.streamText(runtimeSetting(providerSetting), messages, params)

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.Custom,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult =
        delegate.generateEmbedding(runtimeSetting(providerSetting), params)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        emitAllCompat(delegate.generateImage(runtimeSetting(providerSetting), params))
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        emitAllCompat(delegate.editImage(runtimeSetting(providerSetting), params))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ImageGenerationItem>.emitAllCompat(
        source: Flow<ImageGenerationItem>,
    ) {
        source.collect { emit(it) }
    }
}
