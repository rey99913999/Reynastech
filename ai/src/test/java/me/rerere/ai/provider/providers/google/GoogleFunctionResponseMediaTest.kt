package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleFunctionResponseMediaTest {

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    private fun invokeBuildContents(
        messages: List<UIMessage>,
        modelId: String = "gemini-3-flash",
        mode: GoogleMediaSerializationMode =
            GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE,
    ): JsonArray {
        val method = if (
            mode == GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE
        ) {
            GoogleProvider::class.java.getDeclaredMethod(
                "buildContents",
                List::class.java,
                String::class.java,
            )
        } else {
            GoogleProvider::class.java.getDeclaredMethod(
                "buildContents",
                List::class.java,
                String::class.java,
                GoogleMediaSerializationMode::class.java,
            )
        }
        method.isAccessible = true
        return if (
            mode == GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE
        ) {
            method.invoke(provider, messages, modelId) as JsonArray
        } else {
            method.invoke(provider, messages, modelId, mode) as JsonArray
        }
    }

    @Test
    fun `single image keeps ref and displayName exactly aligned`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("take a screenshot"),
                assistantWithTool(
                    toolId = "call-shot-1",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Image("data:image/png;base64,aGVsbG8="),
                        UIMessagePart.Text("screenshot captured"),
                    ),
                ),
            )
        )

        val response = functionResponses(result).single()
        val responseBody = response["response"]!!.jsonObject
        val refEntry = responseBody.entries
            .first { it.key.startsWith("nastech_media_") }

        val ref = refEntry.value.jsonObject["\$ref"]!!.jsonPrimitive.content
        val mediaPart = response["parts"]!!.jsonArray.single()
            .jsonObject["inlineData"]!!.jsonObject

        assertEquals(refEntry.key, ref)
        assertEquals(ref, mediaPart["displayName"]!!.jsonPrimitive.content)
        assertEquals("image/png", mediaPart["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multiple images in one tool result stay unique and ordered`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("show two screenshots"),
                assistantWithTool(
                    toolId = "call-shot-2",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Image("data:image/png;base64,one"),
                        UIMessagePart.Image("data:image/png;base64,two"),
                    ),
                ),
            )
        )

        val response = functionResponses(result).single()
        val body = response["response"]!!.jsonObject
        val refs = body.entries
            .filter { it.key.startsWith("nastech_media_") }
            .map { it.value.jsonObject["\$ref"]!!.jsonPrimitive.content }
        val parts = response["parts"]!!.jsonArray.map {
            it.jsonObject["inlineData"]!!.jsonObject
        }

        assertEquals(listOf("nastech_media_0", "nastech_media_1"), refs)
        assertEquals(refs, parts.map { it["displayName"]!!.jsonPrimitive.content })
        assertEquals(listOf("one", "two"), parts.map { it["data"]!!.jsonPrimitive.content })
    }

    @Test
    fun `multiple media tools share one request without reference collisions`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("do two screenshots"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        executedTool(
                            "call-one",
                            "take_screenshot",
                            listOf(UIMessagePart.Image("data:image/png;base64,one")),
                        ),
                        executedTool(
                            "call-action",
                            "tap",
                            listOf(UIMessagePart.Text("tap ok")),
                        ),
                        executedTool(
                            "call-two",
                            "take_screenshot",
                            listOf(UIMessagePart.Image("data:image/png;base64,two")),
                        ),
                    ),
                ),
            )
        )

        val responses = functionResponses(result)
        assertEquals(3, responses.size)
        val mediaParts = responses.mapNotNull {
            it["parts"]?.jsonArray?.firstOrNull()?.jsonObject
        }.map { it["inlineData"]!!.jsonObject }

        assertEquals(2, mediaParts.size)
        assertEquals(
            listOf("nastech_media_0", "nastech_media_1"),
            mediaParts.map { it["displayName"]!!.jsonPrimitive.content }
        )
        assertEquals(
            listOf("one", "two"),
            mediaParts.map { it["data"]!!.jsonPrimitive.content }
        )
    }

    @Test
    fun `text and image both survive in the same tool result`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("screenshot"),
                assistantWithTool(
                    toolId = "call-mixed",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Text("done"),
                        UIMessagePart.Image("data:image/jpeg;base64,image"),
                    ),
                ),
            )
        )

        val response = functionResponses(result).single()
        val responseBody = response["response"]!!.jsonObject

        assertEquals("done", responseBody["result"]!!.jsonPrimitive.content)
        assertNotNull(response["parts"])
        assertEquals(
            "image",
            response["parts"]!!.jsonArray.single().jsonObject["inlineData"]!!
                .jsonObject["data"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `consecutive screenshot action screenshot can build the next request`() {
        val messages = listOf(
            UIMessage.user("perform the action"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    executedTool(
                        "shot-1",
                        "take_screenshot",
                        listOf(UIMessagePart.Image("data:image/png;base64,first")),
                    ),
                    executedTool(
                        "tap-1",
                        "tap",
                        listOf(UIMessagePart.Text("tap ok")),
                    ),
                    executedTool(
                        "shot-2",
                        "take_screenshot",
                        listOf(UIMessagePart.Image("data:image/png;base64,second")),
                    ),
                ),
            ),
        )

        val result = invokeBuildContents(messages)
        val responses = functionResponses(result)

        assertEquals(3, responses.size)
        assertEquals(
            listOf("first", "second"),
            responses.mapNotNull {
                it["parts"]?.jsonArray?.firstOrNull()?.jsonObject
            }.map { it["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content }
        )
    }


    @Test
    fun `fallback moves images outside function response and preserves text and bytes`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("screenshot"),
                assistantWithTool(
                    toolId = "call-fallback",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Text("done"),
                        UIMessagePart.Image("data:image/png;base64,actual-image"),
                    ),
                ),
            ),
            mode = GoogleMediaSerializationMode.FALLBACK_INLINE_IMAGES,
        )

        val response = functionResponses(result).single()
        val functionResponse = response["functionResponse"]!!.jsonObject
        val responseBody = functionResponse["response"]!!.jsonObject

        assertEquals("done", responseBody["result"]!!.jsonPrimitive.content)
        assertTrue(responseBody.keys.none { it.startsWith("nastech_media_") })
        assertFalse(functionResponse.containsKey("parts"))

        val images = result.flatMap { message ->
            message.jsonObject["parts"]?.jsonArray.orEmpty().mapNotNull { part ->
                part.jsonObject["inlineData"]?.jsonObject
            }
        }
        assertEquals(1, images.size)
        assertEquals("image/png", images.single()["mimeType"]!!.jsonPrimitive.content)
        assertEquals("actual-image", images.single()["data"]!!.jsonPrimitive.content)
        assertFalse(images.single().containsKey("displayName"))
    }

    @Test
    fun `fallback preserves multiple images in original order`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("show two screenshots"),
                assistantWithTool(
                    toolId = "call-fallback-multi",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Image("data:image/png;base64,first"),
                        UIMessagePart.Image("data:image/jpeg;base64,second"),
                    ),
                ),
            ),
            mode = GoogleMediaSerializationMode.FALLBACK_INLINE_IMAGES,
        )

        val images = result.flatMap { message ->
            message.jsonObject["parts"]?.jsonArray.orEmpty().mapNotNull { part ->
                part.jsonObject["inlineData"]?.jsonObject
            }
        }

        val response = functionResponses(result).single()["functionResponse"]!!.jsonObject
        val responseBody = response["response"]!!.jsonObject
        assertTrue(responseBody.keys.none { it.startsWith("nastech_media_") })
        assertFalse(response.containsKey("parts"))
        assertEquals(
            listOf("first", "second"),
            images.map { it["data"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("image/png", "image/jpeg"),
            images.map { it["mimeType"]!!.jsonPrimitive.content },
        )
        assertTrue(images.all { !it.containsKey("displayName") })
    }

    @Test
    fun `media reference validator rejects mismatched or duplicate identities`() {
        val validResponse = buildJsonObject {
            put(
                "nastech_media_0",
                buildJsonObject {
                    put("\$ref", JsonPrimitive("nastech_media_0"))
                },
            )
            put(
                "nastech_media_1",
                buildJsonObject {
                    put("\$ref", JsonPrimitive("nastech_media_1"))
                },
            )
        }

        assertTrue(
            GoogleFunctionResponseMediaSerializer.validateMultimodalReferences(
                response = validResponse,
                inlineDataDisplayNames = listOf("nastech_media_0", "nastech_media_1"),
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaSerializer.validateMultimodalReferences(
                response = validResponse,
                inlineDataDisplayNames = listOf("nastech_media_0"),
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaSerializer.validateMultimodalReferences(
                response = validResponse,
                inlineDataDisplayNames = listOf("nastech_media_1", "nastech_media_1"),
            )
        )
    }

    @Test
    fun `known Gemini media reference mismatch is narrowly classified`() {
        val error = """
            INVALID_ARGUMENT: The referenced name `nastech_media_0` in
            function_response.response does not match to a display_name in
            function_response.parts.
        """.trimIndent()

        assertTrue(
            GoogleFunctionResponseMediaFallback.isMediaReferenceValidationError(error)
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.isMediaReferenceValidationError(
                "400 INVALID_ARGUMENT: invalid request body"
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.isMediaReferenceValidationError(
                "401 Unauthorized: authentication failed"
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.isMediaReferenceValidationError(
                "429 quota exceeded for project"
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.isMediaReferenceValidationError(
                "500 internal server error"
            )
        )
    }

    @Test
    fun `fallback policy is one shot and never starts after meaningful output`() {
        val primary = GoogleMediaSerializationMode.PRIMARY_MULTIMODAL_FUNCTION_RESPONSE
        val error =
            "function_response.response reference mismatch with display_name and reference"

        assertTrue(
            GoogleFunctionResponseMediaFallback.shouldAttemptFallback(
                mode = primary,
                fallbackAttempted = false,
                meaningfulOutputDelivered = false,
                errorText = error,
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.shouldAttemptFallback(
                mode = primary,
                fallbackAttempted = true,
                meaningfulOutputDelivered = false,
                errorText = error,
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.shouldAttemptFallback(
                mode = primary,
                fallbackAttempted = false,
                meaningfulOutputDelivered = true,
                errorText = error,
            )
        )
        assertFalse(
            GoogleFunctionResponseMediaFallback.shouldAttemptFallback(
                mode = GoogleMediaSerializationMode.FALLBACK_INLINE_IMAGES,
                fallbackAttempted = false,
                meaningfulOutputDelivered = false,
                errorText = error,
            )
        )
    }

    @Test
    fun `generateText retries exactly once with fallback serialization`() = runBlocking {
        val requests = mutableListOf<String>()
        val primaryError =
            """{"error":{"message":"The referenced name `nastech_media_0` in function_response.response does not match to a display_name in the function_response.parts"}}"""
        val successBody =
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"continued"}]},"finishReason":"STOP"}]}"""

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                requests += buffer.readUtf8()

                val index = requests.size
                check(index <= 2) { "Unexpected third Gemini request" }
                val body = if (index == 1) primaryError else successBody

                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (index == 1) 400 else 200)
                    .message(if (index == 1) "Bad Request" else "OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val httpProvider = GoogleProvider(client)
        val model = Model(
            modelId = "gemini-3-flash",
            abilities = listOf(ModelAbility.TOOL),
        )

        val result = httpProvider.generateText(
            providerSetting = ProviderSetting.Google(
                baseUrl = "https://example.test/v1",
                apiKey = "test-key",
            ),
            messages = listOf(
                UIMessage.user("take a screenshot"),
                assistantWithTool(
                    toolId = "call-http-fallback",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Image("data:image/png;base64,actual-image"),
                        UIMessagePart.Text("done"),
                    ),
                ),
            ),
            params = me.rerere.ai.provider.TextGenerationParams(model = model),
        )

        assertEquals("continued", result.message.toText())
        assertEquals(2, requests.size)

        val primaryJson = kotlinx.serialization.json.Json.parseToJsonElement(requests[0]).jsonObject
        val fallbackJson = kotlinx.serialization.json.Json.parseToJsonElement(requests[1]).jsonObject

        val primaryFunctionResponse =
            primaryJson["contents"]!!.jsonArray
                .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
                .first { it.jsonObject.containsKey("functionResponse") }
                .jsonObject["functionResponse"]!!.jsonObject
        assertTrue(primaryFunctionResponse["parts"]!!.jsonArray.isNotEmpty())
        assertTrue(
            primaryFunctionResponse["response"]!!.jsonObject.keys.any {
                it.startsWith("nastech_media_")
            }
        )

        val fallbackParts =
            fallbackJson["contents"]!!.jsonArray
                .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }

        val fallbackFunctionResponse =
            fallbackParts.first { it.containsKey("functionResponse") }["functionResponse"]!!.jsonObject
        assertTrue(
            fallbackFunctionResponse["response"]!!.jsonObject.keys.none {
                it.startsWith("nastech_media_")
            }
        )
        assertFalse(fallbackFunctionResponse.containsKey("parts"))

        val fallbackImages = fallbackParts.mapNotNull { it["inlineData"]?.jsonObject }
        assertEquals(1, fallbackImages.size)
        assertEquals("image/png", fallbackImages.single()["mimeType"]!!.jsonPrimitive.content)
        assertEquals("actual-image", fallbackImages.single()["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamText retries once before meaningful output and emits only fallback chunks`() = runBlocking {
        val requestBodies = mutableListOf<String>()
        val primaryError =
            """{"error":{"message":"The referenced name `nastech_media_0` in function_response.response does not match to a display_name in the function_response.parts"}}"""
        val streamBody =
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"continued"}]}}]}

"""

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                requestBodies += buffer.readUtf8()

                val index = requestBodies.size
                check(index <= 2) { "Unexpected third Gemini request" }
                val isPrimary = index == 1
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isPrimary) 400 else 200)
                    .message(if (isPrimary) "Bad Request" else "OK")
                    .body(
                        (if (isPrimary) primaryError else streamBody)
                            .toResponseBody(
                                (if (isPrimary) "application/json" else "text/event-stream")
                                    .toMediaType()
                            )
                    )
                    .build()
            })
            .build()

        val httpProvider = GoogleProvider(client)
        val model = Model(
            modelId = "gemini-3-flash",
            abilities = listOf(ModelAbility.TOOL),
        )

        val chunks = httpProvider.streamText(
            providerSetting = ProviderSetting.Google(
                baseUrl = "https://example.test/v1",
                apiKey = "test-key",
            ),
            messages = listOf(
                UIMessage.user("take a screenshot"),
                assistantWithTool(
                    toolId = "call-stream-fallback",
                    name = "take_screenshot",
                    output = listOf(
                        UIMessagePart.Image("data:image/png;base64,stream-image"),
                    ),
                ),
            ),
            params = me.rerere.ai.provider.TextGenerationParams(model = model),
        ).toList()

        assertEquals(2, requestBodies.size)

        val primaryJson =
            kotlinx.serialization.json.Json.parseToJsonElement(requestBodies[0]).jsonObject
        val fallbackJson =
            kotlinx.serialization.json.Json.parseToJsonElement(requestBodies[1]).jsonObject

        val primaryFunctionResponse =
            primaryJson["contents"]!!.jsonArray
                .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
                .first { it.jsonObject.containsKey("functionResponse") }
                .jsonObject["functionResponse"]!!.jsonObject
        assertTrue(primaryFunctionResponse["parts"]!!.jsonArray.isNotEmpty())
        assertTrue(
            primaryFunctionResponse["response"]!!.jsonObject.keys.any {
                it.startsWith("nastech_media_")
            }
        )

        val fallbackParts =
            fallbackJson["contents"]!!.jsonArray
                .flatMap { it.jsonObject["parts"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }
        val fallbackFunctionResponse =
            fallbackParts.first { it.containsKey("functionResponse") }["functionResponse"]!!.jsonObject

        assertTrue(
            fallbackFunctionResponse["response"]!!.jsonObject.keys.none {
                it.startsWith("nastech_media_")
            }
        )
        assertFalse(fallbackFunctionResponse.containsKey("parts"))
        val fallbackImages = fallbackParts.mapNotNull { it["inlineData"]?.jsonObject }
        assertEquals(1, fallbackImages.size)
        assertEquals("image/png", fallbackImages.single()["mimeType"]!!.jsonPrimitive.content)
        assertEquals("stream-image", fallbackImages.single()["data"]!!.jsonPrimitive.content)

        assertTrue(
            chunks.any {
                it is me.rerere.ai.ui.StreamChunk.TextDelta && it.text == "continued"
            }
        )
        assertTrue(
            chunks.none {
                it is me.rerere.ai.ui.StreamChunk.TextDelta && it.text != "continued"
            }
        )
    }

    @Test
    fun `unsupported Gemini model degrades without emitting malformed media parts`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("screenshot"),
                assistantWithTool(
                    toolId = "call-old",
                    name = "take_screenshot",
                    output = listOf(UIMessagePart.Image("data:image/png;base64,image")),
                ),
            ),
            modelId = "gemini-2.5-flash",
        )

        val response = functionResponses(result).single()
        assertFalse(response.containsKey("parts"))

        val body = response["response"]!!.jsonObject
        val error = body["media_errors"]!!.jsonArray.single().jsonObject
        assertEquals(
            "multimodal_function_response_unsupported_for_model",
            error["reason"]!!.jsonPrimitive.content
        )
        assertEquals("image/png", error["mime_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid media never creates a dangling ref`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("screenshot"),
                assistantWithTool(
                    toolId = "call-invalid",
                    name = "take_screenshot",
                    output = listOf(UIMessagePart.Image("content://not-supported")),
                ),
            )
        )

        val response = functionResponses(result).single()
        assertFalse(response.containsKey("parts"))

        val body = response["response"]!!.jsonObject
        assertTrue(body.keys.none { it.startsWith("nastech_media_") })
        assertEquals(
            "media_encoding_failed:Unsupported URL format: content://not-supported",
            body["media_errors"]!!.jsonArray.single().jsonObject["reason"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `non-media tool response keeps its existing result shape`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("calculate"),
                assistantWithTool(
                    toolId = "call-calc",
                    name = "calculate",
                    output = listOf(UIMessagePart.Text("42")),
                ),
            )
        )

        val response = functionResponses(result).single()
        val body = response["response"]!!.jsonObject
        assertEquals("42", body["result"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("media_errors"))
        assertFalse(response.containsKey("parts"))
    }

    private fun functionResponses(result: JsonArray): List<JsonObject> =
        result.flatMap { message ->
            message.jsonObject["parts"]?.jsonArray.orEmpty().mapNotNull { part ->
                part.jsonObject["functionResponse"]?.jsonObject
            }
        }

    private fun assistantWithTool(
        toolId: String,
        name: String,
        output: List<UIMessagePart>,
    ): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(executedTool(toolId, name, output))
        )

    private fun executedTool(
        id: String,
        name: String,
        output: List<UIMessagePart>,
    ): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = name,
            input = "{}",
            output = output,
        )
}
