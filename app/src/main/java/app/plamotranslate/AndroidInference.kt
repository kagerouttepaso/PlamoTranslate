package app.plamotranslate

import android.content.Context
import android.os.Build
import android.system.Os
import app.plamotranslate.pipeline.diagnostics.InferencePolicy
import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import java.io.File

/** 翻訳・書き起こしで使う GPU バックエンドの選び方。起動し直すと効く。 */
enum class GpuBackendChoice(val key: String, val label: String) {
    AUTO("auto", "自動"),
    VULKAN("vulkan", "Vulkan"),
    VULKAN_NO_COOPMAT("vulkan_no_coopmat", "Vulkan（coopmat無効）"),
    OPENCL("opencl", "OpenCL"),
    CPU("cpu", "CPUのみ");

    companion object {
        fun fromKey(key: String?): GpuBackendChoice = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

internal fun isQualcommSoc(): Boolean {
    val soc = Build.SOC_MANUFACTURER
    return soc.equals("QTI", true) || soc.contains("Qualcomm", true) || Build.HARDWARE.equals("qcom", true)
}

/**
 * 選んだバックエンドに合わせてネイティブ用の環境変数を設定し、GPU を試すべきかを返す。
 * バックエンドのライブラリはプロセスで1回だけ読み込むため、初回読み込みの前に呼ぶ。
 */
internal fun applyBackendEnv(app: Context, choice: GpuBackendChoice): Boolean {
    val effective = if (choice == GpuBackendChoice.AUTO) {
        if (isQualcommSoc()) GpuBackendChoice.OPENCL else GpuBackendChoice.CPU
    } else choice
    when (effective) {
        GpuBackendChoice.VULKAN -> {
            Os.setenv("PLAMO_GPU_BACKEND", "vulkan", true)
            Os.unsetenv("GGML_VK_DISABLE_COOPMAT")
            return true
        }
        GpuBackendChoice.VULKAN_NO_COOPMAT -> {
            Os.setenv("PLAMO_GPU_BACKEND", "vulkan", true)
            Os.setenv("GGML_VK_DISABLE_COOPMAT", "1", true)
            return true
        }
        GpuBackendChoice.OPENCL -> {
            Os.setenv("PLAMO_GPU_BACKEND", "opencl", true)
            Os.unsetenv("GGML_VK_DISABLE_COOPMAT")
            val cache = File(app.cacheDir, "cl-cache").apply { mkdirs() }
            Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache.path, true)
            return true
        }
        GpuBackendChoice.CPU, GpuBackendChoice.AUTO -> {
            Os.unsetenv("PLAMO_GPU_BACKEND")
            Os.unsetenv("GGML_VK_DISABLE_COOPMAT")
            return false
        }
    }
}

/** 翻訳と書き起こしで共有する GPU の準備。プロセスで最初に読み込むモデルにも同じ設定を渡す。 */
internal fun androidInferenceTarget(app: Context, preferences: TranslationPreferences): InferenceTarget {
    val choice = preferences.gpuBackend
    val cpuOnly = preferences.cpuOnly || choice == GpuBackendChoice.CPU
    val gpuCapable = if (choice == GpuBackendChoice.AUTO) isQualcommSoc() else !cpuOnly
    val target = InferencePolicy.target(gpuCapable, cpuOnly, true)
    if (target.useGpu) {
        applyBackendEnv(app, choice)
    }
    return target
}
