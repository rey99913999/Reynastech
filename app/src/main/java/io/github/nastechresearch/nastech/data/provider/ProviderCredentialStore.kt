package io.github.nastechresearch.nastech.data.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderCredential
import me.rerere.ai.provider.ProviderCredentialResolver
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One encrypted credential vault for user-configured AI providers.
 *
 * Provider settings only contain a stable reference. The actual secret material is kept under
 * noBackupFilesDir and encrypted with an Android Keystore AES-GCM key.
 */
class ProviderCredentialStore(
    context: Context,
    private val json: Json,
) : ProviderCredentialResolver {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    override suspend fun resolve(credentialRef: String): ProviderCredential? = withContext(Dispatchers.IO) {
        if (credentialRef.isBlank()) return@withContext null
        readState().credentials[credentialRef]
    }

    suspend fun snapshot(): Map<String, ProviderCredential> = withContext(Dispatchers.IO) {
        readState().credentials
    }

    suspend fun putAll(values: Map<String, ProviderCredential>) = withContext(Dispatchers.IO) {
        if (values.isEmpty()) return@withContext
        val state = readState()
        writeState(state.copy(credentials = state.credentials + values))
    }

    suspend fun remove(credentialRef: String) = withContext(Dispatchers.IO) {
        if (credentialRef.isBlank()) return@withContext
        val state = readState()
        if (credentialRef !in state.credentials) return@withContext
        writeState(state.copy(credentials = state.credentials - credentialRef))
    }

    suspend fun migrateLegacySecrets(values: Map<String, ProviderCredential>) {
        putAll(values)
    }

    private fun readState(): CredentialState {
        if (!file.exists()) return CredentialState()
        val bytes = file.readBytes()
        require(bytes.size > IV_SIZE) { "Provider credential store is corrupt" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
        return json.decodeFromString(cipher.doFinal(encrypted).decodeToString())
    }

    private fun writeState(state: CredentialState) {
        file.parentFile?.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(cipher.iv + encrypted)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            check(temporary.delete())
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    @Serializable
    private data class CredentialState(
        val credentials: Map<String, ProviderCredential> = emptyMap(),
    )

    private companion object {
        const val FILE_NAME = "provider_credentials.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nastech_provider_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}
