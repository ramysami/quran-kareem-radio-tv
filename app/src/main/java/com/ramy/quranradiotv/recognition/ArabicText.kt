package com.ramy.quranradiotv.recognition

/**
 * Brings Arabic text down to a spelling that a speech recogniser and the
 * printed Quran can agree on.
 *
 * The two sides differ in ways that carry no sound: vowel marks, hamza
 * carriers (أ إ آ all read as a plain ا at this level), the final ى against
 * ي, ة against ه. The recogniser also has no notion of where an Ayah ends, so
 * everything arrives as one run of words. Collapsing both texts the same way
 * means a word is matched on how it is pronounced rather than on how it
 * happened to be written.
 */
object ArabicText {

    /**
     * One normalised word, or an empty string for anything that is not an
     * Arabic word at all (digits, Latin, punctuation the recogniser emitted).
     */
    fun normalizeWord(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\u0623', '\u0625', '\u0622', '\u0671' -> out.append('\u0627') // أ إ آ ٱ → ا
                '\u0649' -> out.append('\u064A')                                // ى → ي
                '\u0626' -> out.append('\u064A')                                // ئ → ي
                '\u0624' -> out.append('\u0648')                                // ؤ → و
                '\u0629' -> out.append('\u0647')                                // ة → ه
                '\u0621' -> Unit                                                // bare hamza: inaudible at this level
                in '\u0621'..'\u064A' -> out.append(ch)                          // the letters themselves
                // Vowel marks, tanwin, shadda, sukun, the small Quranic
                // annotation signs, and tatweel carry no sound of their own.
                in '\u0610'..'\u061A', in '\u064B'..'\u065F', '\u0670',
                in '\u06D6'..'\u06ED', '\u0640' -> Unit
                else -> Unit
            }
        }
        // The Quran writes some feminine endings with an open ta — سنت, رحمت,
        // نعمت — where a recogniser, like modern spelling, writes ة. Both come
        // out of the ة → ه rule above as ه if the final ت is folded in too.
        if (out.length > 1 && out[out.length - 1] == '\u062A') out.setCharAt(out.length - 1, '\u0647')
        return out.toString()
    }

    /** Splits on whitespace and normalises, dropping what does not survive. */
    fun words(text: String): List<String> =
        text.split(WHITESPACE).map(::normalizeWord).filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}
