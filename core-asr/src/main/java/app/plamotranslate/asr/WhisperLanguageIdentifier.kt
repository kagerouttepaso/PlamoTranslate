package app.plamotranslate.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.plamotranslate.pipeline.speech.LanguageIdentifier
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * 話された言語の識別（既定の実装。docs/設計/会話翻訳.md「エンジンと出来事」）。Whisper tiny（99言語）を ONNX Runtime で直接動かし、
 * 候補の言語ごとの点数（確率）を返す。sherpa-onnx の識別は一番の言語の名前しか返さず、会話の2つの言語のどちらに近いかを比べられないため。
 * 認識モデルによらず使える。
 *
 * 処理は Whisper の言語の判定と同じ：音声を30秒に足して log-mel（80本）を作り、エンコーダーを1回、デコーダーを
 * 始まりのトークン（SOT）で1手だけ動かして、言語のトークンの logits を候補の中で softmax する。
 * [dir] には `tiny-encoder.int8.onnx`・`tiny-decoder.int8.onnx` を置く（sherpa-onnx の asr-models の `sherpa-onnx-whisper-tiny`）。
 * 言語のトークンと言語のコードの対応は、モデルのメタデータ（`all_language_tokens`・`all_language_codes`）から読む。
 */
class WhisperLanguageIdentifier private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
) : LanguageIdentifier {
    private val sot: Long
    private val tokenOf: Map<String, Int>
    private val layers: Int
    private val textCtx: Int
    private val textState: Int

    init {
        val meta = encoder.metadata.customMetadata
        sot = meta.getValue("sot").toLong()
        val tokens = meta.getValue("all_language_tokens").split(',').map { it.trim().toInt() }
        val codes = meta.getValue("all_language_codes").split(',').map { it.trim() }
        tokenOf = codes.zip(tokens).toMap()
        layers = meta.getValue("n_text_layer").toInt()
        textCtx = meta.getValue("n_text_ctx").toInt()
        textState = meta.getValue("n_text_state").toInt()
    }

    /** デコーダーの自己注意の控え（1手だけなので常に0）。毎回作り直さない。 */
    private val emptyCache = FloatArray(layers * textCtx * textState)

    /** 聞ける言語のコード（Whisper の表記）。 */
    val languages: Set<String> get() = tokenOf.keys

    /**
     * 候補の言語ごとの確率（合計1）。候補のうち Whisper が知らない言語は除く。知っている候補が無ければ null。
     * 識別の担当と書き起こしの担当（言語が分からないまま書き起こすとき）の両方から呼ばれるので、1件ずつにする。
     */
    @Synchronized
    override fun scores(samples: FloatArray, candidates: Set<String>?): Map<String, Float>? {
        val known = (candidates ?: tokenOf.keys).filter { it in tokenOf }
        if (known.isEmpty() || samples.isEmpty()) return null
        val logits = languageLogits(samples)
        val picked = known.associateWith { logits[tokenOf.getValue(it)] }
        val top = picked.values.max()
        val exps = picked.mapValues { exp((it.value - top).toDouble()) }
        val sum = exps.values.sum()
        return exps.mapValues { (it.value / sum).toFloat() }
    }

    /** 言語のトークンの位置での logits（語彙の全体）。 */
    private fun languageLogits(samples: FloatArray): FloatArray {
        val mel = WhisperMel.compute(samples)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, WhisperMel.N_MELS.toLong(), WhisperMel.FRAMES.toLong())).use { melTensor ->
            encoder.run(mapOf("mel" to melTensor)).use { enc ->
                val crossK = enc.get("n_layer_cross_k").get() as OnnxTensor
                val crossV = enc.get("n_layer_cross_v").get() as OnnxTensor
                val cacheShape = longArrayOf(layers.toLong(), 1, textCtx.toLong(), textState.toLong())
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sot)), longArrayOf(1, 1)).use { tokens ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(emptyCache), cacheShape).use { selfK ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(emptyCache), cacheShape).use { selfV ->
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1)).use { offset ->
                                val inputs = mapOf(
                                    "tokens" to tokens,
                                    "in_n_layer_self_k_cache" to selfK,
                                    "in_n_layer_self_v_cache" to selfV,
                                    "n_layer_cross_k" to crossK,
                                    "n_layer_cross_v" to crossV,
                                    "offset" to offset,
                                )
                                decoder.run(inputs, setOf("logits")).use { dec ->
                                    val out = (dec.get("logits").get() as OnnxTensor).floatBuffer
                                    return FloatArray(out.remaining()).also { out.get(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        decoder.close()
        encoder.close()
    }

    companion object {
        const val DIR = "whisper-tiny"
        val FILES = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")

        fun missingFiles(dir: File): List<String> = FILES.map { File(dir, it) }.filterNot { it.canRead() }.map { it.path }

        fun load(dir: File, threads: Int = 2): WhisperLanguageIdentifier {
            val env = OrtEnvironment.getEnvironment()
            fun session(name: String) = env.createSession(
                File(dir, name).path,
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setInterOpNumThreads(1)
                },
            )
            return WhisperLanguageIdentifier(env, session(FILES[0]), session(FILES[1]))
        }
    }
}

/**
 * Whisper の log-mel 特徴量（openai/whisper の `log_mel_spectrogram` と同じ計算）。窓 400（ハン窓）、ずらし 160、
 * 両端を反転で足す（center）、メルは Slaney の80本。音声の後ろに30秒の無音を足して最初の3000フレームを取り、
 * log10 を「最大 − 8」で下を切って (x + 4) / 4 にする。無音のフレームは値が決まっているので計算しない。
 */
internal object WhisperMel {
    const val N_MELS = 80
    const val FRAMES = 3000
    private const val N_FFT = 400
    private const val HOP = 160
    private const val BINS = N_FFT / 2 + 1
    private const val SAMPLE_RATE = 16_000

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val cosTable = FloatArray(BINS * N_FFT) { cos(2 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() }
    private val sinTable = FloatArray(BINS * N_FFT) { sin(2 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() }
    private val filters: Array<FloatArray> = slaneyFilters()

    fun compute(audio: FloatArray): FloatArray {
        // 30秒の無音を後ろに足した音声の、最初の3000フレーム。音声の入るフレームだけを計算する
        val total = audio.size + FRAMES * HOP
        val pad = N_FFT / 2
        fun sample(i: Int): Float {
            // 両端の反転（torch.stft の center、reflect）
            val j = when {
                i < 0 -> -i
                i >= total -> 2 * (total - 1) - i
                else -> i
            }
            return if (j < audio.size) audio[j] else 0f
        }
        val active = minOf(FRAMES, (audio.size + pad) / HOP + 1)
        val logMel = FloatArray(N_MELS * FRAMES)
        val frame = FloatArray(N_FFT)
        val power = FloatArray(BINS)
        var top = Float.NEGATIVE_INFINITY
        for (t in 0 until active) {
            val start = t * HOP - pad
            for (n in 0 until N_FFT) frame[n] = sample(start + n) * window[n]
            for (k in 0 until BINS) {
                var re = 0f
                var im = 0f
                val base = k * N_FFT
                for (n in 0 until N_FFT) {
                    re += frame[n] * cosTable[base + n]
                    im -= frame[n] * sinTable[base + n]
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val f = filters[m]
                var e = 0f
                for (k in 0 until BINS) e += f[k] * power[k]
                val v = log10(max(e, 1e-10f))
                logMel[m * FRAMES + t] = v
                if (v > top) top = v
            }
        }
        val silent = -10f
        top = max(top, silent)
        val floor = top - 8f
        for (m in 0 until N_MELS) {
            for (t in 0 until FRAMES) {
                val v = if (t < active) logMel[m * FRAMES + t] else silent
                logMel[m * FRAMES + t] = (max(v, floor) + 4f) / 4f
            }
        }
        return logMel
    }

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=80)（htk=False、norm="slaney"）。 */
    private fun slaneyFilters(): Array<FloatArray> {
        fun hzToMel(f: Double): Double = if (f < 1000.0) f * 3.0 / 200.0 else 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0)
        fun melToHz(m: Double): Double = if (m < 15.0) m * 200.0 / 3.0 else 1000.0 * exp((ln(6.4) / 27.0) * (m - 15.0))
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val hz = DoubleArray(N_MELS + 2) { melToHz(maxMel * it / (N_MELS + 1)) }
        val freqs = DoubleArray(BINS) { it * SAMPLE_RATE.toDouble() / N_FFT }
        return Array(N_MELS) { i ->
            val enorm = 2.0 / (hz[i + 2] - hz[i])
            FloatArray(BINS) { k ->
                val lower = (freqs[k] - hz[i]) / (hz[i + 1] - hz[i])
                val upper = (hz[i + 2] - freqs[k]) / (hz[i + 2] - hz[i + 1])
                (max(0.0, minOf(lower, upper)) * enorm).toFloat()
            }
        }
    }
}
