package app.plamotranslate.screen

import android.graphics.Bitmap
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.ocr.OcrLine
import app.plamotranslate.overlay.TranslationTarget
import app.plamotranslate.overlay.groupLines
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.screen.ScreenSettings
import app.plamotranslate.pipeline.screen.Thumb
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/*
 * 1回の翻訳（セッション）のデータ（docs/設計/画面翻訳.md「工程とデータ」）。データは1方向に流れ、2種類に分ける。
 * - 前提（ScreenFrame）：撮れたときに1度だけ作る変わらない値。どの工程も読むだけ。
 * - 工程の結果（PageLayout・TranslationRun）：各工程が1度だけ Work に置き、後の工程は読むだけ。
 * 撮影画像は寿命のある資源なので、前提に入れず Work が持ち、枠を決めたら捨てる。
 * 状態遷移（ScreenSession）は番号だけを持ち、データはここにある。どれもメインスレッドだけで触る。
 */

/** 1回の翻訳で使う工程の中身。撮るときに1度だけ、メニューの設定から作る（[of]）。セッションの間は変えない。 */
internal data class LayoutChoice(
    val language: OcrLanguage,
    val outlined: Boolean,
    val grouping: Grouping,
    val fitBubbles: Boolean,
) {
    companion object {
        /** 原文の言語と工程の中身の組から作る。白縁の読み方も組（場面の設定）から決まる。 */
        fun of(language: OcrLanguage, settings: ScreenSettings) =
            LayoutChoice(language, settings.scene.outlinedText, settings.grouping, settings.fit)
    }
}

/** 1回の翻訳の前提（変わらない値）。 */
internal class ScreenFrame(
    /** 判定Bの基準（撮った画面の格子）。 */
    val reference: Thumb,
    /** 重ねた枚数（1なら重ねていない）と、撮るのにかかった時間。 */
    val frames: Int,
    val captureMillis: Long,
    /** 撮った画面の大きさ。 */
    val width: Int,
    val height: Int,
    /** 撮ったときの状態バーの高さ（px）。これより上の単位は訳さない。 */
    val statusBar: Int,
    val choice: LayoutChoice,
) {
    val size: String get() = "${width}x$height"
}

/** 読む・まとめる・選ぶ・枠を決める工程の結果。 */
internal class PageLayout(
    val lines: Int,
    val units: Int,
    val targets: List<TranslationTarget>,
    val ocrMillis: Long,
    val error: String?,
) {
    companion object {
        /**
         * 読んだ行から、まとめる・選ぶ・枠を決める工程を続けて行う。前提 [frame] と撮影画像 [image] は読むだけ。
         * 判断は中核（まとめ方・占めてよい範囲）と overlay（選ぶ・枠を決める）にあり、ここは順番だけを持つ。
         */
        fun of(frame: ScreenFrame, image: Bitmap, lines: List<OcrLine>, ocrMillis: Long, error: String?): PageLayout {
            val choice = frame.choice
            val units = groupLines(lines, choice.grouping, frame.width, frame.height)
            val targets = selectTargets(units, image, frame.statusBar, SCREEN_TARGET, fitBubbles = choice.fitBubbles)
            return PageLayout(lines.size, units.size, targets, ocrMillis, error)
        }
    }
}

/** 訳す工程の結果。[stats] は流れを閉じるまで増える。 */
internal class TranslationRun(val model: String, val startedAt: Long) {
    var stats: TranslationStream.Stats? = null
}

/**
 * 1回の翻訳の作業と、工程ごとの結果。結果は工程ごとの入口からしか書けず、2度は書けない（[captured]・[laidOut]・[translating]）。
 */
internal class Work(val session: Int) {
    val job: CompletableJob = SupervisorJob()

    /** 撮影画像。撮る工程が置き、枠を決めたら（[laidOut]）捨てる。 */
    var image: Bitmap? = null
        private set
    var frame: ScreenFrame? = null
        private set
    var page: PageLayout? = null
        private set
    var run: TranslationRun? = null
        private set

    /** 触れたときののぞき。 */
    var peekJob: Job? = null

    /** 撮る工程の結果を置く。 */
    fun captured(image: Bitmap, frame: ScreenFrame) {
        check(this.frame == null) { "前提は1度だけ作る" }
        this.image = image
        this.frame = frame
    }

    /** 読む〜枠を決める工程の結果を置き、撮影画像を捨てる（枠の色はもう読んだ）。 */
    fun laidOut(page: PageLayout) {
        check(this.page == null) { "枠を決める工程の結果は1度だけ置く" }
        this.page = page
        image?.recycle()
        image = null
    }

    /** 訳す工程の結果を置く。 */
    fun translating(run: TranslationRun) {
        check(this.run == null) { "訳す工程の結果は1度だけ置く" }
        this.run = run
    }

    /** 作業を取り消し、撮影画像を捨てる。 */
    fun release() {
        job.cancel()
        image?.recycle()
        image = null
    }
}
