package com.ramy.quranradiotv

import android.content.Context
import android.content.SharedPreferences

/**
 * All persisted user settings live here. Backed by SharedPreferences so values
 * survive app restarts and device reboots.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Stream URL -------------------------------------------------------

    var streamUrl: String
        get() = sp.getString(KEY_STREAM_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_STREAM_URL
        set(value) = sp.edit().putString(KEY_STREAM_URL, value.trim()).apply()

    val isStreamUrlDefault: Boolean
        get() = streamUrl == DEFAULT_STREAM_URL

    fun resetStreamUrl() = sp.edit().remove(KEY_STREAM_URL).apply()

    // ---- Background -------------------------------------------------------

    /** One of [BG_DEFAULT], [BG_NONE], [BG_CUSTOM]. */
    var backgroundMode: String
        get() = sp.getString(KEY_BG_MODE, BG_DEFAULT) ?: BG_DEFAULT
        set(value) = sp.edit().putString(KEY_BG_MODE, value).apply()

    /** content:// or file:// or http(s):// URI of the user-chosen image. */
    var customBackgroundUri: String?
        get() = sp.getString(KEY_BG_URI, null)?.takeIf { it.isNotBlank() }
        set(value) = sp.edit().putString(KEY_BG_URI, value).apply()

    fun resetBackground() {
        sp.edit()
            .remove(KEY_BG_MODE)
            .remove(KEY_BG_URI)
            .apply()
    }

    // ---- Sleep timer ------------------------------------------------------

    /**
     * Wall-clock instant at which playback should stop, or 0 when no timer is set.
     *
     * Persisted so the home-screen widget can render the timer from a cold process,
     * and so an armed timer is not silently forgotten when the process goes away.
     * Wall clock rather than elapsed-realtime on purpose: it is the only one of the
     * two that still means something after a reboot.
     */
    var sleepDeadline: Long
        get() = sp.getLong(KEY_SLEEP_DEADLINE, 0L)
        set(value) = sp.edit().putLong(KEY_SLEEP_DEADLINE, value).apply()

    // ---- Surah recognition ------------------------------------------------

    /**
     * Whether the user has asked for the Surah and Ayah being recited to be
     * identified. Off by default: it needs a large model downloaded first, and
     * it costs a processor core while it listens.
     */
    var recognitionEnabled: Boolean
        get() = sp.getBoolean(KEY_RECOGNITION_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_RECOGNITION_ENABLED, value).apply()

    /** One of [SOURCE_RADIO], [SOURCE_MIC]: where the recogniser listens. */
    var recognitionSource: String
        get() = sp.getString(KEY_RECOGNITION_SOURCE, SOURCE_RADIO) ?: SOURCE_RADIO
        set(value) = sp.edit().putString(KEY_RECOGNITION_SOURCE, value).apply()

    /** How long an identified Surah stays on screen after it was last heard. */
    var recognitionHoldMillis: Long
        get() = sp.getLong(KEY_RECOGNITION_HOLD, DEFAULT_RECOGNITION_HOLD_MS)
        set(value) = sp.edit().putLong(KEY_RECOGNITION_HOLD, value).apply()

    /**
     * Ids of the model downloads handed to the system's DownloadManager, comma
     * separated; empty when none are running. Kept so downloads that finished
     * while the app was gone are still picked up and installed.
     */
    var modelDownloadIds: String
        get() = sp.getString(KEY_MODEL_DOWNLOAD_IDS, "") ?: ""
        set(value) = sp.edit().putString(KEY_MODEL_DOWNLOAD_IDS, value).apply()

    // ---- Everything -------------------------------------------------------

    fun resetAll() = sp.edit().clear().apply()

    companion object {
        private const val FILE = "quran_radio_prefs"

        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_BG_MODE = "bg_mode"
        private const val KEY_BG_URI = "bg_uri"
        private const val KEY_SLEEP_DEADLINE = "sleep_deadline"
        private const val KEY_RECOGNITION_ENABLED = "recognition_enabled"
        private const val KEY_RECOGNITION_SOURCE = "recognition_source"
        private const val KEY_RECOGNITION_HOLD = "recognition_hold_ms"
        private const val KEY_MODEL_DOWNLOAD_IDS = "model_download_ids"

        const val DEFAULT_STREAM_URL =
            "https://service.webvideocore.net/CL1olYogIrDWvwqiIKK7eCxOS4PStqG9DuEjAr2ZjZQtvS3d4y9r0cvRhvS17SGN/a_7a4vuubc6mo8.m3u8"

        const val BG_DEFAULT = "default"
        const val BG_NONE = "none"
        const val BG_CUSTOM = "custom"

        const val SOURCE_RADIO = "radio"
        const val SOURCE_MIC = "mic"

        const val DEFAULT_RECOGNITION_HOLD_MS = 2 * 60_000L
    }
}
