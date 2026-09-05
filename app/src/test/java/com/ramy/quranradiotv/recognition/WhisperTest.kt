package com.ramy.quranradiotv.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The FFT and tokenizer are checked outright. The end-to-end transcription
 * runs only when the model has been fetched to the directory named by the
 * `whisper.model.dir` system property (set from WHISPER_MODEL_DIR), with a
 * `wav/` folder of 16 kHz mono clips named by Surah and Ayah, e.g. 002255.wav.
 */
class WhisperTest {

    private val melAsset = File("src/main/assets/whisper/mel_80.bin")

    @Test
    fun fftMatchesNaiveDft() {
        val n = 400
        val fft = WhisperFeatures.Fft(n)
        val re = FloatArray(n) { sin(it * 0.37).toFloat() + cos(it * 0.011).toFloat() * 0.5f }
        val im = FloatArray(n)
        val expectRe = DoubleArray(n)
        val expectIm = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val a = -2.0 * PI * k * t / n
            expectRe[k] += re[t] * cos(a)
            expectIm[k] += re[t] * sin(a)
        }
        fft.transform(re, im)
        for (k in 0 until n) {
            assertTrue("re[$k] ${re[k]} vs ${expectRe[k]}", abs(re[k] - expectRe[k]) < 1e-2)
            assertTrue("im[$k] ${im[k]} vs ${expectIm[k]}", abs(im[k] - expectIm[k]) < 1e-2)
        }
    }

    @Test
    fun melOfSilenceIsFlatAndScaled() {
        val f = WhisperFeatures(melAsset.inputStream())
        val mel = f.logMel(FloatArray(16000))
        assertEquals(80 * 3000, mel.size)
        // log10(1e-10) = -10 everywhere; the floor (max - 8) is below that; (-10 + 4) / 4 = -1.5
        assertTrue(mel.all { abs(it + 1.5f) < 1e-4 })
    }

    @Test
    fun tokenizerDecodesArabicBytes() {
        val dir = modelDir() ?: return
        val tok = WhisperTokenizer.load(dir)
        assertEquals(50257, tok.endOfText)
        assertEquals(50258, tok.startOfTranscript)
        assertEquals(50272, tok.arabic)
        // Encode " الله" by hand: UTF-8 bytes through the byte→symbol map must round-trip.
        val vocab = org.json.JSONObject(File(dir, "vocab.json").readText())
        val symbols = " الله".toByteArray(Charsets.UTF_8).map { b ->
            val v = b.toInt() and 0xFF
            val direct = (33..126) + (161..172) + (174..255)
            if (v in direct) v.toChar() else (256 + (0 until v).count { it !in direct }).toChar()
        }.joinToString("")
        assertTrue("token for ' الله' present", vocab.has(symbols))
        assertEquals(" الله", tok.decode(listOf(vocab.getInt(symbols))))
    }

    @Test
    fun transcribesRecitationClips() {
        val dir = modelDir() ?: return
        val wavs = File(dir, "wav").listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }
        assumeTrue("no wav clips", !wavs.isNullOrEmpty())
        val quran = File("src/main/assets/quran/quran-simple-clean.txt").inputStream().use { QuranIndex.load(it) }

        val model = WhisperModel(dir, melAsset.inputStream(), threads = 4)
        model.use {
            for (wav in wavs!!) {
                val samples = readWav(wav)
                val t0 = System.currentTimeMillis()
                val text = it.transcribe(samples)
                val ms = System.currentTimeMillis() - t0
                val match = quran.match(ArabicText.words(text))
                println("${wav.name} (${samples.size / 16000}s, ${ms}ms): $text\n   -> $match")
                val surah = wav.name.substring(0, 3).toInt()
                val ayah = wav.name.substring(3, 6).toInt()
                assertNotNull("no match for ${wav.name}", match)
                assertEquals(surah, match!!.surah)
                assertTrue("ayah ${match.ayah} vs $ayah", abs(match.ayah - ayah) <= 1)
            }
        }
    }

    private fun modelDir(): File? {
        val path = System.getProperty("whisper.model.dir").orEmpty()
        val dir = File(path)
        assumeTrue("model dir not set", path.isNotEmpty() && File(dir, WhisperModel.ENCODER).exists())
        return dir
    }

    /** 16-bit PCM mono WAV, header skipped by finding the data chunk. */
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val n = size / 2
                return FloatArray(n) { buf.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size
        }
        error("no data chunk in ${file.name}")
    }
}
