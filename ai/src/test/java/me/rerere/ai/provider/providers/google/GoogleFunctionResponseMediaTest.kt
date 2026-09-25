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

        val functionResponse = functionResponses(result).single()
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

        val response = functionResponses(result).single()
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
