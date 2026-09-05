package com.ramy.quranradiotv.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Whisper, run through ONNX Runtime: thirty seconds of audio in, Arabic text
 * out.
 *
 * Three graphs, as Optimum exports them: the encoder, which reads the
 * spectrogram once; the decoder for the first step, which takes the whole
 * prompt and hands back its attention caches; and the decoder-with-past for
 * every step after, which takes one token and the caches and hands back the
 * caches grown by one. Cache tensors are passed by name — each `present.*`
 * output becomes the `past_key_values.*` input with the same tail — so the
 * code does not care how many layers the model has.
 *
 * Decoding is greedy. Special tokens are never chosen except the end marker,
 * which is itself forbidden as a first choice, and a run that starts repeating
 * itself is cut off: a small model on a long stretch of silence can otherwise
 * chant the same phrase to the token limit.
 */
class WhisperModel(
    modelDir: File,
    melFilters: InputStream,
    threads: Int = 2,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val features = WhisperFeatures(melFilters)
    private val tokenizer = WhisperTokenizer.load(modelDir)

    private val encoder: OrtSession
    private val decoder: OrtSession
    private val decoderWithPast: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
        }
        encoder = env.createSession(File(modelDir, ENCODER).path, options)
        decoder = env.createSession(File(modelDir, DECODER).path, options)
        decoderWithPast = env.createSession(File(modelDir, DECODER_WITH_PAST).path, options)
    }

    /** [samples] are 16 kHz mono in [-1, 1]; anything beyond thirty seconds is dropped. */
    fun transcribe(samples: FloatArray): String {
        val mel = features.logMel(samples)
        val melTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(mel),
            longArrayOf(1, WhisperFeatures.N_MELS.toLong(), WhisperFeatures.N_FRAMES.toLong())
        )
        val encoded = melTensor.use { encoder.run(mapOf(encoder.inputNames.first() to it)) }
        return encoded.use { decode(it.get(0) as OnnxTensor) }
    }

    private fun decode(encoderOut: OnnxTensor): String {
        val out = ArrayList<Int>()
        val prompt = tokenizer.prompt

        // First step: the whole prompt; yields logits and every cache. Its
        // encoder caches are inputs to every later step, so this result has to
        // stay open until decoding is over.
        val promptTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(prompt), longArrayOf(1, prompt.size.toLong()))
        val first: OrtSession.Result = promptTensor.use {
            decoder.run(mapOf("input_ids" to it, "encoder_hidden_states" to encoderOut))
        }
        var previous: OrtSession.Result? = null
        var cache: Map<String, OnnxTensorLike> = cacheFrom(first)
        var next = pick(first, isFirst = true)

        try {
            while (next != tokenizer.endOfText && out.size < MAX_TOKENS) {
                out.add(next)
                if (isLooping(out)) break

                val tokenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(next.toLong())), longArrayOf(1, 1))
                val inputs = HashMap<String, OnnxTensorLike>(cache.size + 2)
                inputs["input_ids"] = tokenTensor
                for (name in decoderWithPast.inputNames) {
                    if (name.startsWith("past_key_values.")) {
                        inputs[name] = cache[name.removePrefix("past_key_values.")]
                            ?: throw IllegalStateException("no cache for $name")
                    } else if (name == "encoder_hidden_states") {
                        inputs[name] = encoderOut
                    }
                }
                val step = tokenTensor.use { decoderWithPast.run(inputs) }

                // This step's decoder caches replace the last step's; the encoder
                // caches, which the with-past graph does not re-emit, stay as
                // they came from the first step. The last step's result can go
                // now that its tensors have been consumed.
                val grown = HashMap(cache)
                grown.putAll(cacheFrom(step))
                previous?.close()
                previous = step
                cache = grown
                next = pick(step, isFirst = false)
            }
        } finally {
            previous?.close()
            first.close()
        }
        return tokenizer.decode(out)
    }

    /** The `present.*` outputs of a step, keyed by their tail (`0.decoder.key`…). */
    private fun cacheFrom(result: OrtSession.Result): Map<String, OnnxTensorLike> {
        val map = HashMap<String, OnnxTensorLike>()
        for (entry in result) {
            val value = entry.value
            if (entry.key.startsWith("present.") && value is OnnxTensorLike) {
                map[entry.key.removePrefix("present.")] = value
            }
        }
        return map
    }

    /** Greedy choice from the last position's logits, with the suppressions. */
    private fun pick(result: OrtSession.Result, isFirst: Boolean): Int {
        val logits = result.get("logits").get() as OnnxTensor
        val shape = logits.info.shape // [1, seq, vocab]
        val vocab = shape[2].toInt()
        val seq = shape[1].toInt()
        val buf = logits.floatBuffer
        val base = (seq - 1) * vocab

        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            if (i >= tokenizer.firstSpecial && i != tokenizer.endOfText) continue
            if (isFirst && (i == tokenizer.endOfText || i == SPACE_TOKEN)) continue
            val v = buf.get(base + i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    /** True once the last few tokens have been said three times over. */
    private fun isLooping(out: List<Int>): Boolean {
        val n = out.size
        for (period in 1..LOOP_MAX_PERIOD) {
            if (n < period * 3) continue
            var same = true
            for (i in 0 until period * 2) {
                if (out[n - 1 - i] != out[n - 1 - i - period]) { same = false; break }
            }
            if (same) return true
        }
        return false
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        runCatching { decoderWithPast.close() }
    }

    companion object {
        // The uint8 dynamic quantisation: the one whose integer convolution
        // ONNX Runtime's CPU kernels implement, and the export Optimum aims at
        // ARM. A quarter the size of the float model, and faster on a phone.
        const val ENCODER = "encoder_model_uint8.onnx"
        const val DECODER = "decoder_model_uint8.onnx"
        const val DECODER_WITH_PAST = "decoder_with_past_model_uint8.onnx"

        /** The files a complete model consists of, in the order they are fetched. */
        val FILES = listOf(ENCODER, DECODER, DECODER_WITH_PAST, "vocab.json", "added_tokens.json")

        /** Thirty seconds of Arabic recitation runs to a hundred-odd tokens. */
        private const val MAX_TOKENS = 220
        private const val LOOP_MAX_PERIOD = 8

        /** GPT-2's token for a lone space, which Whisper suppresses at the start. */
        private const val SPACE_TOKEN = 220
    }
}
