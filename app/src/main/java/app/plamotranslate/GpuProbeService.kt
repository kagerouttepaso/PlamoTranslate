package app.plamotranslate

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.Logs
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.models.file
import app.plamotranslate.pipeline.diagnostics.InferencePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.system.exitProcess

/**
 * 1つの GPU バックエンド候補（Vulkan / Vulkan coopmat無効 / OpenCL / CPU）を別プロセス（`:gpu_probe`）で試し、
 * 結果をメインプロセスへ返して終了する使い捨てサービス。
 * OpenCL の `exit(1)` やドライバのクラッシュが起きても本体プロセスを落とさずに記録できる。
 */
class GpuProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_PROBE) return
            val replyTo = msg.replyTo ?: return
            val choice = GpuBackendChoice.fromKey(msg.data.getString(KEY_CHOICE))
            val modelPath = msg.data.getString(KEY_MODEL_PATH).orEmpty()
            scope.launch {
                val bundle = runProbe(choice, modelPath)
                runCatching {
                    replyTo.send(Message.obtain(null, MSG_RESULT).apply { data = bundle })
                }
                delay(150)
                stopSelf()
                exitProcess(0)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runProbe(choice: GpuBackendChoice, modelPath: String): Bundle {
        val out = Bundle().apply { putString(KEY_CHOICE, choice.key) }
        return try {
            val modelFile = File(modelPath)
            if (!modelFile.canRead()) {
                return out.apply { putString(KEY_ERROR, "翻訳モデルがありません") }
            }
            val requestGpu = applyBackendEnv(applicationContext, choice)
            val target = InferencePolicy.target(gpuAvailable = requestGpu, cpuOnly = !requestGpu, roleAllowsGpu = true)
            val t0 = System.nanoTime()
            val engine = TranslationEngine.load(
                model = modelFile,
                nativeLibDir = applicationInfo.nativeLibraryDir,
                target = target,
                cpu = CpuOptions(),
                cacheDir = cacheDir.resolve("gpu-probe-threads"),
            )
            try {
                val loadMs = (System.nanoTime() - t0) / 1_000_000
                val gpuName = TranslationEngine.gpuDeviceName()
                val cpuName = TranslationEngine.cpuBackend().removePrefix("libggml-cpu-").removeSuffix(".so")
                if (requestGpu && !engine.usesGpu) {
                    return out.apply {
                        putString(KEY_DEVICE, cpuName)
                        putString(KEY_ERROR, "非対応（GPUで読み込めずCPUへフォールバック）")
                    }
                }
                // ウォームアップ後に1文翻訳と4並列を測る
                EngineBenchmark.measureSingle(engine)
                val single = EngineBenchmark.measureSingle(engine)
                val parallel4 = runCatching { EngineBenchmark.measureParallel(engine, 4) }.getOrNull() ?: 0.0
                out.apply {
                    putBoolean(KEY_OK, single != null)
                    putBoolean(KEY_USED_GPU, engine.usesGpu)
                    putString(KEY_DEVICE, if (engine.usesGpu) gpuName.ifBlank { choice.label } else cpuName.ifBlank { "CPU" })
                    putLong(KEY_LOAD_MS, loadMs)
                    if (single != null) {
                        putLong(KEY_FIRST_MS, single.firstMillis)
                        putDouble(KEY_SINGLE_TPS, single.tokensPerSecond)
                        putDouble(KEY_PARALLEL_TPS, parallel4)
                        putString(KEY_SAMPLE, single.text.take(80))
                    } else {
                        putString(KEY_ERROR, "訳文を生成できませんでした")
                    }
                }
            } finally {
                engine.close()
            }
        } catch (error: Throwable) {
            out.apply { putString(KEY_ERROR, error.message ?: error.javaClass.simpleName) }
        }
    }

    companion object {
        const val MSG_PROBE = 1
        const val MSG_RESULT = 2
        const val KEY_CHOICE = "choice"
        const val KEY_MODEL_PATH = "modelPath"
        const val KEY_OK = "ok"
        const val KEY_USED_GPU = "usedGpu"
        const val KEY_DEVICE = "device"
        const val KEY_LOAD_MS = "loadMs"
        const val KEY_FIRST_MS = "firstMs"
        const val KEY_SINGLE_TPS = "singleTps"
        const val KEY_PARALLEL_TPS = "parallelTps"
        const val KEY_SAMPLE = "sample"
        const val KEY_ERROR = "error"
    }
}

/** 1候補の比較結果。 */
internal data class GpuProbeItem(
    val choice: GpuBackendChoice,
    val ok: Boolean,
    val device: String = "",
    val loadMs: Long = 0L,
    val firstMs: Long = 0L,
    val singleTps: Double = 0.0,
    val parallelTps: Double = 0.0,
    val sample: String = "",
    val error: String? = null,
) {
    fun summaryLine(): String = if (ok) {
        "${choice.label} [$device]: 読込 ${loadMs}ms / 初回 ${firstMs}ms / 生成 ${"%.1f".format(singleTps)} tok/s / 4並列 ${"%.0f".format(parallelTps)} tok/s"
    } else {
        "${choice.label}: ${error ?: "失敗"}"
    }
}

/**
 * 全候補を別プロセスで1件ずつ実行するランナー。
 * 1候補ごとにプロセスが終了するため、OpenCL や Vulkan の異常終了でも本体は落ちず次へ進む。
 */
internal object GpuProbeRunner {
    val CANDIDATES = listOf(
        GpuBackendChoice.VULKAN,
        GpuBackendChoice.VULKAN_NO_COOPMAT,
        GpuBackendChoice.OPENCL,
        GpuBackendChoice.CPU,
    )

    fun probeModelFile(context: Context): File? {
        val small = TranslationModel.SMALL.file.file(context)
        if (small.canRead()) return small
        val selected = TranslationPreferences(context).model.file.file(context)
        return selected.takeIf { it.canRead() }
    }

    suspend fun runAll(
        context: Context,
        onStep: (current: GpuBackendChoice, done: List<GpuProbeItem>) -> Unit,
    ): List<GpuProbeItem> {
        val model = probeModelFile(context)
            ?: return CANDIDATES.map { GpuProbeItem(it, ok = false, error = "翻訳モデルが見つかりません") }
        val results = mutableListOf<GpuProbeItem>()
        for (choice in CANDIDATES) {
            onStep(choice, results.toList())
            val item = probeOne(context.applicationContext, choice, model.absolutePath)
            results += item
            Logs.i("GPU比較: ${item.summaryLine()}")
            if (item.ok && item.sample.isNotEmpty()) {
                Logs.i("GPU比較 訳文(${choice.key}): ${item.sample}")
            }
            // 前のプローブプロセスが完全に終了するのを少し待つ
            delay(300)
        }
        return results
    }

    fun formatReport(items: List<GpuProbeItem>): String = buildString {
        appendLine("端末: ${Build.MODEL} (SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}, Android ${Build.VERSION.RELEASE})")
        items.forEach { item ->
            appendLine("- ${item.summaryLine()}")
            if (item.ok && item.sample.isNotEmpty()) {
                appendLine("  訳文: ${item.sample}")
            }
        }
    }.trimEnd()

    private suspend fun probeOne(context: Context, choice: GpuBackendChoice, modelPath: String): GpuProbeItem {
        val done = CompletableDeferred<GpuProbeItem>()
        val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != GpuProbeService.MSG_RESULT) return
                val b = msg.data
                done.complete(
                    GpuProbeItem(
                        choice = choice,
                        ok = b.getBoolean(GpuProbeService.KEY_OK, false),
                        device = b.getString(GpuProbeService.KEY_DEVICE).orEmpty(),
                        loadMs = b.getLong(GpuProbeService.KEY_LOAD_MS, 0L),
                        firstMs = b.getLong(GpuProbeService.KEY_FIRST_MS, 0L),
                        singleTps = b.getDouble(GpuProbeService.KEY_SINGLE_TPS, 0.0),
                        parallelTps = b.getDouble(GpuProbeService.KEY_PARALLEL_TPS, 0.0),
                        sample = b.getString(GpuProbeService.KEY_SAMPLE).orEmpty(),
                        error = b.getString(GpuProbeService.KEY_ERROR),
                    ),
                )
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val remote = Messenger(service)
                val msg = Message.obtain(null, GpuProbeService.MSG_PROBE).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(GpuProbeService.KEY_CHOICE, choice.key)
                        putString(GpuProbeService.KEY_MODEL_PATH, modelPath)
                    }
                }
                runCatching { remote.send(msg) }.onFailure {
                    done.complete(GpuProbeItem(choice, ok = false, error = "送信失敗: ${it.message}"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!done.isCompleted) {
                    done.complete(GpuProbeItem(choice, ok = false, error = "別プロセスが異常終了（クラッシュまたは非対応）"))
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!done.isCompleted) {
                    done.complete(GpuProbeItem(choice, ok = false, error = "別プロセスが終了しました"))
                }
            }
        }
        val intent = Intent(context, GpuProbeService::class.java)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            return GpuProbeItem(choice, ok = false, error = "比較サービスを開始できませんでした")
        }
        return try {
            withTimeoutOrNull(120_000L) { done.await() }
                ?: GpuProbeItem(choice, ok = false, error = "タイムアウト（120秒）")
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }
}
