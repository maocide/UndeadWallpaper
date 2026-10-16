package org.maocide.undeadwallpaper.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAnalyzerTest {

    @Test
    fun `isStaticImageHeader detects JPEG magic bytes`() {
        val jpegApp0 = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
        )
        assertTrue("JPEG APP0 should be recognized as static image", MediaAnalyzer.isStaticImageHeader(jpegApp0))

        val jpegApp1 = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(),
            0x00, 0x18, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00
        )
        assertTrue("JPEG APP1 (EXIF) should be recognized as static image", MediaAnalyzer.isStaticImageHeader(jpegApp1))
    }

    @Test
    fun `isStaticImageHeader detects PNG magic bytes`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        )
        assertTrue("PNG header should be recognized as static image", MediaAnalyzer.isStaticImageHeader(png))
    }

    @Test
    fun `isStaticImageHeader detects GIF magic bytes`() {
        val gif87 = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '7'.code.toByte(), 'a'.code.toByte(),
            0x64, 0x00, 0x64, 0x00
        )
        assertTrue("GIF87a header should be recognized as static image", MediaAnalyzer.isStaticImageHeader(gif87))

        val gif89 = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x64, 0x00, 0x64, 0x00
        )
        assertTrue("GIF89a header should be recognized as static image", MediaAnalyzer.isStaticImageHeader(gif89))
    }

    @Test
    fun `isStaticImageHeader detects BMP magic bytes`() {
        val bmp = byteArrayOf(
            0x42.toByte(), 0x4D.toByte(), 0x36, 0x00, 0x0C, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x36, 0x00, 0x00, 0x00
        )
        assertTrue("BMP header should be recognized as static image", MediaAnalyzer.isStaticImageHeader(bmp))
    }

    @Test
    fun `isStaticImageHeader detects TIFF magic bytes`() {
        val tiffLittleEndian = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertTrue("TIFF (II) should be recognized as static image", MediaAnalyzer.isStaticImageHeader(tiffLittleEndian))

        val tiffBigEndian = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
        assertTrue("TIFF (MM) should be recognized as static image", MediaAnalyzer.isStaticImageHeader(tiffBigEndian))
    }

    @Test
    fun `isStaticImageHeader detects WebP image RIFF chunks`() {
        // RIFF .... WEBP
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x20, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte()
        )
        assertTrue("WebP RIFF chunk should be recognized as static image", MediaAnalyzer.isStaticImageHeader(webp))
    }

    @Test
    fun `isStaticImageHeader NEVER rejects legitimate video containers (Anti-False-Positive Guarantee)`() {
        // MP4 with ftyp box
        val mp4Ftyp = byteArrayOf(
            0x00, 0x00, 0x00, 0x1C,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x02, 0x00
        )
        assertFalse("MP4 ftyp container must NEVER be classified as static image", MediaAnalyzer.isStaticImageHeader(mp4Ftyp))

        // MP4 with moov first box (fast-start)
        val mp4Moov = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,
            'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(),
            0x00, 0x00, 0x00, 0x6C,
            'm'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte()
        )
        assertFalse("MP4 moov container must NEVER be classified as static image", MediaAnalyzer.isStaticImageHeader(mp4Moov))

        // Matroska / WebM video container (EBML ID: 1A 45 DF A3)
        val mkvEbml = byteArrayOf(
            0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(),
            0x93.toByte(), 0x42.toByte(), 0x82.toByte(), 0x88.toByte(),
            'm'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte()
        )
        assertFalse("MKV/WebM EBML video container must NEVER be classified as static image", MediaAnalyzer.isStaticImageHeader(mkvEbml))

        // AVI video container (RIFF .... AVI )
        val avi = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x00, 0x10, 0x00, 0x00,
            'A'.code.toByte(), 'V'.code.toByte(), 'I'.code.toByte(), ' '.code.toByte(),
            'L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte()
        )
        assertFalse("AVI video container (RIFF ... AVI ) must NEVER be classified as static image", MediaAnalyzer.isStaticImageHeader(avi))

        // MPEG-TS container (Sync byte 0x47)
        val mpegTs = byteArrayOf(
            0x47.toByte(), 0x40.toByte(), 0x00.toByte(), 0x10.toByte(),
            0x00, 0x00, 0x01, 0xE0.toByte(),
            0x00, 0x00, 0x80.toByte(), 0x80.toByte(), 0x05
        )
        assertFalse("MPEG-TS video container must NEVER be classified as static image", MediaAnalyzer.isStaticImageHeader(mpegTs))

        // Ogg Theora container (OggS)
        val ogg = byteArrayOf(
            'O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(),
            0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertFalse("Ogg container must NEVER be classified as static image", MediaAnalyzer.isStaticImageHeader(ogg))
    }

    @Test
    fun `isStaticImageHeader handles truncated, empty, and arbitrary byte sequences safely`() {
        assertFalse("Empty array should return false", MediaAnalyzer.isStaticImageHeader(ByteArray(0)))
        assertFalse("Single byte should return false", MediaAnalyzer.isStaticImageHeader(byteArrayOf(0xFF.toByte())))
        assertFalse("Two bytes should return false", MediaAnalyzer.isStaticImageHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))

        val randomBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        assertFalse("Arbitrary bytes should return false", MediaAnalyzer.isStaticImageHeader(randomBytes))
    }
}
