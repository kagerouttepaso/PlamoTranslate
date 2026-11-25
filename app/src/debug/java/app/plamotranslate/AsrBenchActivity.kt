package app.plamotranslate

import app.plamotranslate.llm.ScheduledSpeech
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.widget.ScrollView
import android.widget.TextView
import app.plamotranslate.asr.SenseVoiceRecognizer
import app.plamotranslate.asr.WavFile
import app.plamotranslate.asr.WhisperLanguageIdentifier
import app.plamotranslate.log.Logs
import app.plamotranslate.models.ModelCatalog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音声認識の比べ（デバッグ版だけ。docs/判断/2026-11-17_音声認識モデルを設定で選び言語の識別を独立した段にする.md）。
 *
 * 外部ファイル置き場の `bench/asr/` の WAV（16kHz・モノラル）を、SenseVoice・Whisper tiny の言語の識別・Qwen3-ASR
 * （言語の指定なし／識別した言語を指定）で1つずつ処理し、時間と結果を logcat と `bench/asr-result.tsv` に書く。
 * Qwen3-ASR は `models/asr/qwen3-asr-gguf/`、Whisper tiny は `models/asr/whisper-tiny/` に置く。
 *
 * adb shell am start -S -n app.plamotranslate/.AsrBenchActivity
 */
class AsrBenchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 12f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(view) })
        scope.launch {
            val report = runCatching { withContext(Dispatchers.Default) { run() } }.getOrElse { "失敗: $it" }
            view.text = report
            log("終わり")
        }
    }

    private suspend fun run(): String {
        val out = StringBuilder()
        fun line(s: String) {
            out.appendLine(s)
            log(s)
        }
        val asrDir = ModelCatalog.asrDir(this)
        val wavs = File(getExternalFilesDir(null), "bench/asr").listFiles { f -> f.extension == "wav" }.orEmpty().sortedBy { it.name }
        require(wavs.isNotEmpty()) { "bench/asr に WAV がありません" }
        val audio = wavs.associate { it.nameWithoutExtension to WavFile.read(it) }

        val pss0 = Debug.getPss()
        val (sv, svMs) = timed { ScheduledSpeech.senseVoice(0) { SenseVoiceRecognizer.load(asrDir, it) } }
        val (lid, lidMs) = timed { ScheduledSpeech.identifier(0) { WhisperLanguageIdentifier.load(File(asrDir, WhisperLanguageIdentifier.DIR), it) } }
        val pss1 = Debug.getPss()
        val (qwen, qwenMs) = timed { AndroidQwen.load(this, 4) }
        val pss2 = Debug.getPss()
        line("読み込み: SenseVoice ${svMs}ms, Whisper識別 ${lidMs}ms, Qwen3 ${qwenMs}ms")
        line("PSS: 始め ${pss0 / 1024}MB, SenseVoice+Whisper後 ${pss1 / 1024}MB, Qwen3後 ${pss2 / 1024}MB")

        // 1回目は遅いので捨てる
        audio.values.first().let { sv.recognize(it); lid.scores(it, null); qwen.recognize(it) }

        line("音声\t秒\tSV ms\tSV言語\tSV文\tWhisper ms\tWhisper言語\tQwen3 ms\tQwen3文\tQwen3指定 ms\tQwen3指定文")
        for ((name, samples) in audio) {
            val secs = samples.size / 16_000f
            val (s, sMs) = timed { sv.recognize(samples) }
            val (lang, lMs) = timed { lid.scores(samples, null)?.maxByOrNull { it.value }?.key }
            val (q, qMs) = timed { qwen.recognizeAs(samples, language = null) }
            val (qh, qhMs) = timed { qwen.recognizeAs(samples, language = lang) }
            line("$name\t${"%.1f".format(secs)}\t$sMs\t${s.lang}\t${s.text}\t$lMs\t$lang\t$qMs\t${q.text}\t$qhMs\t${qh.text}")
        }
        qwen.close()

        // スレッド数の違い（時間だけ）
        for (threads in listOf(3, 6)) {
            val q = AndroidQwen.load(this, threads)
            q.recognize(audio.values.first())
            val times = audio.map { (name, samples) -> "$name ${timed { q.recognize(samples) }.second}ms" }
            line("Qwen3 ${threads}スレッド: ${times.joinToString(", ")}")
            q.close()
        }
        sv.close()
        lid.close()
        File(getExternalFilesDir(null), "bench/asr-result.tsv").writeText(out.toString())
        return out.toString()
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun log(message: String) = Logs.i("音声認識の比べ: $message")
}
