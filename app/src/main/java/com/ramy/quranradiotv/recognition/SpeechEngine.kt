package com.ramy.quranradiotv.recognition

import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The speech recogniser on its own thread: Whisper, loaded once, handed
 * stretches of audio to transcribe.
 *
 * Loading takes a few seconds and a few hundred megabytes, and a transcription
 * of thirty seconds takes a second or more of a busy processor, so all of it
 * happens on one worker thread the callers never wait on. Each request carries
 * an id the caller chose, so an answer that arrives after the question was
 * withdrawn can be recognised and dropped.
 */
class SpeechEngine(
    private val modelDir: File,
    private val melFilters: () -> InputStream,
    private val listener: Listener,
) {
    interface Listener {
        /** Loaded and ready to transcribe. */
        fun onReady()
        /** The text heard in the audio handed over under [requestId]. */
        fun onTranscript(requestId: Int, text: String)
        fun onFailed(reason: Throwable)
    }

    private class Job(val id: Int, val samples: FloatArray)

    private val queue = LinkedBlockingQueue<Job>()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "recitation-asr").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    /** Queues [count] samples of [samples] (16 kHz mono, 16-bit) for transcription. Never blocks. */
    fun transcribe(requestId: Int, samples: ShortArray, count: Int) {
        if (!running) return
        val floats = FloatArray(count) { samples[it] / 32768f }
        queue.offer(Job(requestId, floats))
    }

    private fun run() {
        var model: WhisperModel? = null
        try {
            model = WhisperModel(modelDir, melFilters(), threads = THREADS)
            listener.onReady()
            while (running) {
                val job = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val t0 = System.currentTimeMillis()
                val text = model.transcribe(job.samples)
                Log.d(TAG, "transcribed ${job.samples.size / WhisperFeatures.SAMPLE_RATE}s in ${System.currentTimeMillis() - t0} ms")
                if (running) listener.onTranscript(job.id, text)
            }
        } catch (e: InterruptedException) {
            // Asked to stop.
        } catch (e: Throwable) {
            Log.w(TAG, "speech engine failed", e)
            if (running) listener.onFailed(e)
        } finally {
            runCatching { model?.close() }
        }
    }

    companion object {
        private const val TAG = "SpeechEngine"
        const val SAMPLE_RATE = WhisperFeatures.SAMPLE_RATE

        /** Enough to make use of a phone's big cores without starving playback. */
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
