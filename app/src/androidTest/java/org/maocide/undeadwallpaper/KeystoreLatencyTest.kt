package org.maocide.undeadwallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.maocide.undeadwallpaper.data.ConfigChecksumHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic: measures the cost of the HMAC keystore op that the WallpaperService
 * lifecycle callback drives (onVisibilityChanged -> getActiveVideoUri -> computeChecksum).
 *
 * Purpose: quantify why a high-frequency visibility churn can ANR on a software-keystore
 * emulator but not on a real TEE. Prints per-call latency (sequential) and throughput +
 * contention (concurrent).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreLatencyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun computeChecksumLatencyAndContention() {
        val helper = ConfigChecksumHelper()
        helper.generateHardwareKey()
        // warm
        helper.computeChecksum("warm", "warm")

        val seq = 200
        val t0 = System.nanoTime()
        repeat(seq) { helper.computeChecksum("p", "u") }
        val seqMs = (System.nanoTime() - t0) / 1e6

        val threads = 8
        val perThread = 200
        val ok = AtomicInteger()
        val err = AtomicInteger()
        val maxMs = AtomicLong()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                repeat(perThread) {
                    val t = System.nanoTime()
                    try {
                        helper.computeChecksum("p", "u"); ok.incrementAndGet()
                    } catch (e: Exception) {
                        err.incrementAndGet()
                    }
                    val ms = (System.nanoTime() - t) / 1_000_000
                    maxMs.updateAndGet { m -> if (ms > m) ms else m }
                }
                done.countDown()
            }.start()
        }
        val tc0 = System.nanoTime()
        start.countDown()
        done.await()
        val concMs = (System.nanoTime() - tc0) / 1e6

        val total = threads * perThread
        println(
            "PROBE sequential: n=$seq total=${"%.1f".format(seqMs)}ms per-call=${"%.2f".format(seqMs / seq)}ms"
        )
        println(
            "PROBE concurrent: threads=$threads n=$total total=${"%.1f".format(concMs)}ms " +
                "throughput=${"%.0f".format(total / (concMs / 1000.0))}/s ok=$ok err=$err maxSingle=${maxMs}ms"
        )

        // Key generation (resetCorruptedPrefs path) is a different, heavier operation.
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(ConfigChecksumHelper.ALIAS)
        val kg0 = System.nanoTime()
        helper.generateHardwareKey()
        val kgMs = (System.nanoTime() - kg0) / 1e6
        println("PROBE keygen: ${"%.1f".format(kgMs)}ms")

        // Does a background keystore load stall a MAIN-thread call? (the ANR shape)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val workers = (1..8).map {
            Thread {
                while (!stop.get()) {
                    try { helper.computeChecksum("p", "u") } catch (_: Exception) {}
                }
            }.also { it.start() }
        }
        Thread.sleep(1500)
        var worst = 0L
        repeat(20) {
            val m0 = System.nanoTime()
            helper.computeChecksum("main", "main")
            val mms = (System.nanoTime() - m0) / 1_000_000
            if (mms > worst) worst = mms
        }
        stop.set(true)
        workers.forEach { it.join(500) }
        println("PROBE main-thread call under 8-thread load: worst=${worst}ms")
    }
}
