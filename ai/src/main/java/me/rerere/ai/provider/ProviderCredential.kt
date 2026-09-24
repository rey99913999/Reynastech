
package me.rerere.ai.provider

import kotlinx.serialization.Serializable

/**
 * Runtime credential material resolved from the app's secure credential store.
 *
 * This type intentionally lives in the AI module so providers never need to depend on
 * Android/application storage details. It must never be persisted inside ProviderSetting.
 */
@Serializable
data class ProviderCredential(
    val apiKey: String = "",
    val privateKey: String = "",
    val serviceAccountEmail: String = "",
)

interface ProviderCredentialResolver {
    suspend fun resolve(credentialRef: String): ProviderCredential?
}

object EmptyProviderCredentialResolver : ProviderCredentialResolver {
    override suspend fun resolve(credentialRef: String): ProviderCredential? = null
}
