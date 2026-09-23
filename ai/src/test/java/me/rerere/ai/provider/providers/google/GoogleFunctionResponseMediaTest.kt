package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
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
    ): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, messages, modelId) as JsonArray
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
