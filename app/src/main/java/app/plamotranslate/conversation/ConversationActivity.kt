package app.plamotranslate.conversation

import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.PartnerMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.plamotranslate.AdvancedPreferences
import app.plamotranslate.ComputeMonitor
import app.plamotranslate.AppTheme
import app.plamotranslate.ReadAloud
import app.plamotranslate.llm.Language
import kotlinx.coroutines.flow.drop

/**
 * 会話翻訳の画面（docs/設計/会話翻訳.md「画面」）。
 * 対面：上下2分割で、上半分は相手向けに逆さに出す。端末を2人の間に置いて使う。
 * 1画面：2人で同じ一覧を見る。発話ごとに原文と訳を並べる。
 * 会話中は画面を点けたままにする。
 */
class ConversationActivity : ComponentActivity() {
    private val vm: ConversationViewModel by viewModels()
    private var requestingMic = false
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingMic = false
        if (granted && !isFinishing) startListening() else vm.stop()
    }

    private val logExport = ConversationLogExport(this) { vm.state.value.turns }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestingMic = savedInstanceState?.getBoolean(REQUESTING_MIC) == true
        enableEdgeToEdge()
        // 読み上げの失敗は画面にいる間だけ出す。画面を離れたら読み上げのモデルを手放す
        ReadAloud.attach(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme {
                app.plamotranslate.ThreadTrialDialog()
                val state by vm.state.collectAsStateWithLifecycle()
                val model by vm.model.collectAsStateWithLifecycle()
                // 詳細設定の「翻訳中に CPU の使われ方を出す」。設定から戻ったときに読み直す
                val owner = LocalLifecycleOwner.current
                val monitor by produceState(false, owner) {
                    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        value = AdvancedPreferences(this@ConversationActivity).computeMonitor
                    }
                }
                Box {
                ConversationScreen(
                    state = state,
                    model = model,
                    actions = ConversationActions(
                        start = vm::requestStart,
                        stop = vm::stop,
                        cut = vm::cut,
                        partnerMode = vm::setMode,
                        self = vm::setSelf,
                        viewMode = vm::setViewMode,
                        clear = vm::clear,
                        swapLanguage = vm::swapLanguage,
                        retry = vm::retryAsr,
                        shareLog = logExport::share,
                        saveLog = logExport::save,
                    ),
                )
                if (monitor) ComputeMonitor(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 8.dp))
                }
                LaunchedEffect(state.awaitingInput) {
                    if (!state.awaitingInput) return@LaunchedEffect
                    val wav = intent.getStringExtra(EXTRA_WAV)
                    if (wav != null && isDebuggable()) {
                        intent.removeExtra(EXTRA_WAV)
                        // 相手の言語は保存しないので、流す前に決めたいときは渡す（言語のコード。無ければ自動）
                        intent.getStringExtra(EXTRA_PARTNER)?.let { code -> Language.entries.firstOrNull { it.code == code } }
                            ?.let { vm.setMode(PartnerMode(it)) }
                        vm.startFromWav(wav)
                    } else startListening()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(REQUESTING_MIC, requestingMic)
    }

    override fun onStart() {
        super.onStart()
        vm.screenShown()
    }

    override fun onStop() {
        super.onStop()
        // 画面を離れたらマイクを止める（フォアグラウンドサービスはまだ無い。読み上げは ReadAloud.attach が止める）
        if (!requestingMic || isFinishing) vm.stop()
        vm.screenHidden()
    }

    private fun startListening() {
        if (requestingMic || !vm.state.value.awaitingInput || isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestingMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        vm.start()
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        private const val REQUESTING_MIC = "requestingMic"
        const val EXTRA_WAV = "wav"
        const val EXTRA_PARTNER = "partner"
    }
}

internal class ConversationActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    /** 今話している分を確定して次のカードにする（聞くのは続ける）。 */
    val cut: () -> Unit,
    val partnerMode: (PartnerMode) -> Unit,
    val self: (Language) -> Unit,
    val viewMode: (ViewMode) -> Unit,
    val clear: () -> Unit,
    /** 確定したカード（[Turn.id]）の言語の取り違えを直す（カードを横にスライド）。 */
    val swapLanguage: (Long) -> Unit,
    val retry: () -> Unit,
    val shareLog: () -> Unit,
    val saveLog: () -> Unit,
)

@Composable
private fun ConversationScreen(state: ConversationUiState, model: ModelState<TranslationModel>, actions: ConversationActions) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.viewMode) {
                ViewMode.FACE_TO_FACE -> {
                    // 相手向け：相手の言語を大きく、逆さに出す
                    PartnerHalf(state, actions, Modifier.weight(1f).fillMaxWidth())
                    Controls(state, model, actions)
                    // 自分向け：自分の言語を大きく出す
                    FollowingList(state, Modifier.weight(1f).fillMaxWidth()) {
                        items(state.turns, key = { it.id }) { FaceCard(it, reader = Reader.ME, onSwap = actions.swapLanguage) }
                        items(state.cards, key = ::cardKey) { ListeningCard(it, Reader.ME, state) }
                    }
                }
                ViewMode.SHARED -> {
                    FollowingList(state, Modifier.weight(1f).fillMaxWidth()) {
                        items(state.turns, key = { it.id }) { SharedCard(it, onSwap = actions.swapLanguage) }
                        items(state.cards, key = ::cardKey) { ListeningCard(it, reader = null, state) }
                    }
                    Controls(state, model, actions)
                }
            }
        }
    }
}

/** 聞き取り中のカードの一覧の鍵。話し中から書き起こし待ちになっても同じカードとして並べたままにする。 */
private fun cardKey(card: LiveSpeech): String = "card-${card.card}"

/** 聞き取り中のカード：話し中なら音量と認識の遅れも出し、閉じたカードは書き起こし待ちとして出す。 */
@Composable
private fun ListeningCard(card: LiveSpeech, reader: Reader?, state: ConversationUiState) =
    if (card.open) LiveCard(card, reader, state.level, state.lagSeconds) else WaitingCard(card, reader)

/**
 * 相手向けの半分。一覧ごと180°回すと、指を離したときの勢い（フリング）の向きがずれて急に止まる
 * （2026-11-06、作者の指摘「手を離した瞬間にブレーキがかかる」）。一覧は回さず、各カードだけを回し、
 * 並びは下から上へ（reverseLayout）にする。相手から見ると上から下へ並び、指の動きと画面の動きが一致する。
 */
@Composable
private fun PartnerHalf(state: ConversationUiState, actions: ConversationActions, modifier: Modifier) {
    // 背景は自分側と同じ地の色にする。別の色にすると、カードの色と混ざって見分けにくかった（2026-11-06、作者の指摘）
    FollowingList(state, modifier, reverse = true) {
        items(state.turns, key = { it.id }) { Box(Modifier.rotate(180f)) { FaceCard(it, reader = Reader.PARTNER, onSwap = actions.swapLanguage) } }
        items(state.cards, key = ::cardKey) { Box(Modifier.rotate(180f)) { ListeningCard(it, Reader.PARTNER, state) } }
    }
}

/**
 * 新しい発話が来たら末尾へ送る一覧。作者が読み返すために末尾から離れている間は動かさない
 * （訳が1トークン届くたびに末尾へ戻すと、スクロールの途中で引き戻される）。
 *
 * 対面と1画面を切り替えると、この一覧は作り直される。作り直した一覧は末尾（今話している発話）から始める
 * （作者の指摘「切り替えたタイミングで今話している内容が画面から消える」。以前は先頭から始まり、
 * 先頭にいることを「読み返し中」と取り違えて末尾へ追わなくなっていた）。
 */
@Composable
private fun FollowingList(
    state: ConversationUiState,
    modifier: Modifier,
    reverse: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val count = state.turns.size + state.cards.size
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (count - 1).coerceAtLeast(0))
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // スクロールが止まった時点で末尾にいれば、以後も新しい発話を追う。
        // 作った直後の「止まっている」は、まだ並べる前（先頭）の状態なので見ない
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(count, state.turns.lastOrNull()?.displayTranslation?.length, state.cards.lastOrNull()?.text, state.cards.lastOrNull()?.translation) {
        if (follow && count > 0) listState.scrollToEnd(count)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        reverseLayout = reverse,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private suspend fun LazyListState.scrollToEnd(count: Int) {
    // 最後のカードが伸びた分も見えるよう、最後のカードの末尾まで送る（大きすぎる値は末尾で止まる）
    scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE / 4)
}
