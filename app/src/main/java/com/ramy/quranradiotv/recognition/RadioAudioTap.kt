package com.ramy.quranradiotv.recognition

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A copy of the radio's decoded audio, on its way to the speaker, turned into
 * what the recogniser wants: 16 kHz, one channel, 16-bit.
 *
 * Sits in ExoPlayer's audio pipeline as a tee, so it hears exactly what the
 * listener hears, with no microphone and no permission. It is always in the
 * chain — the player is built once — and costs nothing while nobody is
 * listening, because the first thing it does is ask.
 */
@UnstableApi
class RadioAudioTap : TeeAudioProcessor.AudioBufferSink {

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    /** Fractional read position carried between buffers, in input frames. */
    private var position = 0.0
    private var lastSample = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        position = 0.0
        lastSample = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!Recitation.wantsRadioAudio) return
        if (sampleRate <= 0 || channels <= 0) return

        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channels
        val frames = buffer.remaining() / frameBytes
        if (frames == 0) return

        // Down-mix to mono first; the tee's buffer is read-only and must be
        // left where it was.
        val mono = FloatArray(frames)
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var offset = src.position()
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += when (encoding) {
                    C.ENCODING_PCM_16BIT -> src.getShort(offset).toFloat() / 32768f
                    else -> src.getFloat(offset)
                }
                offset += bytesPerSample
            }
            mono[i] = sum / channels
        }

        Recitation.onAudio(resample(mono))
    }

    /**
     * Linear interpolation from the stream's rate down to 16 kHz. The radio is
     * band-limited speech; anything finer would be wasted on it.
     *
     * The input is treated as one continuous signal across buffers: the last
     * sample of the previous buffer sits at index -1 of this one, and the read
     * position is carried over so the output stays evenly spaced.
     */
    private fun resample(mono: FloatArray): ShortArray {
        val n = mono.size
        if (sampleRate == SpeechEngine.SAMPLE_RATE) {
            lastSample = mono[n - 1]
            return ShortArray(n) { toPcm(mono[it]) }
        }
        val step = sampleRate.toDouble() / SpeechEngine.SAMPLE_RATE
        val out = ShortArray(((n - position) / step).toInt() + 2)
        var count = 0
        var pos = position
        while (pos < n - 1) {
            val idx = Math.floor(pos).toInt()
            val frac = (pos - idx).toFloat()
            val a = if (idx < 0) lastSample else mono[idx]
            val b = mono[idx + 1]
            out[count++] = toPcm(a + (b - a) * frac)
            pos += step
        }
        position = pos - n
        lastSample = mono[n - 1]
        return out.copyOf(count)
    }

    private fun toPcm(sample: Float): Short =
        (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}
