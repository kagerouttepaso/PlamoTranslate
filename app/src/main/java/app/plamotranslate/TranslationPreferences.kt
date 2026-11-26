package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.models.TranslationModel

/**
 * 翻訳モデルの設定（使うモデル・CPUの詳細）。端末に残し、次に起動したときも同じものを使う。
 * 設定の画面が書き、[EngineHolder] が読み込むときに読む（`docs/判断/2026-11-09_設定の保存は機能ごとのPreferencesにまとめDataStoreへ移さない.md`）。
 */
class TranslationPreferences(context: Context) {
    private val translation = context.getSharedPreferences("translation", Context.MODE_PRIVATE)
    private val cpu = context.getSharedPreferences("cpu", Context.MODE_PRIVATE)

    /** 選んだ翻訳モデル。読めなければ0.8B。ファイルがあるかは見ない（[EngineHolder.selected] が見る）。 */
    var model: TranslationModel
        get() = translation.getString(KEY_MODEL, null)
            ?.let { name -> TranslationModel.entries.firstOrNull { it.name == name } }
            ?: TranslationModel.SMALL
        set(value) = translation.edit().putString(KEY_MODEL, value.name).apply()

    /** CPUの詳細設定（2026-11-08、15T Proで翻訳が極端に遅かったため。docs/設計/翻訳コア.md「性能の設定」）。 */
    val cpuOptions: CpuOptions
        get() {
            val d = CpuOptions()
            return CpuOptions(
                threads = cpu.getInt("threads", d.threads),
                boost = cpu.getBoolean("boost", d.boost),
                spinWait = cpu.getBoolean("spinWait", d.spinWait),
                threadPool = cpu.getBoolean("threadPool", d.threadPool),
                mmap = cpu.getBoolean("mmap", d.mmap),
                variant = cpu.getString("variant", d.variant) ?: "",
            )
        }

    /**
     * 翻訳のストリームが同時に訳すチャンクの数（4機能すべてに効く。docs/設計/翻訳コア.md「複数の文を同時に訳す」）。
     * 読み直さずに効く（[EngineHolder.setParallel]）。
     */
    var parallel: Int
        get() = cpu.getInt("parallel", TranslationEngine.DEFAULT_PARALLEL).coerceIn(1, TranslationEngine.MAX_PARALLEL)
        set(value) = cpu.edit().putInt("parallel", value).apply()

    /** Snapdragonの端末でもGPUを使わず、CPUだけで動かすか（CPUの速さを確かめるため）。 */
    val cpuOnly: Boolean get() = cpu.getBoolean("cpuOnly", false)

    /** 使う GPU バックエンドの指定。既定は自動（Qualcomm は OpenCL、それ以外は CPU）。 */
    var gpuBackend: GpuBackendChoice
        get() = if (cpuOnly) GpuBackendChoice.CPU else GpuBackendChoice.fromKey(cpu.getString(KEY_GPU_BACKEND, null))
        set(value) {
            cpu.edit()
                .putString(KEY_GPU_BACKEND, value.key)
                .putBoolean("cpuOnly", value == GpuBackendChoice.CPU)
                .apply()
        }

    /** GPU バックエンド比較ダイアログを初回自動表示した版（versionCode）。 */
    var gpuProbeShownVersion: Long
        get() = cpu.getLong(KEY_GPU_PROBE_SHOWN, 0L)
        set(value) = cpu.edit().putLong(KEY_GPU_PROBE_SHOWN, value).apply()

    /**
     * CPUの詳細設定を保存する。効かせるには翻訳モデルを読み直す（[EngineHolder.reload]）。
     * CPUバックエンドの版（[CpuOptions.variant]）と「CPUだけ」は、バックエンドのライブラリを読み込むのがアプリの起動ごとに
     * 1回だけなので、アプリを起動し直すと効く。
     */
    fun saveCpu(options: CpuOptions, cpuOnly: Boolean) {
        val editor = cpu.edit()
            .putInt("threads", options.threads)
            .putBoolean("boost", options.boost)
            .putBoolean("spinWait", options.spinWait)
            .putBoolean("threadPool", options.threadPool)
            .putBoolean("mmap", options.mmap)
            .putString("variant", options.variant)
            .putBoolean("cpuOnly", cpuOnly)
        if (cpuOnly) {
            editor.putString(KEY_GPU_BACKEND, GpuBackendChoice.CPU.key)
        } else if (GpuBackendChoice.fromKey(cpu.getString(KEY_GPU_BACKEND, null)) == GpuBackendChoice.CPU) {
            editor.putString(KEY_GPU_BACKEND, GpuBackendChoice.AUTO.key)
        }
        editor.apply()
    }

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_GPU_BACKEND = "gpuBackend"
        const val KEY_GPU_PROBE_SHOWN = "gpuProbeShownVersion"
    }
}
