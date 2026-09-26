package org.maocide.undeadwallpaper.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

interface ChecksumKeyProvider {
    fun hasKey(alias: String): Boolean
    fun generateKey(alias: String)
    fun getKey(alias: String): SecretKey?
}

class AndroidKeystoreProvider : ChecksumKeyProvider {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun hasKey(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }

    override fun generateKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    override fun getKey(alias: String): SecretKey? {
        return keyStore.getKey(alias, null) as? SecretKey
    }
}

class ConfigChecksumHelper(
    private val provider: ChecksumKeyProvider = AndroidKeystoreProvider()
) {
    companion object {
        const val ALIAS = "undead_config_crc_v1"
        const val KEY_CONFIG_CRC = "config_crc"
    }

    fun hasHardwareKey(): Boolean {
        return provider.hasKey(ALIAS)
    }

    fun generateHardwareKey() {
        if (!hasHardwareKey()) {
            provider.generateKey(ALIAS)
        }
    }

    fun computeChecksum(playlistJson: String?, activeUri: String?): String {
        val payload = "v1|uri=${activeUri.orEmpty()}|playlist=${playlistJson.orEmpty()}"
        val key = provider.getKey(ALIAS) ?: throw IllegalStateException("Key not found")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val hash = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    fun verifyChecksum(playlistJson: String?, activeUri: String?, storedChecksum: String?): Boolean {
        if (storedChecksum == null) return false
        return try {
            val computed = computeChecksum(playlistJson, activeUri)
            MessageDigest.isEqual(
                computed.toByteArray(Charsets.UTF_8),
                storedChecksum.toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            false
        }
    }
}
