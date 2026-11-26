package app.plamotranslate

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.plamotranslate.asr.AsrTuning
import app.plamotranslate.asr.SpeechEngines
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.present
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.llm.QwenAudioRecognizer
import app.plamotranslate.pipeline.speech.SpeechTrialAudio
import app.plamotranslate.log.Logs
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.pipeline.diagnostics.InferenceRole
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.speech.TranscriptionPreference
import app.plamotranslate.pipeline.speech.TranscriptionTuner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object AndroidQwen {
    private val preparing = Mutex()

    suspend fun load(app: Context, threads: Int): QwenAudioRecognizer = preparing.withLock {
        val translation = TranslationPreferences(app)
        val recognition = RecognitionPreferences(app)
        val target = androidInferenceTarget(app, translation)
        val preference = recognition.transcriptionPreference
        val variant = translation.cpuOptions.variant
        val role = InferenceRole.Transcription
        val processors = Runtime.getRuntime().availableProcessors()
        val start = SystemClock.elapsedRealtime()
        val gpu = target.useGpu && preference != TranscriptionPreference.CPU && GpuGuard.allowGpu(app, role)
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        val driver = if (gpu) File("/vendor/lib64/libOpenCL.so").let {
            "OpenCL/${Build.VERSION.INCREMENTAL}/${it.length()}/${it.lastModified()}"
        } else "CPU"
        val environment = TranscriptionTuner.Environment(
            SharedModelCatalog.qwenWeights.sha256, SharedModelCatalog.qwenProjector.sha256,
            "${installed.longVersionCode}/${installed.lastUpdateTime}/$variant", Build.FINGERPRINT,
            "${Build.SOC_MODEL}/${Build.SUPPORTED_ABIS.joinToString()}/$processors", driver, SpeechTrialAudio.version,
        )
        val (engine, choice) = QwenAudioRecognizer.prepare(ModelCatalog.modelsDir(app), app.applicationInfo.nativeLibraryDir,
            variant, target.copy(requestGpu = gpu), threads, preference, environment, recognition,
            {
                GpuGuard.beginGpuLoad(app, role)
                Logs.i("書き起こしの OpenCL の準備を開始")
                true
            }, { failure -> GpuGuard.endGpuLoad(app, role, failure) })
        val c = choice.candidate
        val reason = GpuGuard.disabledNote(app, role)
            ?: if (!gpu && preference == TranscriptionPreference.GPU) "GPU を使用できません" else choice.reason
        recognition.lastTarget = "${if (c.gpu) "OpenCL" else "CPU"}（${c.batch}/${c.batch}/${c.generation} スレッド、試走 ${choice.medianNanos / 1_000_000} ms）：$reason"
        Logs.i("書き起こしの準備 ${SystemClock.elapsedRealtime() - start} ms：${recognition.lastTarget}")
        engine
    }
}

/** 会話とテキストの両方から呼ぶ。画面を閉じて準備が取り消されたら、引き渡す前の認識器を解放する。 */
internal suspend fun loadSpeechEngine(
    app: Context, model: SpeechModel, profile: SegmentationProfile, tuning: AsrTuning, retain: Int = 0,
): SpeechEngine {
    var loaded: SpeechEngine? = null
    try {
        return withContext(Dispatchers.Default) {
            val missing = ModelBundle.of(model).missing(app, verify = true)
            require(missing.isEmpty() && SharedModelCatalog.sileroVad.present(app, verify = false)) {
                if (missing.any { it in SharedModelCatalog.qwenGguf }) {
                    "Qwen3-ASR のモデル更新が必要です。設定の「音声認識」で取得量を確認してください。"
                } else "音声認識のモデルがありません。モデルの準備を確認してください。"
            }
            SpeechEngines.load(model, ModelCatalog.asrDir(app), ProcessCompute, profile, tuning, retain) {
                AndroidQwen.load(app, tuning.transcribeThreads)
            }.also { loaded = it }
        }
    } catch (error: Throwable) {
        loaded?.let { engine ->
            engine.close()
            withContext(NonCancellable) { engine.awaitClosed() }
        }
        throw error
    }
}
