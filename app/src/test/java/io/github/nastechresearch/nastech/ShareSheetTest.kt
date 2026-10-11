package io.github.nastechresearch.nastech

import kotlin.io.encoding.Base64
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import io.github.nastechresearch.nastech.ui.components.ui.decodeProviderSetting
import io.github.nastechresearch.nastech.ui.components.ui.encodeForShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ShareSheetTest {
    private fun sharedJson(encoded: String): String =
        Base64.decode(encoded.removePrefix("ai-provider:v1:")).decodeToString()

    @Test
    fun `decode should restore OpenAI provider configuration without exporting raw secret`() {
        val originalId = Uuid.random()
        val original = ProviderSetting.OpenAI(
            id = originalId,
            enabled = true,
            name = "Test OpenAI",
            models = listOf(
                Model(
                    id = Uuid.random(),
                    displayName = "gpt-4",
                )
            ),
            apiKey = "sk-test-key",
            baseUrl = "https://api.openai.com/v1",
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false,
            balanceOption = BalanceOption(enabled = false)
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded)

        assertTrue(decoded is ProviderSetting.OpenAI)
        val decodedOpenAI = decoded as ProviderSetting.OpenAI
        assertEquals(originalId, decodedOpenAI.id)
        assertEquals("Test OpenAI", decodedOpenAI.name)
        assertTrue(decodedOpenAI.apiKey.isEmpty())
        assertEquals(original.credentialRef, decodedOpenAI.credentialRef)
        assertTrue("Raw API keys must not be exported", !sharedJson(encoded).contains("sk-test-key"))
        assertEquals("https://api.openai.com/v1", decodedOpenAI.baseUrl)
        // Sharing strips the models list; credentials stay in the local encrypted vault.
        assertTrue(
            "models must be stripped on share — got ${decodedOpenAI.models.size}",
            decodedOpenAI.models.isEmpty(),
        )
    }

    @Test
    fun `decode should restore Google provider configuration without exporting raw secret`() {
        val originalId = Uuid.random()
        val original = ProviderSetting.Google(
            id = originalId,
            enabled = true,
            name = "Test Google",
            models = emptyList(),
            apiKey = "test-google-key",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            vertexAI = false
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded)

        assertTrue(decoded is ProviderSetting.Google)
        val decodedGoogle = decoded as ProviderSetting.Google
        assertEquals(originalId, decodedGoogle.id)
        assertEquals("Test Google", decodedGoogle.name)
        assertTrue(decodedGoogle.apiKey.isEmpty())
        assertEquals(original.credentialRef, decodedGoogle.credentialRef)
        assertTrue("Raw API keys must not be exported", !sharedJson(encoded).contains("test-google-key"))
        assertEquals(false, decodedGoogle.vertexAI)
    }

    @Test
    fun `decode should restore Claude provider configuration without exporting raw secret`() {
        val originalId = Uuid.random()
        val original = ProviderSetting.Claude(
            id = originalId,
            enabled = false,
            name = "Test Claude",
            models = emptyList(),
            apiKey = "test-claude-key",
            baseUrl = "https://api.anthropic.com/v1"
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded)

        assertTrue(decoded is ProviderSetting.Claude)
        val decodedClaude = decoded as ProviderSetting.Claude
        assertEquals(originalId, decodedClaude.id)
        assertEquals("Test Claude", decodedClaude.name)
        assertTrue(decodedClaude.apiKey.isEmpty())
        assertEquals(original.credentialRef, decodedClaude.credentialRef)
        assertTrue("Raw API keys must not be exported", !sharedJson(encoded).contains("test-claude-key"))
        assertEquals(false, decodedClaude.enabled)
    }

    @Test
    fun `decode should handle balance option`() {
        val original = ProviderSetting.OpenAI(
            id = Uuid.random(),
            enabled = true,
            name = "Test with Balance",
            models = emptyList(),
            apiKey = "test-key",
            baseUrl = "https://api.test.com",
            balanceOption = BalanceOption(
                enabled = true,
                apiPath = "/custom/credits",
                resultPath = "data.balance"
            )
        )

        val encoded = original.encodeForShare()
        val decoded = decodeProviderSetting(encoded) as ProviderSetting.OpenAI

        assertEquals(true, decoded.balanceOption.enabled)
        assertEquals("/custom/credits", decoded.balanceOption.apiPath)
        assertEquals("data.balance", decoded.balanceOption.resultPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for invalid prefix`() {
        decodeProviderSetting("invalid-string")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for wrong version`() {
        decodeProviderSetting("ai-provider:v2:somedata")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for invalid base64`() {
        decodeProviderSetting("ai-provider:v1:not-valid-base64!!!")
    }

    @Test
    fun `encode and decode should be reversible for non-secret configuration`() {
        val providers = listOf(
            ProviderSetting.OpenAI(
                name = "OpenAI Test",
                apiKey = "key1",
                baseUrl = "url1"
            ),
            ProviderSetting.Google(
                name = "Google Test",
                apiKey = "key2",
                vertexAI = true,
                projectId = "project-123"
            ),
            ProviderSetting.Claude(
                name = "Claude Test",
                apiKey = "key3"
            )
        )

        providers.forEach { original ->
            val encoded = original.encodeForShare()
            val decoded = decodeProviderSetting(encoded)

            assertEquals(original.id, decoded.id)
            assertEquals(original.name, decoded.name)
            assertEquals(original.enabled, decoded.enabled)
            assertTrue(decodedJsonHasNoApiKey(encoded))
        }
    }

    private fun decodedJsonHasNoApiKey(encoded: String): Boolean {
        val json = sharedJson(encoded)
        return !json.contains("\"apiKey\"") &&
            !json.contains("key1") &&
            !json.contains("key2") &&
            !json.contains("key3")
    }
}
