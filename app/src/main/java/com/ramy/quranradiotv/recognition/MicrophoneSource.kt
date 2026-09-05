package com.ramy.quranradiotv.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The microphone as a source of 16 kHz mono audio, for recitation that is
 * playing somewhere in the room rather than through this app.
 *
 * The caller checks the RECORD_AUDIO permission before starting this; the
 * suppression below is that promise. A device with no usable microphone —
 * many televisions — shows up as [Listener.onUnavailable] rather than an
 * exception.
 */
class MicrophoneSource(private val listener: Listener) {

    interface Listener {
        fun onAudio(pcm: ShortArray)
        fun onUnavailable()
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val minBuffer = AudioRecord.getMinBufferSize(
                SpeechEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SpeechEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, CHUNK_SAMPLES * 4)
                )
            } catch (e: Exception) {
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                running = false
                listener.onUnavailable()
                return@Thread
            }
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    listener.onUnavailable()
                    return@Thread
                }
                val buffer = ShortArray(CHUNK_SAMPLES)
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) listener.onAudio(buffer.copyOf(read))
                    else if (read < 0) break
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }, "recitation-mic").also { it.start() }
    }

    fun stop() {
        running = false
        thread = null
    }

    companion object {
        /** A quarter of a second per hand-over. */
        private const val CHUNK_SAMPLES = SpeechEngine.SAMPLE_RATE / 4
    }
}
