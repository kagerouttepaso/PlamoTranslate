package app.plamotranslate

import app.plamotranslate.llm.ProcessCompute
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.tts.Speaker
import app.plamotranslate.tts.SupertonicSpeaker
import app.plamotranslate.tts.SystemSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 訳文の読み上げ（2026-11-08、作者の要望）。押したときだけ読み、自動では読まない。
 * 一度に読むのは1つだけで、別のものを押すと前のものを止める。同じものをもう一度押すと止める。
 *
 * Supertonic 3を選んでいても、モデルが無い・読めない言語（中国語）のときは、端末の読み上げで読む。
 * 会話翻訳は読んでいる間とその直後、マイクの音を無音に置き換える（[audible]）。読み上げの声を相手の発話として拾わないため。
 * 読み上げのモデル（Supertonic 3、約220MB）は初めて読むときに読み込み、読み上げを使う画面を離れたら手放す（[release]）。
 * 会話翻訳では続けて何度も読むので、画面にいる間は持っておく（作者の判断）。失敗は画面へ渡し（[failures]）、画面が知らせる。
 * 画面にいる間だけ失敗を知らせることと、離れたら手放すことは、読み上げを使う画面が [attach] の1か所で結ぶ（画面ごとに
 * 書き写すと、漏れた画面で失敗が知らされず、モデルも手放さない）。
 * 設定（読むもの・声・速さ・ステップ数）は [SpeechPreferences] にあり、読むたびに読む。設定の画面で変えると次に読むときから効く。
 */
object ReadAloud {
    /** 読み終わってからも、部屋の反響が消えるまでマイクを無音にしておく時間。 */
    private const val ECHO_MILLIS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var echoJob: Job? = null
    private var supertonic: SupertonicSpeaker? = null
    private var system: SystemSpeaker? = null

    private val _speaking = MutableStateFlow<String?>(null)

    /** 読んでいるものの識別子（[toggle] の key）。読んでいなければ null。 */
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    private val _audible = MutableStateFlow(false)

    /**
     * 読み上げの声が鳴っている、または鳴り終わってから [ECHO_MILLIS] の間（部屋の反響が残る）か。読み始めで真になり、
     * 読み終わり・止めたときから [ECHO_MILLIS] 後に偽になる。録音のスレッドからも読める。
     */
    val audible: StateFlow<Boolean> = _audible.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 読み上げに失敗したときの知らせる文。読み上げを使う画面が購読して出す。 */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    /**
     * 読み上げを使う画面 [activity] に結ぶ。画面にいる間（STARTED）だけ失敗をトーストで出す。画面を離れたら（onStop）
     * 読み上げを止めてモデルを手放す。onCreate で1回呼ぶ。
     */
    fun attach(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                failures.collect { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = release()
        })
    }

    /** [key] を読んでいれば止め、そうでなければ [text] を [lang] で読む。 */
    fun toggle(context: Context, key: String, text: String, lang: Language) {
        if (_speaking.value == key) stop() else speak(context, key, text, lang)
    }

    fun speak(context: Context, key: String, text: String, lang: Language) {
        if (text.isBlank()) return
        val app = context.applicationContext
        job?.cancel()
        _speaking.value = key
        startAudible()
        job = scope.launch {
            try {
                speakerFor(app, lang.code).speak(text, lang.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("読み上げに失敗しました", e)
                _failures.tryEmit(e.message ?: "読み上げに失敗しました")
            } finally {
                if (_speaking.value == key) {
                    _speaking.value = null
                    endAudible()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        if (_speaking.value != null) endAudible()
        _speaking.value = null
    }

    /**
     * 読み上げを止め、読み上げのモデルを手放す（読み上げを使う画面を離れたとき）。読んでいる途中なら、止まるのを待ってから
     * 手放す（作っている途中のモデルを閉じない）。次に読むときに読み込み直す（約0.8〜0.9秒）。
     */
    fun release() {
        val reading = job
        stop()
        // 先に外しておく（手放す前にまた読み始めたら、新しく読み込んだものは閉じない）
        val models = listOfNotNull(supertonic, system)
        supertonic = null
        system = null
        scope.launch {
            reading?.join()
            models.forEach { it.close() }
        }
    }

    private fun startAudible() {
        echoJob?.cancel()
        _audible.value = true
    }

    /** 鳴り終わった：反響が消えるまで待ってから、鳴っていないことにする（その間にまた読み始めたら取り消す）。 */
    private fun endAudible() {
        echoJob?.cancel()
        echoJob = scope.launch {
            delay(ECHO_MILLIS)
            _audible.value = false
        }
    }

    /** Supertonic 3の声の数（設定の画面の選択肢）。モデルが無ければ0。 */
    suspend fun supertonicVoices(context: Context): Int =
        if (!ModelCatalog.speechPresent(context)) 0 else supertonic(context.applicationContext).voices()

    /** Supertonic 3を選んでいて、利用条件に同意し、モデルがそろっていて、その言語を読めれば使う。それ以外は端末の読み上げ。 */
    private fun speakerFor(app: Context, lang: String): Speaker {
        val preferences = SpeechPreferences(app)
        if (preferences.engine == SpeechEngine.SUPERTONIC && preferences.supertonicAgreed && ModelCatalog.speechPresent(app)) {
            val s = supertonic(app)
            if (s.supports(lang)) {
                s.voice = preferences.voice
                s.speed = preferences.speed
                s.steps = preferences.steps
                return s
            }
        }
        return system ?: SystemSpeaker(app).also { system = it }
    }

    private fun supertonic(app: Context): SupertonicSpeaker =
        supertonic ?: SupertonicSpeaker(ModelCatalog.speechDir(app), withCpu = { action ->
            ProcessCompute.borrow(false, 0) { n, _ -> action(n) }
        }).also { supertonic = it }
}
