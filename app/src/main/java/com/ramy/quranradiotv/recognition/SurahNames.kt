package com.ramy.quranradiotv.recognition

import android.content.Context
import com.ramy.quranradiotv.R

/** The Surah names in the UI's language, from the string-array resource. */
object SurahNames {
    fun name(context: Context, surah: Int): String {
        val names = context.resources.getStringArray(R.array.surah_names)
        return if (surah in 1..names.size) names[surah - 1] else surah.toString()
    }
}
