package org.maocide.undeadwallpaper.security

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.service.EngineEffect
import org.maocide.undeadwallpaper.service.EngineEvent
import org.maocide.undeadwallpaper.service.EngineFSM
import java.io.File
import java.io.FileNotFoundException

/**
 * Security Consistency & Anti-Tamper Unit Test Suite.
 *
 * Verifies that decentralized anti-tamper tripwires:
 * 1. Maintain bit-for-bit parity across disconnected call sites without source centralization.
 * 2. Mathematically drop luminance to black (0.0f) under package renaming / repacking.
 * 3. Preserve intentional fail-open behavior in the luma trap when reflection is impaired,
 *    allowing the fail-closed signature gate (calculateCalibrationOffset) to act as the authoritative backstop.
 */
class SecurityConsistencyTest {

    private class FakeContext(private val pkgName: String) : ContextWrapper(null) {
        override fun getPackageName(): String = pkgName
    }

    private class ThrowingContext : ContextWrapper(null) {
        override fun getPackageName(): String = throw RuntimeException("Simulated reflection failure")
    }

    private fun resolveSourceFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("../", relativePath),
            File(relativePath.removePrefix("app/")),
            File("../app", relativePath.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: throw FileNotFoundException("Could not locate source file: $relativePath (Searched: ${candidates.map { it.absolutePath }})")
    }

    private fun extractIntArray(file: File, varName: String): List<Int> {
        val content = file.readText()
        val regex = Regex("""val\s+$varName\s*=\s*intArrayOf\(([\s\S]*?)\)""")
        val match = regex.find(content)
            ?: throw AssertionError("Could not find variable '$varName' in ${file.name}")
        return match.groupValues[1]
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
    }

    private fun extractByteArray(file: File, varName: String): List<Byte> {
        val content = file.readText()
        val regex = Regex("""val\s+$varName\s*=\s*byteArrayOf\(([\s\S]*?)\)""")
        val match = regex.find(content)
            ?: throw AssertionError("Could not find variable '$varName' in ${file.name}")
        return match.groupValues[1]
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toByte() }
    }

    @Test
    fun testPackageNameSeedConsistencyAcrossEngines() {
        val fsmFile = resolveSourceFile("app/src/main/java/org/maocide/undeadwallpaper/service/EngineFSM.kt")
        val pvrFile = resolveSourceFile("app/src/main/java/org/maocide/undeadwallpaper/ui/preview/PreviewVideoRenderer.kt")

        val fsmSeed = extractIntArray(fsmFile, "seed")
        val pvrSeed = extractIntArray(pvrFile, "seed")

        assertNotNull("EngineFSM seed must not be null", fsmSeed)
        assertNotNull("PreviewVideoRenderer seed must not be null", pvrSeed)

        // 1. Assert parity between decentralized instances (No drift allowed!)
        assertEquals(
            "EngineFSM and PreviewVideoRenderer package seeds have drifted! They must be identical.",
            fsmSeed,
            pvrSeed
        )

        // 2. Assert seed decodes to the official canonical package name
        val decoded = fsmSeed.map { it.toChar() }.joinToString("")
        assertEquals("org.maocide.undeadwallpaper", decoded)
    }

    @Test
    fun testCertificateDigestsConsistencyAcrossRenderers() {
        val glFile = resolveSourceFile("app/src/main/java/org/maocide/undeadwallpaper/service/GLVideoRenderer.kt")
        val pvrFile = resolveSourceFile("app/src/main/java/org/maocide/undeadwallpaper/ui/preview/PreviewVideoRenderer.kt")

        val glBytes1 = extractByteArray(glFile, "expectedBytes1")
        val pvrBytes1 = extractByteArray(pvrFile, "expectedBytes1")

        val glBytes2 = extractByteArray(glFile, "expectedBytes2")
        val pvrBytes2 = extractByteArray(pvrFile, "expectedBytes2")

        // 1. Assert parity across both decentralized renderers
        assertEquals("expectedBytes1 has drifted between GLVideoRenderer and PreviewVideoRenderer!", glBytes1, pvrBytes1)
        assertEquals("expectedBytes2 has drifted between GLVideoRenderer and PreviewVideoRenderer!", glBytes2, pvrBytes2)

        // 2. Assert valid SHA-256 length (64 hex characters)
        assertEquals("expectedBytes1 must be a 64-byte ASCII SHA-256 digest", 64, glBytes1.size)
        assertEquals("expectedBytes2 must be a 64-byte ASCII SHA-256 digest", 64, glBytes2.size)

        val hex1 = String(glBytes1.toByteArray())
        val hex2 = String(glBytes2.toByteArray())

        assertTrue("expectedBytes1 must be lowercase hexadecimal: $hex1", hex1.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue("expectedBytes2 must be lowercase hexadecimal: $hex2", hex2.matches(Regex("^[0-9a-f]{64}$")))

        // 3. Assert the two expected digests represent distinct signing keys (e.g. Upload vs Play Signing)
        assertNotEquals("expectedBytes1 and expectedBytes2 should represent distinct signing keys", hex1, hex2)
    }

    @Test
    fun testLumaTrapFiresOnPackageTamperingInEngineFSM() {
        // Legitimate package: full luminance
        val cleanFsm = EngineFSM(FakeContext("org.maocide.undeadwallpaper"))
        val cleanEffect = cleanFsm.transition(EngineEvent.InitializeRequested)
        assertTrue(cleanEffect is EngineEffect.StartInitialization)
        assertEquals(1.0f, (cleanEffect as EngineEffect.StartInitialization).lumaScale, 0.001f)

        // Repack 1: Suffix added
        val tamperedFsm1 = EngineFSM(FakeContext("org.maocide.undeadwallpaper.repack"))
        val effect1 = tamperedFsm1.transition(EngineEvent.InitializeRequested)
        assertTrue(effect1 is EngineEffect.StartInitialization)
        assertEquals("Luma trap must fire (0.0f) for suffixed package", 0.0f, (effect1 as EngineEffect.StartInitialization).lumaScale, 0.001f)

        // Repack 2: Entirely rogue namespace
        val tamperedFsm2 = EngineFSM(FakeContext("com.syndicate.wallpaper"))
        val effect2 = tamperedFsm2.transition(EngineEvent.InitializeRequested)
        assertTrue(effect2 is EngineEffect.StartInitialization)
        assertEquals("Luma trap must fire (0.0f) for rogue namespace", 0.0f, (effect2 as EngineEffect.StartInitialization).lumaScale, 0.001f)

        // Repack 3: Truncated prefix
        val tamperedFsm3 = EngineFSM(FakeContext("org.maocide.undead"))
        val effect3 = tamperedFsm3.transition(EngineEvent.InitializeRequested)
        assertTrue(effect3 is EngineEffect.StartInitialization)
        assertEquals("Luma trap must fire (0.0f) for truncated namespace", 0.0f, (effect3 as EngineEffect.StartInitialization).lumaScale, 0.001f)
    }

    @Test
    fun testLumaTrapFailsOpenOnReflectionFailureToYieldToSignatureGate() {
        // When reflection on getPackageName fails, luma trap intentionally returns 1.0f (fail-open)
        // so that the authoritative fail-closed signature gate (calculateCalibrationOffset -> 20000f) governs.
        val throwingFsm = EngineFSM(ThrowingContext())
        val effect = throwingFsm.transition(EngineEvent.InitializeRequested)
        assertTrue(effect is EngineEffect.StartInitialization)
        assertEquals(
            "Luma trap must fail-open (1.0f) when getPackageName throws to yield to signature gate",
            1.0f,
            (effect as EngineEffect.StartInitialization).lumaScale,
            0.001f
        )
    }
}
