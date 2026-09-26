package org.maocide.undeadwallpaper.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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
}
