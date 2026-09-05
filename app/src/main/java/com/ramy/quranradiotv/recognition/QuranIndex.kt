package com.ramy.quranradiotv.recognition

import java.io.BufferedReader
import java.io.InputStream
import kotlin.math.ln

/**
 * The whole Quran, indexed for finding an Ayah from a handful of words the
 * speech recogniser thinks it heard.
 *
 * Recognition of recitation is rough: words come back misspelt, dropped, or
 * run together, and never with an Ayah boundary. What survives is enough,
 * because the text being recited is fixed and known in full. Every pair of
 * consecutive words in the Quran is indexed here to the Ayah it starts in,
 * weighted by how rare the pair is — "الله الذين" is everywhere and says little,
 * "الكرسي" beside anything settles the matter. A window of recognised words is
 * scored by the pairs it shares with each Ayah, and the Ayah that gathers the
 * most weight, by a clear enough margin, is the answer.
 *
 * Recitation moves forward through one Surah, so a caller that already knows
 * where the reciter was gets a bias toward the Ayahs just after that spot: it
 * is what keeps a phrase that appears in twenty places from pulling the
 * display across the book.
 *
 * Pure Kotlin, on purpose, so it can be tested off the device.
 */
class QuranIndex private constructor(
    private val ayahs: Array<Ayah>,
    private val bigrams: HashMap<String, IntArray>,
    private val rareUnigrams: HashMap<String, IntArray>,
) {

    class Ayah(val surah: Int, val ayah: Int, val words: List<String>)

    class Match(
        val surah: Int,
        val ayah: Int,
        /** Total weight gathered — the absolute strength of the evidence. */
        val score: Float,
        /** How many distinct word pairs (or rare words) agreed. */
        val hits: Int,
        /** 0..1, for callers that only want to know how sure to be. */
        val confidence: Float,
    ) {
        fun isSameAyah(other: Match?) = other != null && other.surah == surah && other.ayah == ayah
        override fun toString() = "$surah:$ayah (score=%.1f hits=$hits)".format(score)
    }

    val ayahCount: Int get() = ayahs.size

    fun ayah(surah: Int, ayah: Int): Ayah? = ayahs.firstOrNull { it.surah == surah && it.ayah == ayah }

    /**
     * Finds the Ayah the given words most likely come from, or null when the
     * evidence is too thin or too divided.
     *
     * [words] should already be normalised (see [ArabicText]). [near] is the
     * last confirmed position, if any; candidates in the same Surah just after
     * it are favoured.
     */
    fun match(words: List<String>, near: Match? = null): Match? {
        if (words.size < 2) return null

        val scores = HashMap<Int, Float>()
        val hits = HashMap<Int, Int>()
        val seen = HashSet<String>()

        fun credit(ids: IntArray, weight: Float) {
            for (id in ids) {
                scores[id] = (scores[id] ?: 0f) + weight
                hits[id] = (hits[id] ?: 0) + 1
            }
        }

        for (i in 0 until words.size - 1) {
            val key = words[i] + " " + words[i + 1]
            if (!seen.add(key)) continue
            val ids = bigrams[key] ?: continue
            if (ids.size > MAX_BIGRAM_DF) continue
            credit(ids, idf(ids.size))
        }
        for (w in words) {
            if (!seen.add(w)) continue
            val ids = rareUnigrams[w] ?: continue
            credit(ids, RARE_WORD_WEIGHT * idf(ids.size))
        }
        if (scores.isEmpty()) return null

        // Continuity: the reciter was at `near` a moment ago, so the Ayahs
        // just after it are where the next words are most likely from.
        if (near != null) {
            for (id in scores.keys.toList()) {
                val a = ayahs[id]
                if (a.surah == near.surah) {
                    val delta = a.ayah - near.ayah
                    if (delta in -1..CONTINUITY_WINDOW) scores[id] = scores[id]!! * CONTINUITY_BOOST
                }
            }
        }

        // Thirty seconds of recitation runs across Ayah boundaries, so an
        // Ayah is judged with its neighbours: the evidence that landed on the
        // Ayahs either side of it counts for it too, at a discount. A passage
        // repeated in several Surahs then loses to the one whose surroundings
        // were also heard.
        val passage = HashMap<Int, Float>(scores.size)
        for ((id, own) in scores) {
            var total = own
            for (d in intArrayOf(-1, 1)) {
                val other = id + d
                if (other < 0 || other >= ayahs.size || ayahs[other].surah != ayahs[id].surah) continue
                total += (scores[other] ?: 0f) * NEIGHBOUR_SHARE
            }
            passage[id] = total
        }

        val ranked = passage.entries.sortedByDescending { it.value }
        val best = ranked[0]
        val bestAyah = ayahs[best.key]
        val bestHits = hits[best.key] ?: 0

        if (best.value < MIN_SCORE || (bestHits + neighbourHits(best.key, hits)) < MIN_HITS) return null

        // A runner-up in a different place must be clearly beaten. One in the
        // same neighbourhood is not a rival: recitation spans Ayah boundaries,
        // and both are parts of the same passage.
        val rival = ranked.drop(1).firstOrNull { e ->
            val a = ayahs[e.key]
            !(a.surah == bestAyah.surah && kotlin.math.abs(a.ayah - bestAyah.ayah) <= NEIGHBOUR_WINDOW)
        }
        if (rival != null && best.value < rival.value * MIN_MARGIN) return null

        // Among the leading passage, prefer the later Ayah the words could have
        // come from — the reciter only ever moves forward.
        var chosen = best
        for (e in ranked) {
            val a = ayahs[e.key]
            if (a.surah != bestAyah.surah) continue
            if (a.ayah <= ayahs[chosen.key].ayah) continue
            if (a.ayah - bestAyah.ayah > NEIGHBOUR_WINDOW) continue
            if (e.value >= best.value * FORWARD_SHARE) chosen = e
        }
        val chosenAyah = ayahs[chosen.key]

        return Match(
            surah = chosenAyah.surah,
            ayah = chosenAyah.ayah,
            score = best.value,
            hits = bestHits,
            confidence = (best.value / FULL_CONFIDENCE_SCORE).coerceIn(0f, 1f),
        )
    }

    private fun idf(df: Int): Float = ln(1f + ayahs.size.toFloat() / df)

    private fun neighbourHits(id: Int, hits: Map<Int, Int>): Int {
        var n = 0
        for (d in intArrayOf(-1, 1)) {
            val other = id + d
            if (other in ayahs.indices && ayahs[other].surah == ayahs[id].surah) n += hits[other] ?: 0
        }
        return n
    }

    companion object {
        /** Pairs occurring in more Ayahs than this say nothing worth counting. */
        private const val MAX_BIGRAM_DF = 300

        /** A single word is only evidence on its own when this rare. */
        private const val RARE_UNIGRAM_DF = 4
        private const val RARE_WORD_WEIGHT = 0.7f

        private const val MIN_SCORE = 9f
        private const val MIN_HITS = 2
        private const val MIN_MARGIN = 1.4f
        private const val NEIGHBOUR_WINDOW = 2
        private const val NEIGHBOUR_SHARE = 0.6f
        private const val FORWARD_SHARE = 0.6f
        private const val CONTINUITY_WINDOW = 6
        private const val CONTINUITY_BOOST = 1.35f
        private const val FULL_CONFIDENCE_SCORE = 24f

        private val BASMALA = ArabicText.words("بسم الله الرحمن الرحيم")

        /**
         * Reads Tanzil's `surah|ayah|text` format. The basmala Tanzil prefixes
         * to the first Ayah of every Surah is taken off (except in Al-Fatiha,
         * where it is the Ayah): left in, the opening of every Surah would
         * score against every other.
         */
        fun load(input: InputStream): QuranIndex {
            val ayahs = ArrayList<Ayah>(6236)
            input.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
                reader.forEachLine { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEachLine
                    val parts = line.split('|', limit = 3)
                    if (parts.size < 3) return@forEachLine
                    val surah = parts[0].toIntOrNull() ?: return@forEachLine
                    val ayah = parts[1].toIntOrNull() ?: return@forEachLine
                    var words = ArabicText.words(parts[2])
                    if (ayah == 1 && surah != 1 && words.size > BASMALA.size &&
                        words.subList(0, BASMALA.size) == BASMALA
                    ) {
                        words = words.subList(BASMALA.size, words.size)
                    }
                    ayahs.add(Ayah(surah, ayah, words))
                }
            }
            return build(ayahs)
        }

        private fun build(ayahs: List<Ayah>): QuranIndex {
            val bigramLists = HashMap<String, ArrayList<Int>>(90_000)
            val unigramLists = HashMap<String, ArrayList<Int>>(20_000)

            fun add(map: HashMap<String, ArrayList<Int>>, key: String, id: Int) {
                val list = map.getOrPut(key) { ArrayList(2) }
                if (list.isEmpty() || list[list.size - 1] != id) list.add(id)
            }

            // Pairs are formed across Ayah boundaries within a Surah, credited
            // to the Ayah the first word is in, because that is how the words
            // arrive from a reciter: one unbroken run.
            var i = 0
            while (i < ayahs.size) {
                val surah = ayahs[i].surah
                val run = ArrayList<Pair<String, Int>>()
                var j = i
                while (j < ayahs.size && ayahs[j].surah == surah) {
                    for (w in ayahs[j].words) run.add(w to j)
                    j++
                }
                for (k in 0 until run.size) {
                    add(unigramLists, run[k].first, run[k].second)
                    if (k + 1 < run.size) add(bigramLists, run[k].first + " " + run[k + 1].first, run[k].second)
                }
                i = j
            }

            val bigrams = HashMap<String, IntArray>(bigramLists.size)
            for ((k, v) in bigramLists) bigrams[k] = v.toIntArray()

            val rare = HashMap<String, IntArray>()
            for ((k, v) in unigramLists) if (v.size <= RARE_UNIGRAM_DF) rare[k] = v.toIntArray()

            return QuranIndex(ayahs.toTypedArray(), bigrams, rare)
        }
    }
}
