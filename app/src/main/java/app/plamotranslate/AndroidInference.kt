package app.plamotranslate

import android.content.Context
import android.os.Build
import android.system.Os
import app.plamotranslate.pipeline.diagnostics.InferencePolicy
import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import java.io.File

/** 翻訳と書き起こしで共有する OpenCL の準備。プロセスで最初に読み込むモデルにも同じ設定を渡す。 */
internal fun androidInferenceTarget(app: Context, preferences: TranslationPreferences): InferenceTarget {
    val soc = Build.SOC_MANUFACTURER
    val adreno = soc.equals("QTI", true) || soc.contains("Qualcomm", true) || Build.HARDWARE.equals("qcom", true)
    val target = InferencePolicy.target(adreno, preferences.cpuOnly, true)
    if (target.useGpu) {
        val cache = File(app.cacheDir, "cl-cache").apply { mkdirs() }
        Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache.path, true)
    }
    return target
}
