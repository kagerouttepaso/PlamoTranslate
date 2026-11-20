package app.plamotranslate.pipeline.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 翻訳モデルの状態。[M] は翻訳モデルの種類（`app` の `TranslationModel`）。 */
sealed interface ModelState<out M> {
    data object Loading : ModelState<Nothing>

    /**
     * 使える。[generation] は読み込むたびに増える番号で、同じ番号なら同じエンジン（同時に訳す数 [parallel] を変えても変わらない）。
     * [gpu] は GPU で動いているか、[loadMillis] は読み込みにかかった時間。エンジンそのものは持たない（[ModelHost.awaitEngine]）。
     */
    data class Ready<M>(val model: M, val generation: Long, val parallel: Int, val gpu: Boolean, val loadMillis: Long) : ModelState<M>

    /** モデルのファイルが無い（[path] はあるはずの場所）。 */
    data class Missing(val path: String) : ModelState<Nothing>

    data class Failed(val message: String) : ModelState<Nothing>
}

/** 翻訳エンジンを読み込む口（[ModelHost]）。llama.cpp の読み込み（JNI）と端末の判断（GPU を使うかなど）は実装が持つ。 */
interface EngineLoader<M, E : JobEngine> {
    /** [model] のファイルが無ければ、あるはずの場所。あれば null。 */
    fun missing(model: M): String?

    /** [model] を読み込み、同時に訳す数を [parallel] にして返す（初回の準備まで済ませる）。読めなければ例外。 */
    suspend fun load(model: M, parallel: Int): E

    fun usesGpu(engine: E): Boolean

    /** 同時に訳す数を変える（読み直さずに効く）。 */
    fun setParallel(engine: E, parallel: Int)

    /** 閉じる。走っている翻訳が終わってから閉じる。 */
    suspend fun close(engine: E)
}

/**
 * 翻訳モデルをアプリ全体で1つだけ持つ状態遷移（docs/設計/翻訳コア.md「読み込み」）。命令（[ensureLoaded]・
 * [select]・[reload]・[setParallel]）はすぐ戻り、結果は [state] を購読して知る。
 *
 * - 読み込みは1つずつ行う。読み込み中に選び直した・読み直しを頼まれたら、今の読み込みが終わってから、そのときに選ばれている
 *   モデル（[selected]）を読み直す（選んだモデルと読み込んだモデルが食い違ったままにしない）。
 * - 切り替えと読み直しは、今のエンジンを閉じてから読み込む（2つのモデルを同時にメモリに置かない）。
 * - 同じモデルを選んでも読み直さない。同時に訳す数の変更は今のエンジンにすぐ効かせ、世代の番号は変えない。
 *
 * エンジンを受け取る口は [awaitEngine] だけで、翻訳のストリームを開く入口と、`docs/設計/翻訳コア.md`「ストリームを通さない呼び出し」
 * の例外だけが使う。状態は [lock] で守り、命令はどのスレッドから呼んでもよい。
 */
class ModelHost<M, E : JobEngine>(
    private val scope: CoroutineScope,
    private val loader: EngineLoader<M, E>,
    /** 今選ばれているモデル（設定）。読み込むたびに読む。 */
    private val selected: () -> M,
    /** 同時に訳す数（設定）。読み込むたびに読む。 */
    private val parallel: () -> Int,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<ModelState<M>>(ModelState.Loading)
    val state: StateFlow<ModelState<M>> = _state.asStateFlow()

    /** 読み込んだエンジンと、その状態（[state] の Ready と同じ世代）。 */
    private var engine: E? = null
    private var loading = false

    /** 読み込み中に選び直された・読み直しを頼まれた。今の読み込みの後に読み直すか決める。 */
    private var pending: Pending? = null
    private var generation = 0L

    private enum class Pending { SELECT, RELOAD }

    /** まだ読んでいなければ読み込みを始める。使える・読み込み中なら何もしない。 */
    fun ensureLoaded() {
        synchronized(lock) {
            if (_state.value is ModelState.Ready || loading) return
            start(close = null)
        }
    }

    /** モデルを [model] に切り替える（設定は呼ぶ側が先に保存する）。同じモデルなら何もしない。 */
    fun select(model: M) {
        synchronized(lock) {
            if (loading) {
                if (pending == null) pending = Pending.SELECT
                return
            }
            val current = _state.value
            if (current is ModelState.Ready && current.model == model) return
            start(close = engine)
        }
    }

    /** 今のモデルを閉じて読み直す（CPU の詳細設定を変えたとき。設定は呼ぶ側が先に保存する）。 */
    fun reload() {
        synchronized(lock) {
            if (loading) {
                pending = Pending.RELOAD
                return
            }
            start(close = engine)
        }
    }

    /** 同時に訳す数を変える（設定は呼ぶ側が先に保存する）。今のエンジンにすぐ効かせ、読み込み中なら読み込んだ後に効かせる。 */
    fun setParallel(value: Int) {
        synchronized(lock) {
            val e = engine ?: return
            val ready = _state.value as? ModelState.Ready ?: return
            loader.setParallel(e, value)
            _state.value = ready.copy(parallel = value)
        }
    }

    /** 読み込みが終わるまで待ってエンジンと状態を返す。ファイルが無い・失敗なら null。 */
    suspend fun awaitEngine(): Pair<E, ModelState.Ready<M>>? {
        ensureLoaded()
        while (true) {
            val ready = state.first { it !is ModelState.Loading } as? ModelState.Ready ?: return null
            synchronized(lock) {
                val e = engine
                if (e != null && (_state.value as? ModelState.Ready)?.generation == ready.generation) return e to ready
            }
        }
    }

    /** [close] を閉じてから、選ばれているモデルを読み込む。[lock] の中で呼ぶ。 */
    private fun start(close: E?) {
        loading = true
        engine = null
        _state.value = ModelState.Loading
        scope.launch {
            close?.let { loader.close(it) }
            load()
        }
    }

    private suspend fun load() {
        val model = selected()
        loader.missing(model)?.let { path ->
            finish(model, ModelState.Missing(path), null)
            return
        }
        val count = parallel()
        val start = System.nanoTime()
        val loaded = runCatching { loader.load(model, count) }
        val e = loaded.getOrNull()
        val next = if (e != null) {
            ModelState.Ready(model, ++generation, count, loader.usesGpu(e), (System.nanoTime() - start) / 1_000_000)
        } else {
            val error = loaded.exceptionOrNull()
            ModelState.Failed(error?.message ?: error.toString())
        }
        finish(model, next, e)
    }

    /** [model] の読み込みを終える。読み込み中に頼まれたことがあれば、続けて読み直す。 */
    private fun finish(model: M, next: ModelState<M>, loaded: E?) {
        synchronized(lock) {
            val again = when (pending) {
                Pending.RELOAD -> true
                Pending.SELECT -> selected() != model
                null -> false
            }
            pending = null
            engine = loaded
            if (again) {
                start(close = loaded)
                return
            }
            loading = false
            // 読み込んでいる間に変わった同時に訳す数を効かせる
            val ready = next as? ModelState.Ready
            val count = parallel()
            if (loaded != null && ready != null && ready.parallel != count) {
                loader.setParallel(loaded, count)
                _state.value = ready.copy(parallel = count)
            } else {
                _state.value = next
            }
        }
    }
}
