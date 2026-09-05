package com.ramy.quranradiotv.recognition

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Turns 16 kHz audio into the log-mel spectrogram Whisper was trained on.
 *
 * This reproduces `whisper.audio.log_mel_spectrogram` step for step: a
 * 400-point STFT with a periodic Hann window and a hop of 160, reflect-padded
 * by 200 samples at both ends; power spectrum; the 80-band mel filter bank
 * from Whisper's own `mel_filters.npz`, shipped as an asset rather than
 * recomputed so there is nothing to get subtly wrong; log10 with a floor; a
 * clamp to eight decades below the peak; and the (x + 4) / 4 scaling.
 *
 * Thirty seconds of audio is 480 000 samples and 3 000 frames. Shorter input
 * is zero-padded to that, as Whisper does; longer is cut.
 */
class WhisperFeatures(melFilters: InputStream) {

    /** [N_MELS][N_FREQS] filter weights. */
    private val filters: Array<FloatArray>

    private val window = FloatArray(N_FFT) { 0.5f * (1f - cos(2.0 * PI * it / N_FFT).toFloat()) }
    private val fft = Fft(N_FFT)

    init {
        val bytes = melFilters.readBytes()
        require(bytes.size == N_MELS * N_FREQS * 4) { "mel filter bank has ${bytes.size} bytes" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        filters = Array(N_MELS) { m -> FloatArray(N_FREQS) { buf.get(m * N_FREQS + it) } }
    }

    /**
     * Returns the spectrogram as one flat array laid out [80][3000], which is
     * the tensor Whisper's encoder takes as `input_features` of shape
     * [1, 80, 3000].
     */
    fun logMel(samples: FloatArray): FloatArray {
        // Pad or cut to exactly thirty seconds, then reflect-pad for the STFT.
        val padded = FloatArray(N_SAMPLES + N_FFT)
        val n = minOf(samples.size, N_SAMPLES)
        System.arraycopy(samples, 0, padded, HALF_FFT, n)
        for (i in 1..HALF_FFT) {
            padded[HALF_FFT - i] = padded[HALF_FFT + i]
            padded[HALF_FFT + N_SAMPLES - 1 + i] = padded[HALF_FFT + N_SAMPLES - 1 - i]
        }

        val mel = FloatArray(N_MELS * N_FRAMES)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val power = FloatArray(N_FREQS)
        var maxLog = Float.NEGATIVE_INFINITY

        for (t in 0 until N_FRAMES) {
            val start = t * HOP
            for (i in 0 until N_FFT) {
                re[i] = padded[start + i] * window[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            for (k in 0 until N_FREQS) power[k] = re[k] * re[k] + im[k] * im[k]

            for (m in 0 until N_MELS) {
                val f = filters[m]
                var acc = 0f
                for (k in 0 until N_FREQS) acc += f[k] * power[k]
                val v = log10(max(acc, 1e-10f))
                mel[m * N_FRAMES + t] = v
                if (v > maxLog) maxLog = v
            }
        }

        val floor = maxLog - 8f
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + 4f) / 4f
        return mel
    }

    /**
     * Mixed-radix Cooley–Tukey FFT for a fixed size, here 400 = 2⁴·5². Whisper
     * uses a 400-point transform, and a power-of-two transform of zero-padded
     * input would put its bins at different frequencies, so the size has to be
     * honoured exactly. Small and unoptimised; three thousand of them take a
     * fraction of a second.
     */
    class Fft(private val n: Int) {
        private val factors: IntArray
        private val cosTable = FloatArray(n) { cos(2.0 * PI * it / n).toFloat() }
        private val sinTable = FloatArray(n) { -sin(2.0 * PI * it / n).toFloat() }

        init {
            val f = ArrayList<Int>()
            var m = n
            for (p in intArrayOf(2, 3, 5, 7)) while (m % p == 0) { f.add(p); m /= p }
            require(m == 1) { "FFT size $n has a prime factor above 7" }
            factors = f.toIntArray()
        }

        /** In place; [re] and [im] hold the input and receive the output. */
        fun transform(re: FloatArray, im: FloatArray) {
            val outRe = FloatArray(n)
            val outIm = FloatArray(n)
            recurse(re, im, 0, 1, n, 0, outRe, outIm, 0)
            System.arraycopy(outRe, 0, re, 0, n)
            System.arraycopy(outIm, 0, im, 0, n)
        }

        /**
         * DFT of the [length] samples at `in[offset + k*stride]`, written to
         * `out[outOffset .. outOffset+length)`. Splits by the next radix.
         */
        private fun recurse(
            inRe: FloatArray, inIm: FloatArray, offset: Int, stride: Int, length: Int, depth: Int,
            outRe: FloatArray, outIm: FloatArray, outOffset: Int
        ) {
            if (length == 1) {
                outRe[outOffset] = inRe[offset]
                outIm[outOffset] = inIm[offset]
                return
            }
            val p = factors[depth]
            val sub = length / p
            // Sub-transforms of the p interleaved sequences, side by side in out.
            for (r in 0 until p) {
                recurse(inRe, inIm, offset + r * stride, stride * p, sub, depth + 1, outRe, outIm, outOffset + r * sub)
            }
            // Combine: X[k] = Σ_r W_length^{rk} · S_r[k mod sub]
            val tmpRe = FloatArray(length)
            val tmpIm = FloatArray(length)
            val twiddleStep = n / length
            for (k in 0 until length) {
                var accRe = 0f
                var accIm = 0f
                val kk = k % sub
                for (r in 0 until p) {
                    val sRe = outRe[outOffset + r * sub + kk]
                    val sIm = outIm[outOffset + r * sub + kk]
                    val idx = ((r * k) % length) * twiddleStep
                    val c = cosTable[idx]
                    val s = sinTable[idx]
                    accRe += sRe * c - sIm * s
                    accIm += sRe * s + sIm * c
                }
                tmpRe[k] = accRe
                tmpIm[k] = accIm
            }
            System.arraycopy(tmpRe, 0, outRe, outOffset, length)
            System.arraycopy(tmpIm, 0, outIm, outOffset, length)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 80
        const val N_FREQS = N_FFT / 2 + 1
        const val CHUNK_SECONDS = 30
        const val N_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
        const val N_FRAMES = N_SAMPLES / HOP
        private const val HALF_FFT = N_FFT / 2
    }
}
