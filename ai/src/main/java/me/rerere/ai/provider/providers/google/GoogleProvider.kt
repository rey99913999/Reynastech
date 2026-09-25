package me.rerere.ai.provider.providers.google

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.providers.mediaArtifacts
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.redactSecrets
import me.rerere.ai.util.sanitizeForGeminiSchema
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.android.Logging
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"
private const val GEMINI_STREAM_PATH_SUFFIX = ":streamGenerateContent"
private const val GEMINI_GENERATE_PATH_SUFFIX = ":generateContent"

/**
 * Every category the generative-language API accepts on `safetySettings`.
 */
val GOOGLE_SAFETY_CATEGORIES = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
    "HARM_CATEGORY_CIVIC_INTEGRITY",
)

/**
 * What Cloud Code Assist accepts, which is the same list minus civic integrity: sending that one
 * fails the whole request with a 400 naming the offending element, even though the error text
 * lists the category as allowed.
 */
val CODE_ASSIST_SAFETY_CATEGORIES =
    GOOGLE_SAFETY_CATEGORIES - "HARM_CATEGORY_CIVIC_INTEGRITY"

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }


    private suspend fun buildCompletionRequest(
        providerSetting: ProviderSetting.Google,
        params: TextGenerationParams,
        requestBody: JsonObject,
        streaming: Boolean,
    ): Request {
        val modelPath = if (providerSetting.vertexAI) {
            "publishers/google/models/" + params.model.modelId
        } else {
            "models/" + params.model.modelId
        }
        val operationSuffix =
            if (streaming) GEMINI_STREAM_PATH_SUFFIX else GEMINI_GENERATE_PATH_SUFFIX
        val url = buildUrl(
            providerSetting = providerSetting,
            path = modelPath + operationSuffix,
        ).let { baseUrl ->
            if (streaming) {
                baseUrl.newBuilder()
                    .addQueryParameter("alt", "sse")
                    .build()
            } else {
                baseUrl
            }
        }

        return transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody)
                        .toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )
    }

    private fun redactGeminiImageDataForLogging(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (
                    key == "data" &&
                    element["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("image/") == true
                ) {
                    put(key, "[redacted-image-data]")
                } else {
                    put(key, redactGeminiImageDataForLogging(value))
                }
            }
        }

        is JsonArray -> buildJsonArray {
            element.forEach { add(redactGeminiImageDataForLogging(it)) }
        }

        else -> element
    }

    private fun logStreamRequestBody(
        requestBody: JsonObject,
        mediaSerializationMode: GoogleMediaSerializationMode,
    ) {
        if (Logging.isDebugLoggingEnabled()) {
            val safeBody = redactGeminiImageDataForLogging(redactSecrets(requestBody))
            Log.i(TAG, "streamText[" + mediaSerializationMode + "]: " + safeBody)
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body.string()
                Log.d(TAG, "listModels: $body")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]!!.jsonArray
                            .map { method -> method.jsonPrimitive.content }
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    Model(
                        modelId = modelObject["name"]!!.jsonPrimitive.content.substringAfter("/"),
                        displayName = modelObject["displayName"]!!.jsonPrimitive.content,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                    )
                }
            } else {
                emptyList()
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        fun parseResponse(response: Response): TextGenerationResult {
            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("No candidates in response")
            return TextGenerationResult(
                id = Uuid.random().toString(),
                model = params.model.modelId,
                message = parseMessage(candidate),
                finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
                usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject),
            )
        }

        fun requestFor(mode: GoogleMediaSerializationMode): Request =
            buildCompletionRequest(
                providerSetting = providerSetting,
                params = params,
                requestBody = buildCompletionRequestBody(
                    messages = messages,
                    params = params,
                    mediaSerializationMode = mode,
                ),
                streaming = false,
            )

        val primaryResponse = client.newCall(
            requestFor(
                GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE
            )
        ).await()

        if (primaryResponse.isSuccessful) {
            return@withContext parseResponse(primaryResponse)
        }

        val primaryErrorBody = primaryResponse.body.stringSafe().orEmpty()
        if (GoogleFunctionResponseMediaFallback.shouldAttemptFallback(
                mode = GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
                fallbackAttempted = false,
                meaningfulOutputDelivered = false,
                errorText = primaryErrorBody,
            )
        ) {
            Log.w(
                TAG,
                "Gemini rejected the primary media-reference request; attempting fallback serialization"
            )

            val fallbackResponse = client.newCall(
                requestFor(
                    GoogleMediaSerializationMode.FALLBACK_INLINE_IMAGES
                )
            ).await()

            if (!fallbackResponse.isSuccessful) {
                val fallbackErrorBody = fallbackResponse.body.stringSafe().orEmpty()
                throw Exception(
                    "Failed to get response: " +
                        fallbackResponse.code + " " + fallbackErrorBody
                )
            }

            return@withContext parseResponse(fallbackResponse)
        }

        throw Exception(
            "Failed to get response: " +
                primaryResponse.code + " " + primaryErrorBody
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val responseId = Uuid.random().toString()
        var activeEventSource: EventSource? = null
        var fallbackAttempted = false
        var meaningfulOutputDelivered = false
        var attemptGeneration = 1

        fun meaningfulChunks(chunks: Iterable<StreamChunk>): Boolean =
            chunks.any { chunk ->
                when (chunk) {
                    is StreamChunk.Annotations,
                    is StreamChunk.Usage,
                    is StreamChunk.Finish -> false
                    else -> true
                }
            }

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (" + e?.message + ")")
                }
            }
        }

        fun errorTextAndException(
            throwable: Throwable?,
            response: Response?,
        ): Pair<String, Throwable?> {
            var exception = throwable
            var errorText = throwable?.message.orEmpty()

            try {
                if (response != null) {
                    val bodyStr = response.body.stringSafe().orEmpty()
                    if (bodyStr.isNotEmpty()) {
                        errorText = (errorText + " " + bodyStr).trim()
                        val bodyElement = runCatching {
                            json.parseToJsonElement(bodyStr)
                        }.getOrNull()
                        if (bodyElement is JsonObject) {
                            val providerMessage =
                                bodyElement["error"]?.jsonObject
                                    ?.get("message")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                            if (!providerMessage.isNullOrBlank()) {
                                exception = Exception(providerMessage)
                                errorText =
                                    (errorText + " " + providerMessage).trim()
                            } else {
                                exception = Exception(bodyStr)
                            }
                        } else {
                            exception = Exception(bodyStr)
                        }
                    } else {
                        exception = Exception("Unknown error: " + response.code)
                        errorText =
                            (errorText + " HTTP " + response.code).trim()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "onFailure: failed to parse error body", e)
                exception = e
                errorText = (errorText + " " + e.message.orEmpty()).trim()
            }

            return errorText to exception
        }

        suspend fun openStream(
            mediaSerializationMode: GoogleMediaSerializationMode,
            generation: Int,
        ): EventSource {
            val requestBody = buildCompletionRequestBody(
                messages = messages,
                params = params,
                mediaSerializationMode = mediaSerializationMode,
            )
            logStreamRequestBody(requestBody, mediaSerializationMode)

            val request = buildCompletionRequest(
                providerSetting = providerSetting,
                params = params,
                requestBody = requestBody,
                streaming = true,
            )
            val decoder = GoogleStreamDecoder(responseId, params.model.modelId)

            val listener = object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (generation != attemptGeneration) return
                    Log.i(TAG, "onEvent: " + data)

                    try {
                        val result = decoder.accept(
                            SseEvent(id = id, event = type, data = data)
                        )
                        if (meaningfulChunks(result.chunks)) {
                            meaningfulOutputDelivered = true
                        }
                        sendChunks(result.chunks)
                        if (result.completed) {
                            close()
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Stream terminated: " + data, e)
                        close(e)
                    } catch (e: Throwable) {
                        Log.w(
                            TAG,
                            "onEvent: skipping malformed chunk (" + e.message + ")",
                            e
                        )
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (generation != attemptGeneration) return

                    Log.w(TAG, "onFailure: " + t?.message, t)
                    val (errorText, exception) = errorTextAndException(t, response)

                    if (GoogleFunctionResponseMediaFallback.shouldAttemptFallback(
                            mode = mediaSerializationMode,
                            fallbackAttempted = fallbackAttempted,
                            meaningfulOutputDelivered = meaningfulOutputDelivered,
                            errorText = errorText,
                        )
                    ) {
                        fallbackAttempted = true
                        attemptGeneration += 1
                        val fallbackGeneration = attemptGeneration

                        Log.w(
                            TAG,
                            "Gemini rejected the primary media-reference stream; attempting fallback serialization"
                        )

                        eventSource.cancel()
                        launch {
                            try {
                                activeEventSource = openStream(
                                    mediaSerializationMode =
                                        GoogleMediaSerializationMode.FALLBACK_INLINE_IMAGES,
                                    generation = fallbackGeneration,
                                )
                            } catch (e: Throwable) {
                                close(e)
                            }
                        }
                        return
                    }

                    close(exception ?: Exception("Stream failed"))
                }

                override fun onClosed(eventSource: EventSource) {
                    if (generation != attemptGeneration) return
                    sendChunks(decoder.onClosed())
                    close()
                }
            }

            return EventSources.createFactory(client)
                .newEventSource(request, listener)
        }

        try {
            activeEventSource = openStream(
                mediaSerializationMode =
                    GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
                generation = attemptGeneration,
            )
        } catch (e: Throwable) {
            close(e)
        }

        awaitClose {
            activeEventSource?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    /**
     * Map one decoded `streamGenerateContent` payload onto a [MessageChunk], or null when it
     * carries no candidates yet.
     *
     * Public alongside [buildCompletionRequestBody] so the Cloud Code Assist transport can reuse
     * the Gemini wire format it wraps: that API takes the same request body under a `request` key
     * and returns the same candidates under a `response` key, so a second copy of the part
     * decoding would only give the two transports room to drift apart.
     */
    fun parseStreamCandidates(jsonData: JsonObject, model: Model): MessageChunk? {
        val candidates = jsonData["candidates"]?.jsonArray ?: return null
        if (candidates.isEmpty()) return null
        return MessageChunk(
            id = Uuid.random().toString(),
            model = model.modelId,
            choices = candidates.mapIndexed { index, candidate ->
                val candidateObj = candidate.jsonObject
                val content = candidateObj["content"]?.jsonObject
                val groundingMetadata = candidateObj["groundingMetadata"]?.jsonObject
                val finishReason = candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull

                val message = content?.let {
                    parseMessage(buildJsonObject {
                        put("role", JsonPrimitive("model"))
                        put("content", it)
                        groundingMetadata?.let { groundingMetadata ->
                            put("groundingMetadata", groundingMetadata)
                        }
                    })
                }

                UIMessageChoice(
                    index = index,
                    delta = message,
                    message = null,
                    finishReason = finishReason
                )
            },
            usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
        )
    }

    fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        safetyCategories: List<String> = GOOGLE_SAFETY_CATEGORIES,
    ): JsonObject = buildCompletionRequestBody(
        messages = messages,
        params = params,
        safetyCategories = safetyCategories,
        mediaSerializationMode =
            GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
    )

    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        safetyCategories: List<String>,
        mediaSerializationMode: GoogleMediaSerializationMode,
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                put("thinkingLevel", "MINIMAL")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "LOW")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "MEDIUM")
                                    else -> put("thinkingLevel", "HIGH") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(
                messages = messages,
                modelId = params.model.modelId,
                mediaSerializationMode = mediaSerializationMode,
            )
        )

        // Tools — function tools and model built-in tools both live under the same
        // "tools" key. Writing them via two separate put("tools", ...) calls made the
        // second overwrite the first outright (JsonObjectBuilder.put replaces an
        // existing key), so built-in tools silently clobbered every function tool
        // whenever both were present. Build one array covering both.
        val hasFunctionTools = params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val hasBuiltInTools = params.model.tools.isNotEmpty()
        if (hasFunctionTools || hasBuiltInTools) {
            put("tools", buildJsonArray {
                if (hasFunctionTools) {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    val parameters = tool.parameters()
                                    if (parameters != null) {
                                        put(
                                            key = "parameters",
                                            element = json.encodeToJsonElement(parameters)
                                                .sanitizeForGeminiSchema()
                                        )
                                    }
                                })
                            }
                        })
                    })
                }
                if (hasBuiltInTools) {
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("googleSearch", buildJsonObject {})
                                })
                            }

                            BuiltInTools.UrlContext -> {
                                add(buildJsonObject {
                                    put("urlContext", buildJsonObject {})
                                })
                            }

                            else -> {}
                        }
                    }
                }
            })
        }
        if (hasFunctionTools && hasBuiltInTools) {
            put("toolConfig", buildJsonObject {
                put("includeServerSideToolInvocations", true)
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            safetyCategories.forEach { category ->
                add(buildJsonObject {
                    put("category", category)
                    put("threshold", "OFF")
                })
            }
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // in google api, tool results are sent as the user role
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = content["parts"]?.jsonArray?.mapNotNull { part ->
            parseMessagePart(part.jsonObject)
        } ?: emptyList()

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessagePart(jsonObject: JsonObject): UIMessagePart? {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = thoughtSignature?.let {
                        buildJsonObject { put("thoughtSignature", JsonPrimitive(it)) }
                    },
                ) else UIMessagePart.Text(text)
            }

            jsonObject.containsKey("functionCall") -> {
                UIMessagePart.Tool(
                    toolCallId = Uuid.random().toString(),
                    toolName = jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(jsonObject["functionCall"]!!.jsonObject["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    // Every other producer/consumer in this codebase (Base64ImageToLocalFileTransformer,
                    // FileEncoder.encodeBase64, etc.) expects a proper data URL, not a bare payload -
                    // see issue #37.
                    url = "data:$mime;base64,$data",
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )
            }

            else -> {
                Log.w(TAG, "parseMessagePart: skipping unrecognized part, keys=${jsonObject.keys}")
                null
            }
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray =
        buildContents(
            messages,
            null,
            GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
        )

    private fun buildContents(messages: List<UIMessage>, modelId: String?): JsonArray =
        buildContents(
            messages,
            modelId,
            GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
        )

    private fun buildContents(
        messages: List<UIMessage>,
        modelId: String?,
        mediaSerializationMode: GoogleMediaSerializationMode,
    ): JsonArray {
        val mediaReferenceAllocator = GoogleMediaReferenceAllocator()
        val supportsMultimodalFunctionResponses =
            GoogleFunctionResponseMediaSerializer.supportsMultimodalFunctionResponses(modelId)
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(
                            message = message,
                            mediaReferenceAllocator = mediaReferenceAllocator,
                            allowMultimodal = supportsMultimodalFunctionResponses,
                            mediaSerializationMode = mediaSerializationMode,
                        )
                    } else {
                        addUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(
        message: UIMessage,
        mediaReferenceAllocator: GoogleMediaReferenceAllocator,
        allowMultimodal: Boolean,
        mediaSerializationMode: GoogleMediaSerializationMode,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()
        // Forward thoughtSignature from any preceding Reasoning part to the next Tool
        // part that doesn't already carry one. Gemini emits the signature on the thought
        // (text + thought=true), but Reasoning parts are not sent back to Gemini in
        // continuation requests — without forwarding, the next functionCall arrives
        // unsigned and Gemini rejects with "Function call is missing a thought_signature
        // in functionCall parts". Tracked across the message's parts list so cross-chunk
        // streaming (thought in chunk N, functionCall in chunk N+1) still attaches the
        // signature when the assistant message is finally serialized.
        var carriedSig: String? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // Track most recent reasoning signature for the next tool group.
                    group.parts.forEach { part ->
                        if (part is UIMessagePart.Reasoning) {
                            part.metadata?.get("thoughtSignature")
                                ?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let { carriedSig = it }
                        }
                    }
                    group.parts.mapNotNull { it.toGooglePart() }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { tool ->
                        val effective = if (
                            tool.metadata?.get("thoughtSignature")?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                            && carriedSig != null
                        ) {
                            tool.copy(metadata = buildJsonObject {
                                put("thoughtSignature", JsonPrimitive(carriedSig))
                            })
                        } else tool
                        partsBuffer.add(effective.toFunctionCallPart())
                    }
                    carriedSig = null  // consumed by this tool group

                    // 输出 model 消息 (skip if every part dropped - an empty "parts" array is an
                    // invalid Google payload, matching the guard on the tail flush below)
                    if (partsBuffer.isNotEmpty()) {
                        add(buildJsonObject {
                            put("role", "model")
                            putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                        })
                    }
                    partsBuffer.clear()

                    // Keep the function response immediately after its function call.
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach {
                                add(
                                it.toFunctionResponseParts(
                                    mediaReferenceAllocator = mediaReferenceAllocator,
                                    allowMultimodal = allowMultimodal,
                                    mediaSerializationMode = mediaSerializationMode,
                                ).forEach { add(it) }
                                )
                            }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        val parts = message.parts.mapNotNull { it.toGooglePart() }
        // Skip the turn entirely if every part was dropped (e.g. an unencodable image) - an
        // empty "parts" array is an invalid Google payload.
        if (parts.isEmpty()) return
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") { parts.forEach { add(it) } }
        })
    }

    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
        }

        is UIMessagePart.Image -> {
            val result = encodeBase64(false)
            if (result.isFailure) {
                logDroppedPart("Image", url)
            }
            result.getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            val result = encodeBase64(false)
            if (result.isFailure) {
                logDroppedPart("Video", url)
            }
            result.getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            val result = encodeBase64(false)
            if (result.isFailure) {
                logDroppedPart("Audio", url)
            }
            result.getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    // Never log the payload itself - only the part type and the url scheme - so this stays safe
    // even though the caller passes a base64 payload's URL.
    private fun logDroppedPart(partType: String, url: String) {
        val scheme = url.substringBefore(':', missingDelimiterValue = "none")
        Log.w(TAG, "toGooglePart: dropping unencodable $partType part, url scheme=$scheme")
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            if (toolCallId.isNotBlank()) {
                put("id", toolCallId)
            }
            put("name", toolName)
            put("args", inputAsJson())
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponseParts(
        mediaReferenceAllocator: GoogleMediaReferenceAllocator,
        allowMultimodal: Boolean,
        mediaSerializationMode: GoogleMediaSerializationMode,
    ): List<JsonObject> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val mediaArtifacts = mediaArtifacts()
        val encodingResults = mediaArtifacts.map { artifact ->
            val displayName = mediaReferenceAllocator.next()
            GoogleFunctionResponseMediaSerializer.encode(
                artifact = artifact,
                displayName = displayName,
                allowMultimodal = allowMultimodal,
            )
        }

        val successfulMedia = encodingResults.mapNotNull { result ->
            (result as? GoogleMediaEncodingResult.Encoded)?.media
        }
        val mediaFailures = encodingResults.mapNotNull { result ->
            result as? GoogleMediaEncodingResult.Skipped
        }

        val response = buildJsonObject {
            if (textParts.isNotEmpty()) {
                put("result", textParts.joinToString("\n") { it.text })
            } else if (successfulMedia.isEmpty()) {
                put(
                    "result",
                    if (mediaFailures.isEmpty()) {
                        " "
                    } else {
                        "Media output was not attached inline; see media_errors for details."
                    }
                )
            }

            if (
                mediaSerializationMode ==
                    GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE
            ) {
                successfulMedia.forEach { media ->
                    put(media.displayName, buildJsonObject {
                        put("\$ref", media.displayName)
                    })
                }
            }

            if (mediaFailures.isNotEmpty()) {
                putJsonArray("media_errors") {
                    mediaFailures.forEach { failure ->
                        add(buildJsonObject {
                            put("media_id", failure.artifact.id)
                            put("mime_type", failure.artifact.mimeType)
                            put("reference", failure.artifact.contentRef)
                            put("reason", failure.reason)
                        })
                    }
                }
            }
        }

        val primaryInlineParts = successfulMedia.map { media ->
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", media.mimeType)
                    put("data", media.data)
                    put("displayName", media.displayName)
                })
            }
        }

        if (
            mediaSerializationMode ==
                GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE
        ) {
            check(
                GoogleFunctionResponseMediaSerializer.validateMultimodalReferences(
                    response = response,
                    inlineDataDisplayNames = successfulMedia.map { it.displayName },
                )
            ) {
                "Invalid Gemini function-response media reference mapping"
            }
        }

        val functionResponsePart = buildJsonObject {
            put("functionResponse", buildJsonObject {
                if (toolCallId.isNotBlank()) {
                    put("id", toolCallId)
                }
                put("name", toolName)
                put("response", response)

                if (
                    mediaSerializationMode ==
                        GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE &&
                    primaryInlineParts.isNotEmpty()
                ) {
                    putJsonArray("parts") {
                        primaryInlineParts.forEach { add(it) }
                    }
                }
            })
        }

        if (
            mediaSerializationMode ==
                GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE ||
            successfulMedia.isEmpty()
        ) {
            return listOf(functionResponsePart)
        }

        return buildList {
            add(functionResponsePart)
            successfulMedia.forEach { media ->
                add(
                    buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", media.mimeType)
                            put("data", media.data)
                        })
                    }
                )
            }
        }
    }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.Google) {
            "Expected Google provider setting"
        }

        val items = withContext(Dispatchers.IO) {
            val requestBody = buildJsonObject {
                putJsonArray("instances") {
                    add(buildJsonObject {
                        put("prompt", params.prompt)
                    })
                }
                putJsonObject("parameters") {
                    put("sampleCount", params.numOfImages)
                    put(
                        "aspectRatio", when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1:1"
                            ImageAspectRatio.LANDSCAPE -> "16:9"
                            ImageAspectRatio.PORTRAIT -> "9:16"
                        }
                    )
                }
            }.mergeCustomBody(params.customBody)

            val url = buildUrl(
                providerSetting = providerSetting,
                path = if (providerSetting.vertexAI) {
                    "publishers/google/models/${params.model.modelId}:predict"
                } else {
                    "models/${params.model.modelId}:predict"
                }
            )

            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(params.customHeaders.toHeaders())
                    .post(
                        json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                    )
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build()
            )

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

            val predictions = bodyJson["predictions"]?.jsonArray ?: error("No predictions in response")

            predictions.mapNotNull { prediction ->
                val predictionObj = prediction.jsonObject
                val bytesBase64Encoded = predictionObj["bytesBase64Encoded"]?.jsonPrimitive?.contentOrNull

                if (bytesBase64Encoded != null) {
                    ImageGenerationItem(
                        data = bytesBase64Encoded,
                        mimeType = "image/png"
                    )
                } else {
                    null
                }
            }
        }

        if (items.isEmpty()) error("No images in response (the model may have refused the prompt).")

        items.forEach { item ->
            emit(item)
        }
    }
}
