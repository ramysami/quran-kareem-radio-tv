package com.ramy.quranradiotv.recognition

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns Whisper's token ids back into text.
 *
 * Whisper's vocabulary is GPT-2 style byte-level BPE: every token is a string
 * over a 256-symbol alphabet in which each symbol stands for one byte, chosen
 * so that they are all printable. Decoding is therefore: look each id up,
 * map each symbol of the result back to its byte, and read the bytes as
 * UTF-8. Arabic comes out of that as several tokens per word, which is why
 * decoding has to see the whole sequence before the text means anything.
 *
 * The special tokens — language, task, timestamps — live in `added_tokens.json`
 * and are looked up by name, so nothing here assumes their numbers.
 */
class WhisperTokenizer(vocabJson: String, addedTokensJson: String) {

    private val tokens: Array<String?>
    private val special: Map<String, Int>

    val endOfText: Int
    val startOfTranscript: Int
    val arabic: Int
    val transcribe: Int
    val noTimestamps: Int

    /** Ids from here up are special tokens or timestamps, never words. */
    val firstSpecial: Int

    init {
        val vocab = JSONObject(vocabJson)
        val added = JSONObject(addedTokensJson)
        var maxId = -1
        for (key in vocab.keys()) maxId = maxOf(maxId, vocab.getInt(key))
        for (key in added.keys()) maxId = maxOf(maxId, added.getInt(key))
        tokens = arrayOfNulls(maxId + 1)
        for (key in vocab.keys()) tokens[vocab.getInt(key)] = key

        val sp = HashMap<String, Int>()
        for (key in added.keys()) sp[key] = added.getInt(key)
        special = sp

        endOfText = require("<|endoftext|>")
        startOfTranscript = require("<|startoftranscript|>")
        arabic = require("<|ar|>")
        transcribe = require("<|transcribe|>")
        noTimestamps = require("<|notimestamps|>")
        firstSpecial = vocab.length()
    }

    private fun require(name: String): Int =
        special[name] ?: throw IllegalArgumentException("tokenizer lacks $name")

    /** The prompt that asks for an Arabic transcript without timestamps. */
    val prompt: LongArray
        get() = longArrayOf(startOfTranscript.toLong(), arabic.toLong(), transcribe.toLong(), noTimestamps.toLong())

    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            if (id >= firstSpecial) continue
            val token = tokens.getOrNull(id) ?: continue
            for (ch in token) {
                val b = SYMBOL_TO_BYTE[ch] ?: continue
                bytes.write(b)
            }
        }
        return bytes.toString("UTF-8")
    }

    companion object {
        /**
         * GPT-2's bytes_to_unicode, inverted. Printable ASCII and Latin-1
         * letters stand for themselves; the remaining bytes are mapped in
         * order onto code points from 256 upwards.
         */
        private val SYMBOL_TO_BYTE: Map<Char, Int> = HashMap<Char, Int>().apply {
            val direct = (33..126) + (161..172) + (174..255)
            for (b in direct) put(b.toChar(), b)
            var next = 256
            for (b in 0..255) if (b !in direct) put(next++.toChar(), b)
        }

        fun load(dir: File): WhisperTokenizer = WhisperTokenizer(
            File(dir, "vocab.json").readText(),
            File(dir, "added_tokens.json").readText()
        )
    }
}
