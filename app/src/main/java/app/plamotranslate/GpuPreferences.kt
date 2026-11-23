package app.plamotranslate

import android.content.Context
import app.plamotranslate.pipeline.diagnostics.InferenceRole

internal class GpuPreferences(context: Context, private val role: InferenceRole) {
    private val translation = context.getSharedPreferences("translation", Context.MODE_PRIVATE)
    private fun key(field: String) = "gpu.${role.key}.$field"

    init {
        synchronized(migrationLock) {
            if (!translation.getBoolean("gpu.rolesMigrated", false)) {
                val editor = translation.edit()
                for ((old, value) in translation.all) {
                    if (old !in legacyKeys) continue
                    val target = "gpu.${InferenceRole.Translation.key}.${old.removePrefix("gpu_")}"
                    when (value) {
                        is Int -> editor.putInt(target, value)
                        is Long -> editor.putLong(target, value)
                        is String -> editor.putString(target, value)
                    }
                    editor.remove(old)
                }
                editor.putBoolean("gpu.rolesMigrated", true).commit()
            }
        }
    }

    /**
     * GPUで読み込んでいるプロセスのID。0なら読み込み中ではない。
     * 書いた直後にプロセスごと終わり得るので、apply ではなく commit ですぐに書く。
     */
    var gpuLoadingPid: Int
        get() = translation.getInt(key("loading_pid"), 0)
        set(value) {
            translation.edit().putInt(key("loading_pid"), value).commit()
        }

    /** 異常終了でない中断（強制停止・メモリ不足など）が、GPUでの読み込み中に何回続いたか。 */
    var gpuInterruptions: Int
        get() = translation.getInt(key("interruptions"), 0)
        set(value) {
            translation.edit().putInt(key("interruptions"), value).commit()
        }

    /** GPUを外した記録。外していなければ null。 */
    var gpuDisabled: GpuDisabled?
        get() {
            val reason = translation.getString(key("disabled_reason"), null) ?: return null
            return GpuDisabled(reason, translation.getString(key("disabled_build"), null).orEmpty(), translation.getLong(key("disabled_at"), 0))
        }
        set(value) {
            translation.edit()
                .putString(key("disabled_reason"), value?.reason)
                .putString(key("disabled_build"), value?.build)
                .putLong(key("disabled_at"), value?.at ?: 0)
                .commit()
        }

    private companion object {
        val migrationLock = Any()
        val legacyKeys = setOf("gpu_loading_pid", "gpu_interruptions", "gpu_disabled_reason", "gpu_disabled_build", "gpu_disabled_at")
    }
}
