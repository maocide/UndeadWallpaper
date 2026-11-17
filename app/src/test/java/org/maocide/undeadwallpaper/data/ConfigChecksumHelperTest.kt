package org.maocide.undeadwallpaper.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.encodeToString
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SoftwareChecksumKeyProvider : ChecksumKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun hasKey(alias: String): Boolean {
        return keys.containsKey(alias)
    }

    override fun generateKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance("HmacSHA256")
        keyGenerator.init(256)
        keys[alias] = keyGenerator.generateKey()
    }

    override fun getKey(alias: String): SecretKey? {
        return keys[alias]
    }
}

class ConfigChecksumHelperTest {

    private lateinit var provider: SoftwareChecksumKeyProvider
    private lateinit var helper: ConfigChecksumHelper

    @Before
    fun setup() {
        provider = SoftwareChecksumKeyProvider()
        helper = ConfigChecksumHelper(provider)
    }

    @Test
    fun testGenerateAndHasKey() {
        assertFalse(helper.hasHardwareKey())
        helper.generateHardwareKey()
        assertTrue(helper.hasHardwareKey())
    }

    @Test
    fun testComputeAndVerifyChecksum() {
        helper.generateHardwareKey()
        val playlistJson = "[{\"fileName\":\"video.mp4\"}]"
        val uri = "file:///data/user/0/video.mp4"

        val computed = helper.computeChecksum(playlistJson, uri)
        
        // Should verify correctly
        assertTrue(helper.verifyChecksum(playlistJson, uri, computed))
        
        // Mutated JSON
        assertFalse(helper.verifyChecksum("[{\"fileName\":\"video2.mp4\"}]", uri, computed))
        
        // Mutated URI
        assertFalse(helper.verifyChecksum(playlistJson, "file:///data/user/0/video2.mp4", computed))
    }

    @Test
    fun testVerifyWithNulls() {
        helper.generateHardwareKey()
        
        val checksumString = helper.computeChecksum(null, null)
        assertTrue(helper.verifyChecksum(null, null, checksumString))
        assertFalse(helper.verifyChecksum("foo", null, checksumString))
        assertFalse(helper.verifyChecksum(null, "bar", checksumString))
    }

    @Test
    fun testVideoSettingsSerializationWithContrastAndSaturationPreservesIntegrity() {
        helper.generateHardwareKey()
        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        // 1. Legacy JSON without contrast and saturation
        val legacyJson = """[{"fileName":"sample.mp4","brightness":1.2}]"""
        val uri = "file:///storage/sample.mp4"
        val legacyChecksum = helper.computeChecksum(legacyJson, uri)
        assertTrue(helper.verifyChecksum(legacyJson, uri, legacyChecksum))

        // 2. Decode legacy JSON using current VideoSettings data class
        val decoded = jsonParser.decodeFromString<List<org.maocide.undeadwallpaper.model.VideoSettings>>(legacyJson)
        assertEquals(1, decoded.size)
        assertEquals("sample.mp4", decoded[0].fileName)
        assertEquals(1.2f, decoded[0].brightness, 0.001f)
        assertEquals(1.0f, decoded[0].contrast, 0.001f) // Defaults properly
        assertEquals(1.0f, decoded[0].saturation, 0.001f) // Defaults properly

        // 3. New JSON with contrast and saturation
        val newSettings = decoded[0].copy(contrast = 1.1f, saturation = 0.9f)
        val newJson = jsonParser.encodeToString<List<org.maocide.undeadwallpaper.model.VideoSettings>>(listOf(newSettings))
        val newChecksum = helper.computeChecksum(newJson, uri)

        assertTrue(helper.verifyChecksum(newJson, uri, newChecksum))
        assertFalse(helper.verifyChecksum(newJson, uri, legacyChecksum))
    }
}
