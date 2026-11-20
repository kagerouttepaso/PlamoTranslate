package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * 翻訳モデルをアプリ全体で1つだけ持つ口（docs/設計/翻訳コア.md「読み込み」）。テキスト翻訳画面・画面翻訳サービス・カメラ翻訳・
 * 会話翻訳のすべてが同じエンジンを使う。読み込み・切り替え・読み直しの判断は中核の [ModelHost] が持ち、ここは設定
 * （[TranslationPreferences]）を保存して命令を渡すだけ。読み込みの中身（GPU の判定・初回の準備）は [LlamaEngineLoader]。
 * 画面とサービスは命令を出して [state] を購読し、エンジンは受け取らない（受け取るのは [awaitEngine] を使う
 * 翻訳のストリームの入口と計測だけ。docs/設計/翻訳コア.md「ストリームを通さない呼び出し」）。
 */
object EngineHolder {
    private lateinit var app: Context

    private val host = ModelHost(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        LlamaEngineLoader { app },
        selected = { selected(app) },
        parallel = { TranslationPreferences(app).parallel },
    )

    val state: StateFlow<ModelState<TranslationModel>> get() = host.state

    @Synchronized
    private fun attach(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    /** 選ばれている翻訳モデル。選んだモデルのファイルが無ければ0.8Bを使う。 */
    fun selected(context: Context): TranslationModel {
        val model = TranslationPreferences(context).model
        return if (model.isPresent(context)) model else TranslationModel.SMALL
    }

    /** まだ読んでいなければ読み込みを始める。読み込み済み・読み込み中なら何もしない。 */
    fun ensureLoaded(context: Context) {
        attach(context)
        host.ensureLoaded()
    }

    /** 翻訳モデルを切り替える。読み込み中なら、読み込みが終わってから切り替える。 */
    fun select(context: Context, model: TranslationModel) {
        attach(context)
        TranslationPreferences(context.applicationContext).model = model
        host.select()
    }

    /** 同時に訳す数を保存し、今のエンジンにもすぐ効かせる（読み直さない）。 */
    fun setParallel(context: Context, parallel: Int) {
        attach(context)
        TranslationPreferences(context.applicationContext).parallel = parallel
        host.setParallel(parallel)
    }

    /** 今の翻訳モデルを閉じて読み直す（CPUの詳細設定を変えたとき。設定は先に [TranslationPreferences.saveCpu] で保存する）。 */
    fun reload(context: Context) {
        attach(context)
        host.reload()
    }

    /** 読み込みを始めて（まだなら）、読み込みが終わった状態を返す。エンジンは渡さない。 */
    suspend fun awaitSettled(context: Context): ModelState<TranslationModel> {
        attach(context)
        return host.awaitSettled()
    }

    /** 読み込みが終わるまで待ってエンジンと状態を返す。失敗・未配置なら null。翻訳のストリームの入口と計測だけが使う。 */
    suspend fun awaitEngine(context: Context): Pair<TranslationEngine, ModelState.Ready<TranslationModel>>? {
        attach(context)
        return host.awaitEngine()
    }
}
