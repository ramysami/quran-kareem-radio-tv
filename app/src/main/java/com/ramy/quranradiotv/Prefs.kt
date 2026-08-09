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

    // ---- Everything -------------------------------------------------------

    fun resetAll() = sp.edit().clear().apply()

    companion object {
        private const val FILE = "quran_radio_prefs"

        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_BG_MODE = "bg_mode"
        private const val KEY_BG_URI = "bg_uri"

        const val DEFAULT_STREAM_URL = "https://stream.radiojar.com/8s5u5tpdtwzuv"

        const val BG_DEFAULT = "default"
        const val BG_NONE = "none"
        const val BG_CUSTOM = "custom"
    }
}
