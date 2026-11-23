package app.plamotranslate

import app.plamotranslate.pipeline.diagnostics.InferenceRole
import app.plamotranslate.llm.LlamaRuntime
import app.plamotranslate.pipeline.diagnostics.InferencePolicy
import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationRequest
import app.plamotranslate.log.Logs
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.models.file
import app.plamotranslate.pipeline.translation.EngineLoader
import app.plamotranslate.pipeline.translation.SingleRequest
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * llama.cpp の翻訳エンジンを読み込む（中核の [EngineLoader] の実装。[EngineHolder] が使う）。
 * OpenCL のカーネルのキャッシュの場所、GPU を使うかの判定（[GpuGuard] を含む）、CPU の詳細設定、初回の準備の推論を持つ。
 */
internal class LlamaEngineLoader(private val context: () -> Context) : EngineLoader<TranslationModel, TranslationEngine> {
    override fun missing(model: TranslationModel): String? = model.file.file(context()).takeIf { !it.exists() }?.path

    override suspend fun load(model: TranslationModel, parallel: Int): TranslationEngine {
        val app = context()
        // OpenCLのカーネルのコンパイル結果を残す場所。既定の一時フォルダーはアプリから書けない
        val clCache = File(app.cacheDir, "cl-cache").apply { mkdirs() }
        android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", clCache.path, true)
        val preferences = TranslationPreferences(app)
        val candidate = InferencePolicy.target(hasAdreno(), preferences.cpuOnly, true)
        return LlamaRuntime.process.prepare(candidate.useGpu) {
            val role = InferenceRole.Translation
            val target = candidate.copy(requestGpu = candidate.useGpu && GpuGuard.allowGpu(app, role))
            if (target.useGpu) GpuGuard.beginGpuLoad(app, role)
            try {
                TranslationEngine.load(model.file.file(app), app.applicationInfo.nativeLibraryDir, target, cpu = preferences.cpuOptions).also {
                    it.parallel = parallel
                    warmUp(it)
                }
            } finally {
                if (target.useGpu) GpuGuard.endGpuLoad(app, role)
            }
        }
    }

    override fun usesGpu(engine: TranslationEngine) = engine.usesGpu

    override fun setParallel(engine: TranslationEngine, parallel: Int) {
        engine.parallel = parallel
    }

    override suspend fun close(engine: TranslationEngine) = engine.close()

    /**
     * GPU（OpenCL）を使うか。llama.cppのOpenCLはQualcomm（Snapdragon）のAdrenoに合わせて作られているので、
     * Qualcommのチップの端末だけGPUを使い、ほかはCPUで動かす（2026-11-06、APKを配布するため）。
     * GPUでの読み込みに失敗したときは [TranslationEngine.load] がCPUでやり直す。失敗が返らずにプロセスごと終わったときは、
     * 次の起動で [GpuGuard] がCPUに切り替える。
     * 設定の「性能」で「CPUだけで動かす」を選ぶと、Snapdragonの端末でもCPUで動く（CPUの速さを確かめるため）。
     */
    private fun hasAdreno(): Boolean {
        val soc = android.os.Build.SOC_MANUFACTURER
        return soc.equals("QTI", ignoreCase = true) || soc.contains("Qualcomm", ignoreCase = true) ||
            android.os.Build.HARDWARE.equals("qcom", ignoreCase = true)
    }

    /**
     * GPU（OpenCL）は初回の推論でカーネルを準備するため4秒ほどかかる（2026-11-05実測）。
     * 読み込み直後に短い文を1回訳しておき、作者の最初の翻訳を待たせない。
     */
    private suspend fun warmUp(engine: TranslationEngine) {
        try {
            engine.run(1, SingleRequest(TranslationRequest("안녕하세요", Language.KO, Language.JA)), maxTokens = 8)
        } catch (cancelled: CancellationException) {
            engine.close()
            throw cancelled
        } catch (error: Exception) {
            Logs.w("起動診断: 準備用の推論が失敗しました", error)
        }
    }
}
