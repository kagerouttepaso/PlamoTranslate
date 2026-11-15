package app.plamotranslate.models

import android.content.Context
import app.plamotranslate.log.Logs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 待ち行列の1組の状態。 */
sealed interface DownloadStatus {
    /** 順番を待っている。 */
    data object Waiting : DownloadStatus

    /** 取っている。[done] はこの組で受け取ったバイト数、[total] はこの組で残っていたファイルの合計。 */
    data class Running(val current: ModelFile, val done: Long, val total: Long) : DownloadStatus {
        val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    data object Done : DownloadStatus

    /** 失敗した。もう一度足せば、続きから取る。 */
    data class Failed(val message: String) : DownloadStatus
}

/** 待ち行列に並んだ1組。 */
data class DownloadItem(val bundle: ModelBundle, val status: DownloadStatus)

/**
 * モデルのダウンロードの待ち行列（2026-11-06、APKを配布するため。2026-11-15、初回にモデルを選んで一括で取るため待ち行列にした）。
 * アプリ全体で1つだけ動かす。足した組を上から順に1組ずつ取り、1組が失敗しても次の組へ進む。
 * 途中で切れたら、次は続きから取る（HTTPのRange）。取り終えたらハッシュを照合し、合えば置き場へ移す。
 * 翻訳・認識の処理そのものは通信しない。通信するのはこのダウンロードだけ。
 */
object ModelDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())

    /** 待ち行列（足した順）。取り終えた組・失敗した組も、次に同じ組を足すまで残す（画面が結果を出すため）。 */
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private var job: Job? = null
    private val completed = mutableMapOf<ModelBundle, () -> Unit>()

    /** [bundle] の今の状態。待ち行列に無ければ null。 */
    fun status(bundle: ModelBundle): DownloadStatus? = _items.value.lastOrNull { it.bundle == bundle }?.status

    /** 待っている・取っている組があるか。 */
    val busy: Boolean get() = _items.value.any { it.status is DownloadStatus.Waiting || it.status is DownloadStatus.Running }

    /**
     * [bundles] を待ち行列の後ろに足し、取り始める。置き済みの組と、もう待っている・取っている組は足さない。
     * [onCompleted] は、その組を取り終えたときに呼ぶ（取っている担当のスレッドで）。
     */
    @Synchronized
    fun enqueue(context: Context, bundles: List<ModelBundle>, onCompleted: (ModelBundle) -> Unit = {}) {
        val app = context.applicationContext
        val queued = _items.value.filter { it.status is DownloadStatus.Waiting || it.status is DownloadStatus.Running }.map { it.bundle }
        val added = bundles.distinct().filter { it !in queued && !it.isPresent(app) }
        if (added.isEmpty()) return
        added.forEach { bundle -> completed[bundle] = { onCompleted(bundle) } }
        // 一度に足した組は、ModelBundle の並び（必須・翻訳・音声認識・読み上げ）で並べる。選ぶ画面と同じ並びにする
        _items.update { list -> list.filterNot { it.bundle in added } + added.sorted().map { DownloadItem(it, DownloadStatus.Waiting) } }
        if (job == null) job = scope.launch { work(app) }
    }

    /** 取っている組と待っている組をやめる（途中のファイルは残し、次は続きから取る）。 */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        _items.update { list -> list.filter { it.status is DownloadStatus.Done || it.status is DownloadStatus.Failed } }
    }

    private suspend fun work(app: Context) {
        while (true) {
            // 待っている組が無くなったら担当を終える（終える判断と足す判断を同じ錠で行い、足した組を取り残さない）
            val bundle = synchronized(this) {
                _items.value.firstOrNull { it.status is DownloadStatus.Waiting }?.bundle.also { if (it == null) job = null }
            } ?: return
            val status = try {
                fetchBundle(app, bundle)
                DownloadStatus.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("モデルのダウンロードに失敗しました（${bundle.label}）", e)
                DownloadStatus.Failed(e.message ?: e.toString())
            }
            set(bundle, status)
            if (status is DownloadStatus.Done) synchronized(this) { completed.remove(bundle) }?.invoke()
        }
    }

    private suspend fun fetchBundle(app: Context, bundle: ModelBundle) {
        val missing = bundle.missing(app)
        val total = missing.sumOf { it.size }
        var doneBefore = 0L
        for (model in missing) {
            set(bundle, DownloadStatus.Running(model, doneBefore, total))
            download(model, model.file(app)) { got -> set(bundle, DownloadStatus.Running(model, doneBefore + got, total)) }
            doneBefore += model.size
        }
    }

    private fun set(bundle: ModelBundle, status: DownloadStatus) {
        _items.update { list -> list.map { if (it.bundle == bundle) it.copy(status = status) else it } }
    }

    /**
     * [model] を [target] へダウンロードする。途中のファイル（`.part`）があれば続きから取る。
     * [onProgress] にはこのファイルで受け取った合計バイト数を渡す。
     */
    internal suspend fun download(model: ModelFile, target: File, onProgress: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (part.length() > model.size) part.delete()
        val resumed = part.length() > 0
        if (part.length() < model.size) fetch(model.url, part, model.size, onProgress)
        onProgress(part.length())
        var actual = sha256(part)
        if (resumed && !actual.equals(model.sha256, ignoreCase = true)) {
            // 続きから取った前半が別の版のファイルだった（版を上げる前の `.part` が残っていた）。最初から取り直す
            Logs.w("途中のファイルが合わないので最初から取り直します: ${target.path}")
            part.delete()
            fetch(model.url, part, model.size, onProgress)
            actual = sha256(part)
        }
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${model.label} が壊れていました（ハッシュが一致しません）。もう一度ダウンロードしてください")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("${model.label} を置けませんでした: ${target.path}")
        Logs.i("モデルを置きました: ${target.path}")
    }

    private suspend fun fetch(url: String, part: File, size: Long, onProgress: (Long) -> Unit) {
        val offset = part.length()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = conn.responseCode
            // 206なら続きから。200なら最初から送られてくるので、途中のファイルを捨てて書き直す
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("ダウンロードに失敗しました（HTTP $code）: $url")
            }
            var got = if (append) offset else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        if (got - lastReport >= 1 shl 20) {
                            onProgress(got)
                            lastReport = got
                        }
                    }
                }
            }
            if (got > size) {
                // 配布元のファイルが差し替わっている。続きから取っても合わないので捨てる
                part.delete()
                throw IOException("ダウンロード元のファイルが想定と違います（$got / $size バイト）。アプリを新しい版にしてください")
            }
            if (got < size) throw IOException("ダウンロードが途中で切れました（$got / $size バイト）。もう一度押すと続きから取ります")
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
