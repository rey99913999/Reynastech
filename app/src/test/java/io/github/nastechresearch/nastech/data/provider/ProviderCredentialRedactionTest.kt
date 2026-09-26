package io.github.nastechresearch.nastech.data.provider

import io.github.nastechresearch.nastech.utils.JsonInstant
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialRedactionTest {
    @Test
    fun serializedProviderSettingsNeverContainRawSecrets() {
        val apiKey = "super-secret-api-key"
        val privateKey = "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----"
        val providers = listOf(
            ProviderSetting.OpenAI(apiKey = apiKey),
            ProviderSetting.Google(
                apiKey = apiKey,
                useServiceAccount = true,
                privateKey = privateKey,
                serviceAccountEmail = "service@example.invalid",
            ),
            ProviderSetting.Claude(apiKey = apiKey),
            ProviderSetting.Custom(apiKey = apiKey),
        )

        val json = JsonInstant.encodeToString(providers)
        assertFalse(json.contains(apiKey))
        assertFalse(json.contains(privateKey))
        assertFalse(json.contains("service@example.invalid"))
        assertFalse(json.contains(""apiKey""))
        assertTrue(json.contains(""credentialRef""))
    }
}
