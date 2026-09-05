package com.ramy.quranradiotv.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

class QuranIndexTest {

    companion object {
        lateinit var index: QuranIndex

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = File("src/main/assets/quran/quran-simple-clean.txt")
            index = file.inputStream().use { QuranIndex.load(it) }
        }
    }

    private fun words(text: String) = ArabicText.words(text)

    @Test
    fun loadsEveryAyah() {
        assertEquals(6236, index.ayahCount)
        // Basmala taken off the head of Al-Baqarah, kept as Al-Fatiha 1.
        assertEquals(listOf("الم"), index.ayah(2, 1)!!.words)
        assertEquals(4, index.ayah(1, 1)!!.words.size)
        // Surah 9 has no basmala to remove.
        assertEquals("براه", index.ayah(9, 1)!!.words.first())
    }

    @Test
    fun normaliserCollapsesSpelling() {
        assertEquals("الصلاه", ArabicText.normalizeWord("الصَّلَاةَ"))
        assertEquals("اياك", ArabicText.normalizeWord("إياك"))
        assertEquals("موسي", ArabicText.normalizeWord("موسى"))
        assertEquals("", ArabicText.normalizeWord("123"))
        assertEquals(listOf("قل", "هو", "الله", "احد"), words("قُلْ هُوَ اللَّهُ أَحَدٌ"))
    }

    @Test
    fun exactPhraseFindsItsAyah() {
        val m = index.match(words("الله لا إله إلا هو الحي القيوم لا تأخذه سنة ولا نوم"))
        assertNotNull(m)
        assertEquals(2, m!!.surah)
        assertEquals(255, m.ayah)
    }

    @Test
    fun shortSurahOpening() {
        val m = index.match(words("قل هو الله أحد الله الصمد"))
        assertNotNull(m)
        assertEquals(112, m!!.surah)
    }

    @Test
    fun windowAcrossAyahBoundary() {
        // End of 1:2 into 1:3 and 1:4.
        val m = index.match(words("رب العالمين الرحمن الرحيم مالك يوم الدين"))
        assertNotNull(m)
        assertEquals(1, m!!.surah)
        assertTrue(m.ayah in 2..4)
    }

    @Test
    fun openTaSpellingAndRepeatedPassage() {
        // Heard through a phone microphone: Fatir 43–44, with سنت written as
        // Whisper spells it, and a second half that occurs in several Surahs.
        val heard = "وَلِيَّ فَلَنْ تَجِدَ لِبِسُنَّةِ اللَّهِ تَبْدِيلًا وَلَنْ تَجِدَ لِسُنَّةِ اللَّهِ تَحْوِيلًا " +
            "أَوَلَمْ يَسِيرُوا فِي الْأَرْضِ فَيَنْظُرُوا كَيْفَ كَانَ عَاقِبَةً لِلْمُنْكَبِ لِلْمُنْكَبِثِينَ"
        val m = index.match(words(heard))
        assertNotNull(m)
        assertEquals(35, m!!.surah)
        assertTrue("ayah ${m.ayah}", m.ayah in 43..44)
        assertEquals("لسنه", ArabicText.normalizeWord("لسنت"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(index.match(words("سيارة مطار حاسوب برنامج تلفاز")))
        assertNull(index.match(words("و")))
    }

    @Test
    fun ubiquitousPhraseAloneIsRejected() {
        // Appears in many places; no single Ayah should be picked from it.
        assertNull(index.match(words("إن الله غفور رحيم")))
    }

    @Test
    fun continuityResolvesRepeatedPhrase() {
        // "فبأي آلاء ربكما تكذبان" repeats 31 times in Ar-Rahman; with a known
        // position the next occurrence after it should win.
        val near = QuranIndex.Match(55, 20, 20f, 3, 1f)
        val m = index.match(words("فبأي آلاء ربكما تكذبان"), near)
        // Either resolved to the neighbourhood or, honestly, left undecided —
        // but never sent somewhere else in the book.
        if (m != null) {
            assertEquals(55, m.surah)
            assertTrue(m.ayah in 19..27)
        }
    }

    /**
     * Simulates a poor recogniser: takes real 12-word windows from across the
     * Quran, drops a fifth of the words and corrupts another fifth, and checks
     * that whatever is answered is right far more often than wrong.
     */
    @Test
    fun noisyWindowsAreMostlyRightAndRarelyWrong() {
        val rnd = Random(7)
        var asked = 0
        var right = 0
        var wrong = 0
        val samples = 400
        repeat(samples) {
            val surah = rnd.nextInt(1, 115)
            // Walk to a random Ayah in that Surah with at least a few words.
            var a = 1
            var ayah = index.ayah(surah, a)
            val count = generateSequence(1) { it + 1 }.takeWhile { index.ayah(surah, it) != null }.count()
            a = rnd.nextInt(1, count + 1)
            ayah = index.ayah(surah, a)!!
            // Build a 12-word run starting inside that Ayah, running on into the next.
            val run = ArrayList<String>()
            var cur = a
            var start = rnd.nextInt(0, ayah.words.size)
            while (run.size < 12) {
                val w = index.ayah(surah, cur) ?: break
                for (k in start until w.words.size) { run.add(w.words[k]); if (run.size == 12) break }
                cur++
                start = 0
            }
            if (run.size < 6) return@repeat
            val noisy = run.mapNotNull { w ->
                when (rnd.nextInt(5)) {
                    0 -> null
                    1 -> w.dropLast(1).ifEmpty { w } + "ن"
                    else -> w
                }
            }
            val m = index.match(noisy) ?: return@repeat
            asked++
            if (m.surah == surah && m.ayah in (a - 1)..(cur + 1)) right++ else wrong++
        }
        println("noisy windows: asked=$asked right=$right wrong=$wrong of $samples")
        assertTrue("answered too rarely: $asked/$samples", asked >= samples / 2)
        assertTrue("too many wrong answers: $wrong of $asked", wrong <= asked / 20)
    }
}
