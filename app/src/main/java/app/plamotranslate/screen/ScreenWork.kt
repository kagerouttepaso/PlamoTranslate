package app.plamotranslate.screen

import android.graphics.Bitmap
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.overlay.TranslationTarget
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.screen.Thumb
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/*
 * 1回の翻訳（セッション）のデータ（docs/設計/画面翻訳.md「工程とデータ」）。データは1方向に流れ、2種類に分ける。
 * - 前提（ScreenFrame）：撮れたときに1度だけ作り、どの工程も読むだけ。
 * - 工程の結果（PageLayout・TranslationRun）：各工程が1つずつ作って Work に置き、後の工程は読むだけ。
 * 状態遷移（ScreenSession）は番号だけを持ち、データはここにある。どれもメインスレッドだけで触る。
 */

/** 1回の翻訳で使う工程の中身（場面の設定・メニューから決まる）。撮るときに読み、セッションの間は変えない。 */
internal data class LayoutChoice(
    val language: OcrLanguage,
    val outlined: Boolean,
    val grouping: Grouping,
    val fitBubbles: Boolean,
)

/** 1回の翻訳の前提。[image] は枠を決める工程までが使い、その後に捨てる（[release]）。 */
internal class ScreenFrame(
    val image: Bitmap,
    /** 判定Bの基準（撮った画面の格子）。 */
    val reference: Thumb,
    /** 重ねた枚数（1なら重ねていない）と、撮るのにかかった時間。 */
    val frames: Int,
    val captureMillis: Long,
    /** 撮ったときの状態バーの高さ（px）。これより上の単位は訳さない。 */
    val statusBar: Int,
    val choice: LayoutChoice,
) {
    val size: String = "${image.width}x${image.height}"

    fun release() = image.recycle()
}

/** 読む・まとめる・選ぶ・枠を決める工程の結果。 */
internal class PageLayout(
    val lines: Int,
    val units: Int,
    val targets: List<TranslationTarget>,
    val ocrMillis: Long,
    val error: String?,
)

/** 訳す工程の結果。[stats] は流れを閉じるまで増える。 */
internal class TranslationRun(val model: String, val startedAt: Long) {
    var stats: TranslationStream.Stats? = null
}

/** 1回の翻訳の作業と、工程ごとの結果。結果は書く工程が決まっている（コメントの工程だけが書く）。 */
internal class Work(val session: Int) {
    val job: CompletableJob = SupervisorJob()

    /** 撮る工程が書く。 */
    var frame: ScreenFrame? = null

    /** 読む〜枠を決める工程が書く。 */
    var page: PageLayout? = null

    /** 訳す工程が書く。 */
    var run: TranslationRun? = null

    /** 触れたときののぞき。 */
    var peekJob: Job? = null

    /** 作業を取り消し、撮影画像を捨てる。 */
    fun release() {
        job.cancel()
        frame?.release()
    }
}
