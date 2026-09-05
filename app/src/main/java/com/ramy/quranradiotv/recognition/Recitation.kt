package com.ramy.quranradiotv.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ramy.quranradiotv.PlaybackStatus
import com.ramy.quranradiotv.Prefs
import java.util.concurrent.Executors

/**
 * Answers "which Surah is this?" when asked, by listening for half a minute.
 *
 * Process-wide, like [PlaybackStatus], because the pieces that feed it live in
 * different places: the audio tap in the playback service, the microphone
 * wherever the screen is, the settings that decide between them.
 *
 * Recognition is on demand: a press of the button opens a [WINDOW_MS] window
 * during which audio is collected. Whisper hears thirty seconds at a time, so
 * the whole window is transcribed once at the end — and once halfway through,
 * on what there is so far, so an answer can appear early and be confirmed or
 * corrected by the full pass. The answer stays on screen for the hold time
 * from Settings. The model is loaded on the first press, kept while the player
 * screen stays in front, and let go when it leaves.
 *
 * A position found recently biases the next answer toward the Ayahs just
 * after it: recitation on the radio runs through one Surah for a long time,
 * and a phrase that occurs in twenty places should be read as the nearest one.
 */
object Recitation {

    enum class Source { RADIO, MIC }

    sealed class State {
        /** Feature off in Settings. */
        object Off : State()
        /** On, but there is no model to run yet (see [ModelStore.status]). */
        object ModelMissing : State()
        /** On and ready; waiting to be asked. */
        object Idle : State()
        /** Loading the model into memory; a window opens when it is ready. */
        object Loading : State()
        /** Collecting audio from [source]. */
        class Listening(val source: Source) : State()
        /** The window closed; the audio is being transcribed. */
        object Finishing : State()
        /** The window closed and nothing in it could be placed. */
        object NotRecognized : State()
        /** Radio source chosen, but the radio is not playing. */
        object NeedsPlayback : State()
        /** Microphone chosen, permission not granted. */
        object NeedsMicPermission : State()
        /** Microphone chosen, none usable on this device. */
        object MicUnavailable : State()
        object Failed : State()
    }

    class Current(val surah: Int, val ayah: Int, val confidence: Float)

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    private var appContext: Context? = null
    private var prefs: Prefs? = null

    @Volatile
    var state: State = State.Off
        private set

    @Volatile
    var current: Current? = null
        private set

    /** True while the player screen is showing. */
    private var foreground = false

    // Read from the playback thread by the audio tap, written on the main one.
    @Volatile
    private var engine: SpeechEngine? = null
    @Volatile
    private var engineSource: Source? = null
    @Volatile
    private var listening = false
    private var mic: MicrophoneSource? = null

    /** A press arrived while the model was still loading. */
    private var startWhenReady = false

    /** The window's audio, and how much of it is filled. Guarded by itself. */
    private val buffer = ShortArray(SpeechEngine.SAMPLE_RATE * WhisperFeatures.CHUNK_SECONDS)
    private var filled = 0

    /** Uptime at which the current window opened. */
    private var windowOpened = 0L

    /**
     * Identifies the window each transcription request belongs to, so one that
     * comes back after its window was abandoned is ignored. The halfway pass is
     * `generation * 2`, the final one `generation * 2 + 1`.
     */
    private var generation = 0
    private var awaitingFinal = false

    /** Whether anything was placed since the window opened, and the best of it. */
    private var matchedInWindow = false
    private var windowBest: QuranIndex.Match? = null

    private var index: QuranIndex? = null
    private val indexLoader = Executors.newSingleThreadExecutor()

    /** Last accepted position, for continuity; outlives [current]'s display. */
    private var anchor: QuranIndex.Match? = null
    private var anchorAt = 0L

    /** Whether the radio tap should bother converting audio for us. */
    @JvmStatic
    val wantsRadioAudio: Boolean
        get() = listening && engineSource == Source.RADIO

    /** Seconds left in the open window, for the button to count down. */
    val secondsLeft: Int
        get() = if (state is State.Listening)
            ((WINDOW_MS - (SystemClock.uptimeMillis() - windowOpened) + 999) / 1000).toInt().coerceAtLeast(0)
        else 0

    val source: Source
        get() = if (prefs?.recognitionSource == Prefs.SOURCE_MIC) Source.MIC else Source.RADIO

    val isEnabled: Boolean
        get() = isSupported && prefs?.recognitionEnabled == true

    /** ONNX Runtime's native library needs Android 7.0. */
    val isSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= 24

    // ---------------------------------------------------------------- setup

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = Prefs(app)
        PlaybackStatus.addListener { onPlaybackChanged() }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
        l()
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        handler.post { listeners.toList().forEach { it() } }
    }

    /** The player screen came in front of, or left, the user. */
    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) {
            // Keep the answer and its hold timer; let the model go.
            cancelWindow()
            releaseEngine()
        }
        refreshIdleState()
    }

    /** Settings changed something that bears on whether or where we listen. */
    fun onSettingsChanged() {
        cancelWindow()
        if (engineSource != null && engineSource != source) releaseEngine()
        refreshIdleState()
    }

    /** The model was installed or removed. */
    fun onModelChanged() = handler.post {
        cancelWindow()
        releaseEngine()
        refreshIdleState()
    }

    /** The user answered the microphone permission prompt: carry on if granted. */
    fun onPermissionResult() {
        if (state is State.NeedsMicPermission && source == Source.MIC && micGranted()) identify()
        else refreshIdleState()
    }

    private fun onPlaybackChanged() {
        // A radio window with the radio gone has nothing left to hear; close it
        // on what it has.
        if (listening && engineSource == Source.RADIO && !PlaybackStatus.isActive) closeWindow()
        else if (state is State.NeedsPlayback && PlaybackStatus.isActive) refreshIdleState()
    }

    private fun micGranted(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * The state to show while nothing is happening: off, missing its model, or
     * ready. A window in progress is left alone.
     */
    private fun refreshIdleState() {
        val ctx = appContext ?: return
        val p = prefs ?: return
        if (!p.recognitionEnabled || !isSupported) {
            handler.removeCallbacks(expire)
            current = null
            anchor = null
            setState(State.Off)
            return
        }
        if (ModelStore.installedModelDir(ctx) == null) {
            setState(State.ModelMissing)
            return
        }
        when (state) {
            State.Loading, is State.Listening, State.Finishing -> Unit
            else -> setState(State.Idle)
        }
    }

    // ---------------------------------------------------------------- the ask

    /**
     * The button. Says what stands in the way, if anything does; otherwise
     * opens a window — after loading the model, the first time.
     */
    fun identify() {
        val ctx = appContext ?: return
        val p = prefs ?: return
        if (!p.recognitionEnabled || !isSupported) return
        if (state is State.Listening || state is State.Loading || state is State.Finishing) return

        handler.removeCallbacks(clearTransient)
        val modelDir = ModelStore.installedModelDir(ctx)
        if (modelDir == null) {
            setState(State.ModelMissing)
            return
        }
        val wanted = source
        if (wanted == Source.MIC && !micGranted()) {
            setState(State.NeedsMicPermission)
            return
        }
        if (wanted == Source.RADIO && !PlaybackStatus.isActive) {
            setState(State.NeedsPlayback)
            handler.postDelayed(clearTransient, TRANSIENT_MS)
            return
        }

        if (engine != null && engineSource != wanted) releaseEngine()
        if (engine == null) {
            ensureIndex(ctx)
            engineSource = wanted
            engine = SpeechEngine(modelDir, { ctx.assets.open(MEL_ASSET) }, engineListener).also { it.start() }
            startWhenReady = true
            setState(State.Loading)
            return
        }
        openWindow()
    }

    private fun openWindow() {
        val src = engineSource ?: return
        synchronized(buffer) { filled = 0 }
        generation++
        awaitingFinal = false
        matchedInWindow = false
        windowBest = null
        windowOpened = SystemClock.uptimeMillis()
        listening = true
        if (src == Source.MIC) mic = MicrophoneSource(micListener).also { it.start() }
        setState(State.Listening(src))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
        handler.removeCallbacks(halfway)
        handler.postDelayed(halfway, WINDOW_MS / 2)
        handler.removeCallbacks(close)
        handler.postDelayed(close, WINDOW_MS)
        Log.d(TAG, "window $generation opened ($src)")
    }

    /** Halfway: transcribe what there is so far, for an early answer. */
    private val halfway = Runnable {
        if (!listening) return@Runnable
        val (copy, n) = snapshot()
        if (n >= SpeechEngine.SAMPLE_RATE * MIN_AUDIO_SECONDS) engine?.transcribe(generation * 2, copy, n)
    }

    private val close = Runnable { closeWindow() }

    /** Once a second while the window is open, so the button can count down. */
    private val tick = object : Runnable {
        override fun run() {
            if (!listening) return
            notifyListeners()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun snapshot(): Pair<ShortArray, Int> = synchronized(buffer) {
        buffer.copyOf(filled) to filled
    }

    /** RMS of the audio in dBFS, for the log: silence reads far below -60. */
    private fun level(pcm: ShortArray, n: Int): String {
        if (n == 0) return "n/a"
        var sum = 0.0
        for (i in 0 until n) { val v = pcm[i] / 32768.0; sum += v * v }
        val rms = Math.sqrt(sum / n)
        return "%.1f dBFS".format(20 * Math.log10(rms.coerceAtLeast(1e-9)))
    }

    /** Stops collecting and sends the whole window off to be transcribed. */
    private fun closeWindow() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(halfway)
        handler.removeCallbacks(close)
        mic?.stop()
        mic = null
        val (copy, n) = snapshot()
        Log.d(TAG, "window $generation closed after ${SystemClock.uptimeMillis() - windowOpened} ms with ${n / SpeechEngine.SAMPLE_RATE}s of audio, level ${level(copy, n)}")
        if (n < SpeechEngine.SAMPLE_RATE * MIN_AUDIO_SECONDS) {
            finishWindow()
            return
        }
        awaitingFinal = true
        setState(State.Finishing)
        engine?.transcribe(generation * 2 + 1, copy, n)
        handler.postDelayed(finishTimeout, FINISH_TIMEOUT_MS)
    }

    private val finishTimeout = Runnable {
        Log.w(TAG, "transcription did not come back in time")
        finishWindow()
    }

    private fun finishWindow() {
        handler.removeCallbacks(finishTimeout)
        awaitingFinal = false
        if (state !is State.Finishing && state !is State.Listening) return
        Log.d(TAG, "window $generation finished, matched=$matchedInWindow")
        if (matchedInWindow) {
            setState(State.Idle)
        } else {
            setState(State.NotRecognized)
            handler.postDelayed(clearTransient, TRANSIENT_MS)
        }
    }

    /** A window abandoned — the screen left, or settings changed under it. */
    private fun cancelWindow() {
        listening = false
        startWhenReady = false
        awaitingFinal = false
        generation++ // orphans any transcription still in flight
        handler.removeCallbacks(tick)
        handler.removeCallbacks(halfway)
        handler.removeCallbacks(close)
        handler.removeCallbacks(finishTimeout)
        handler.removeCallbacks(clearTransient)
        mic?.stop()
        mic = null
        if (state is State.Listening || state is State.Finishing || state is State.Loading) setState(State.Idle)
    }

    private fun releaseEngine() {
        engine?.stop()
        engine = null
        engineSource = null
    }

    /** Takes a "not recognised" or "press play" message down again. */
    private val clearTransient = Runnable { refreshIdleState() }

    private fun setState(s: State) {
        if (s === state) return
        state = s
        notifyListeners()
    }

    private fun ensureIndex(ctx: Context) {
        if (index != null) return
        indexLoader.execute {
            if (index != null) return@execute
            try {
                ctx.assets.open("quran/quran-simple-clean.txt").use { index = QuranIndex.load(it) }
            } catch (e: Exception) {
                Log.e(TAG, "could not load Quran text", e)
            }
        }
    }

    // ---------------------------------------------------------------- audio in

    /** From the radio tap or the microphone, on their own threads. */
    fun onAudio(pcm: ShortArray) {
        if (!listening) return
        synchronized(buffer) {
            val room = buffer.size - filled
            val n = minOf(room, pcm.size)
            if (n > 0) {
                System.arraycopy(pcm, 0, buffer, filled, n)
                filled += n
            }
        }
    }

    private val micListener = object : MicrophoneSource.Listener {
        override fun onAudio(pcm: ShortArray) = this@Recitation.onAudio(pcm)
        override fun onUnavailable() {
            handler.post {
                cancelWindow()
                setState(State.MicUnavailable)
            }
        }
    }

    private val engineListener = object : SpeechEngine.Listener {
        override fun onReady() {
            handler.post {
                if (state !is State.Loading) return@post
                if (!startWhenReady) {
                    setState(State.Idle)
                    return@post
                }
                startWhenReady = false
                // The radio may have stopped while the model loaded.
                if (engineSource == Source.RADIO && !PlaybackStatus.isActive) {
                    setState(State.NeedsPlayback)
                    handler.postDelayed(clearTransient, TRANSIENT_MS)
                } else {
                    openWindow()
                }
            }
        }

        override fun onTranscript(requestId: Int, text: String) {
            handler.post {
                val isFinal = requestId % 2 == 1
                if (requestId / 2 != generation) return@post // from a window since abandoned
                Log.d(TAG, "transcript (${if (isFinal) "final" else "halfway"}): $text")
                consider(text)
                if (isFinal && awaitingFinal) finishWindow()
            }
        }

        override fun onFailed(reason: Throwable) {
            handler.post {
                cancelWindow()
                releaseEngine()
                setState(State.Failed)
            }
        }
    }

    // ---------------------------------------------------------------- matching

    /**
     * Places the transcript. The end of it says where the reciter is now, so
     * the last stretch of words is tried first; the whole transcript is the
     * fallback when that stretch alone is too little or too common.
     */
    private fun consider(text: String) {
        val idx = index ?: return
        val words = ArabicText.words(text)
        if (words.size < MIN_WORDS) return
        val now = System.currentTimeMillis()
        val near = anchor?.takeIf { now - anchorAt < ANCHOR_TTL_MS }

        val tail = words.takeLast(TAIL_WORDS)
        val match = idx.match(tail, near) ?: idx.match(words, near)
        Log.d(TAG, "${words.size} words -> ${match ?: "no match"}")
        if (match != null) accept(match, now)
    }

    /**
     * The final pass has heard more than the halfway one, so it replaces it —
     * unless it is clearly weaker, which is a recogniser losing the thread
     * rather than finding it.
     */
    private fun accept(match: QuranIndex.Match, now: Long) {
        val best = windowBest
        if (best != null && match.surah != best.surah && match.score < best.score * REPLACE_SHARE) return
        windowBest = match
        matchedInWindow = true
        anchor = match
        anchorAt = now
        current = Current(match.surah, match.ayah, match.confidence)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, prefs?.recognitionHoldMillis ?: Prefs.DEFAULT_RECOGNITION_HOLD_MS)
        notifyListeners()
    }

    /** The hold time ran out. */
    private val expire = Runnable {
        current = null
        notifyListeners()
    }

    private const val TAG = "Recitation"
    private const val MEL_ASSET = "whisper/mel_80.bin"

    /** How long the button listens: Whisper's whole thirty-second chunk. */
    const val WINDOW_MS = WhisperFeatures.CHUNK_SECONDS * 1000L

    /** Less audio than this is not worth a transcription. */
    private const val MIN_AUDIO_SECONDS = 3

    /** How long to wait for the final transcription on a slow device. */
    private const val FINISH_TIMEOUT_MS = 90_000L
    private const val TRANSIENT_MS = 6_000L

    private const val MIN_WORDS = 3
    private const val TAIL_WORDS = 14
    private const val REPLACE_SHARE = 0.8f

    /** How long a past position still steers the matcher. */
    private const val ANCHOR_TTL_MS = 10 * 60_000L
}
