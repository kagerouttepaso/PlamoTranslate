package app.plamotranslate.asr

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.conversation.ConversationSession
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.speech.IdentifiedRecognizer
import app.plamotranslate.pipeline.speech.RecognitionJob
import app.plamotranslate.pipeline.speech.RecognitionLane
import app.plamotranslate.pipeline.speech.RecognitionOutput
import app.plamotranslate.pipeline.speech.RecognitionQueue
import app.plamotranslate.pipeline.speech.RecognitionRunner
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechSegmenter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 会話の録音を PC で流し直し、端末と同じ区切りの判断（[SpeechSegmenter]）を再現する（docs/手順/開発環境.md「会話の録音を流し直す」）。
 * VAD（Silero）・言語の識別（Whisper tiny）・書き起こし（Qwen3-ASR）は端末と同じ実物を使い、時間だけを模擬する：
 * 音声は録音の速さで流し、仕事の処理時間は端末の記録から測った式（[DeviceTiming]）で見積もる。PC の速さに左右されず、同じ録音からは同じ結果になる。
 *
 * 環境変数で動かす（どれかが無ければ飛ばす）：
 * - PLAMO_REPLAY_WAV：会話の録音（端末の `files/asr-rec/conv_*.wav`）
 * - PLAMO_REPLAY_ASR：モデルの置き場（`silero_vad.onnx`・`qwen3-asr/`・`whisper-tiny/`。端末の `files/models/asr/` と同じ形）
 * - PLAMO_REPLAY_OUT：結果を書くファイル（カードの一覧と区切りの経過）
 * - PLAMO_REPLAY_CUTS：手で区切った位置（秒、カンマ区切り。任意）
 * - PLAMO_REPLAY_PARTNER：相手の言語（auto か言語のコード。既定は auto）
 * - PLAMO_SHERPA_LIB：sherpa-onnx の Windows 版の JNI の lib（build.gradle.kts が読む）
 */
class ConversationReplayTest {
    /** 端末（REDMAGIC 11 Pro、Qwen3-ASR 6スレッド・Whisper tiny 2スレッド）で測った処理時間（2026-11-15 の会話の記録の回帰）。 */
    private object DeviceTiming {
        fun millis(job: RecognitionJob): Long {
            val seconds = job.samples.size / SAMPLE_RATE.toDouble()
            return when (job) {
                is RecognitionJob.Language -> if (job.lane == RecognitionLane.IDENTIFY) 330 + 21 * seconds else 256 + 203 * seconds
                is RecognitionJob.Transcribe -> 256 + 203 * seconds
                // 継ぎ目探しは識別の繰り返し（記録の平均）
                is RecognitionJob.Seam -> 1700.0
            }.toLong()
        }
    }

    /**
     * 採点の物差し：録音の全体を見通して、0.25秒ごとに「どの言語の声か」を決める（`tools/score-speaker.py` が読む）。
     * 2秒の区間を0.5秒ずつずらして Whisper tiny で点数を出し、0.25秒ごとに重なる区間の点数を足して一番の言語にする。
     * 声の無いところ（端末と同じ Silero VAD が話していないとするところ）は `-` にする。
     * 話し中の判定と違い、後ろの音声も使えて時間の制約も無いので、区切りの判断より確かな物差しになる（ただし正解そのものではない）。
     * PLAMO_REFERENCE_OUT（書く先）・PLAMO_REPLAY_WAV・PLAMO_REPLAY_ASR・PLAMO_SHERPA_LIB・PLAMO_REFERENCE_LANGS（候補。既定 ja,en）で動かす。
     */
    @Test
    fun reference() {
        val wav = System.getenv("PLAMO_REPLAY_WAV")?.let(::File)
        val asr = System.getenv("PLAMO_REPLAY_ASR")?.let(::File)
        val out = System.getenv("PLAMO_REFERENCE_OUT")?.let(::File)
        assumeTrue(wav != null && asr != null && out != null && System.getenv("PLAMO_SHERPA_LIB") != null)
        val langs = (System.getenv("PLAMO_REFERENCE_LANGS") ?: "ja,en").split(',').map { it.trim() }.toSet()
        val audio = WavFile.parse(wav!!.readBytes())
        val lid = WhisperLanguageIdentifier.load(File(asr, WhisperLanguageIdentifier.DIR), 4)
        val slot = SAMPLE_RATE / 4
        val slots = audio.size / slot
        val voiced = BooleanArray(slots)
        SileroVoiceActivity.load(File(asr, "silero_vad.onnx"), SegmentationProfile.CONVERSATION.phraseSilenceSeconds).use { vad ->
            var pos = 0
            while (pos < audio.size) {
                val end = minOf(audio.size, pos + 512)
                if (vad.accept(audio.copyOfRange(pos, end)).speaking) {
                    for (i in pos / slot..minOf(slots - 1, (end - 1) / slot)) voiced[i] = true
                }
                pos = end
            }
        }
        val totals = Array(slots) { HashMap<String, Double>() }
        val window = SAMPLE_RATE * 2
        val step = SAMPLE_RATE / 2
        var from = 0
        while (from + window <= audio.size) {
            val scores = lid.scores(audio.copyOfRange(from, from + window), langs).orEmpty()
            for (i in from / slot until (from + window) / slot) {
                scores.forEach { (lang, p) -> totals[i][lang] = (totals[i][lang] ?: 0.0) + p }
            }
            from += step
        }
        lid.close()
        val lines = StringBuilder()
        var runStart = 0
        fun label(i: Int) = if (!voiced[i]) "-" else totals[i].maxByOrNull { it.value }?.key ?: "-"
        for (i in 1..slots) {
            if (i == slots || label(i) != label(runStart)) {
                lines.append("%.2f\t%.2f\t%s\n".format(runStart * 0.25, i * 0.25, label(runStart)))
                runStart = i
            }
        }
        out!!.writeText(lines.toString(), Charsets.UTF_8)
        println("REFERENCE slots=$slots -> $out")
    }

    @Test
    fun replay() {
        val wav = System.getenv("PLAMO_REPLAY_WAV")?.let(::File)
        val asr = System.getenv("PLAMO_REPLAY_ASR")?.let(::File)
        val out = System.getenv("PLAMO_REPLAY_OUT")?.let(::File)
        assumeTrue(wav != null && asr != null && out != null && System.getenv("PLAMO_SHERPA_LIB") != null)
        val cuts = System.getenv("PLAMO_REPLAY_CUTS")?.split(',')?.filter { it.isNotBlank() }
            ?.map { (it.trim().toDouble() * SAMPLE_RATE).toLong() }?.sorted().orEmpty().toMutableList()
        val partner = System.getenv("PLAMO_REPLAY_PARTNER")?.takeIf { it != "auto" }
            ?.let { code -> Language.entries.first { it.code == code } }

        val audio = WavFile.parse(wav!!.readBytes())
        val profile = SegmentationProfile.CONVERSATION
        val recognizer = IdentifiedRecognizer(
            WhisperLanguageIdentifier.load(File(asr, WhisperLanguageIdentifier.DIR), 4),
            Qwen3Recognizer.load(File(asr, Qwen3Recognizer.DIR), 8),
        )
        val vad = SileroVoiceActivity.load(File(asr, "silero_vad.onnx"), profile.phraseSilenceSeconds)
        val runner = RecognitionRunner(recognizer)
        val queue = RecognitionQueue()
        val session = ConversationSession(
            mode = partner?.let(::PartnerMode) ?: PartnerMode.AUTO,
            self = Language.JA,
            heard = app.plamotranslate.pipeline.speech.SpeechModel.QWEN3_ASR.languages,
        )

        var now = 0L // 模擬の時刻（サンプル数）
        val log = StringBuilder()
        fun stamp() = "%7.2f".format(now / SAMPLE_RATE.toDouble())
        val cards = mutableListOf<String>()
        lateinit var segmenter: SpeechSegmenter
        segmenter = SpeechSegmenter(
            vad,
            identifies = true,
            profile = profile,
            heavy = true,
            emit = { event ->
                if (event is SpeechEvent.Final) {
                    val u = event.utterance
                    cards += "%6.2f-%6.2f [%s] %s".format(u.startMs / 1000.0, (u.startMs + u.durationMs) / 1000.0, u.lang, u.text)
                }
                session.handle(ConversationSession.Event.Speech(event))
                segmenter.languages = session.speechLanguages
            },
            submit = queue::submit,
            trace = { message -> log.append(stamp()).append(' ').append(message()).append('\n') },
            clock = { now * 1_000_000_000L / SAMPLE_RATE },
        )
        segmenter.languages = session.speechLanguages

        // 担当ごとに、走っている仕事と終わる時刻
        val running = HashMap<RecognitionLane, Triple<RecognitionJob, RecognitionOutput, Long>>()
        fun startJobs() {
            for (lane in RecognitionLane.entries) {
                if (lane in running) continue
                val job = queue.take(lane) ?: continue
                val output = runner.run(job)
                running[lane] = Triple(job, output, now + DeviceTiming.millis(job) * SAMPLE_RATE / 1000)
            }
        }
        // 端末と同じく、閉じたカードの要らない仕事は捨て、空の結果を返す（SpeechPipeline）
        fun dropStale() {
            while (true) {
                val stale = queue.dropStale(segmenter.openCardId)
                if (stale.isEmpty()) return
                stale.forEach { segmenter.onResult(it.id, RecognitionRunner.empty(it)) }
            }
        }
        fun deliverDone() {
            while (true) {
                val (lane, done) = running.entries.filter { it.value.third <= now }.minByOrNull { it.value.third }?.toPair() ?: return
                running.remove(lane)
                segmenter.onResult(done.first.id, done.second)
                dropStale()
                startJobs()
            }
        }

        val chunk = 512
        var pos = 0
        while (pos < audio.size) {
            val end = minOf(audio.size, pos + chunk)
            now = end.toLong()
            deliverDone()
            while (cuts.isNotEmpty() && cuts.first() <= now) {
                cuts.removeAt(0)
                segmenter.cut()
            }
            segmenter.accept(audio.copyOfRange(pos, end))
            dropStale()
            startJobs()
            pos = end
        }
        // 録音の終わり：止めて、残りの仕事を終える
        segmenter.flush()
        startJobs()
        while (running.isNotEmpty()) {
            now = running.values.minOf { it.third }
            deliverDone()
            startJobs()
        }
        segmenter.close()
        recognizer.close()

        out!!.writeText(
            "# カード\n" + cards.joinToString("\n") + "\n\n# 区切りの経過\n" + log,
            Charsets.UTF_8,
        )
        println("REPLAY cards=${cards.size} -> $out")
    }
}
