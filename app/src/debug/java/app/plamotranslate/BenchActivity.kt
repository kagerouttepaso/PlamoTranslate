package app.plamotranslate

import app.plamotranslate.models.ModelCatalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.overlay.OverlaySlot
import app.plamotranslate.overlay.dp
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.overlay.spToPx
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.screen.OverlayStyle
import app.plamotranslate.screen.SCREEN_TARGET
import app.plamotranslate.screen.ScreenTranslateService
import app.plamotranslate.screen.Tategaki
import app.plamotranslate.screen.VerticalTextView
import app.plamotranslate.screen.Yokogaki
import app.plamotranslate.screen.sourceLanguage
import app.plamotranslate.screen.statusBarHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 画面翻訳の評価用（デバッグビルドだけ）。保存したスクリーンショットに、画面翻訳と同じ
 * 文字認識→訳す対象の選別（selectTargets）→まとめ訳しをかけ、所要時間と訳をJSON Linesに、重ね表示の見た目をPNGに書き出す。
 *
 * ふだんは tools/run-bench.ps1 から動かす（画像の転送・起動・取り出し・採点まで行う。docs/設計/画面翻訳.md「評価」）。
 * 原文の言語を指定するときは `--es lang CHINESE` のように渡す（既定は自動）。
 */
class BenchActivity : ComponentActivity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 13f }
        setContentView(log)
        lifecycleScope.launch { run() }
    }

    private fun say(text: String) {
        Log.i(TAG, text)
        log.append(text + "\n")
    }

    private suspend fun run() {
        val root = File(getExternalFilesDir(null), "bench")
        val input = File(root, "in")
        val output = File(root, "out").apply { mkdirs() }
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name }
        if (images.isNullOrEmpty()) {
            say("ベンチ: 画像がありません: ${input.path}")
            return
        }
        val engine = EngineHolder.awaitEngine(this)
        if (engine == null) {
            say("ベンチ: 翻訳モデルを読めません")
            return
        }
        val language = OcrLanguage.parse(intent.getStringExtra("lang"))
        say("ベンチ: 原文の言語 $language")
        val ocr = MultiScriptOcr(ModelCatalog.mangaDir(this))
        say("ベンチ: 漫画モデル ${if (ocr.hasMangaModel) "あり" else "なし（ML Kitだけ）"}")
        val statusBar = statusBarHeight()
        val results = File(output, "results.jsonl")
        results.writeText("")
        say("ベンチ: ${images.size}枚を処理します")

        for ((n, image) in images.withIndex()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: continue
            val t0 = SystemClock.uptimeMillis()
            val blocks = ocr.recognize(bitmap, language)
            val ocrMillis = SystemClock.uptimeMillis() - t0
            val targets = selectTargets(blocks, bitmap, statusBar, SCREEN_TARGET)

            // 画像ごとに新しい翻訳の流れを開き、前の画像の訳の控えを使わない（画面翻訳と同じ契約で訳す）
            val translations = Array(targets.size) { StringBuilder() }
            val t1 = SystemClock.uptimeMillis()
            // 訳す対象が無い画像（日本語だけ・空の画面）では流れを開かない。空の依頼は何も流さないので、確定を待つと止まる
            if (targets.isNotEmpty()) coroutineScope {
                val stream = TranslationStream(this, { TranslationStream.Backend(engine, BENCH_ORIGIN) }, TranslationStream.Options(language.sourceLanguage, SCREEN_TARGET))
                try {
                    stream.submit(targets.mapIndexed { i, t -> Segment(i.toLong(), t.sourceText) })
                    val done = HashSet<Long>()
                    stream.updates.transformWhile { u ->
                        when (u) {
                            is SegmentUpdate.Partial -> Unit
                            is SegmentUpdate.Final -> translations[u.id.toInt()].append(u.text).also { done += u.id }
                            is SegmentUpdate.Failed -> done += u.id
                        }
                        emit(u)
                        done.size < targets.size
                    }.collect { }
                } finally {
                    stream.close()
                }
            }
            val translateMillis = SystemClock.uptimeMillis() - t1

            val fits = withContext(Dispatchers.IO) {
                render(bitmap, targets.map { it.slot }, translations.map { it.toString() },
                    File(output, image.nameWithoutExtension + ".png"))
            }
            val json = JSONObject()
                .put("image", image.name)
                .put("ocrMs", ocrMillis)
                .put("translateMs", translateMillis)
                .put("blocks", blocks.size)
                .put("targets", JSONArray().apply {
                    targets.forEachIndexed { i, target ->
                        val b = target.block
                        put(JSONObject()
                            .put("script", b.script.name)
                            .put("vertical", b.vertical)
                            .put("bounds", JSONArray(listOf(b.bounds.left, b.bounds.top, b.bounds.right, b.bounds.bottom)))
                            .put("source", b.text)
                            .put("translation", translations[i].toString())
                            .put("textSp", fits[i].first)
                            .put("fitted", fits[i].second))
                    }
                })
                .put("skipped", JSONArray().apply {
                    val picked = targets.map { it.block }.toSet()
                    blocks.filter { it !in picked }.forEach { b ->
                        put(JSONObject().put("source", b.text)
                            .put("bounds", JSONArray(listOf(b.bounds.left, b.bounds.top, b.bounds.right, b.bounds.bottom))))
                    }
                })
            withContext(Dispatchers.IO) { results.appendText(json.toString() + "\n") }
            bitmap.recycle()
            say("ベンチ: ${n + 1}/${images.size} ${image.name} 認識${blocks.size}件→訳${targets.size}件 文字認識${ocrMillis}ms 翻訳${translateMillis}ms")
        }
        ocr.close()
        say("ベンチ: 完了 ${output.path}")
    }

    /**
     * 重ね表示と同じ枠の広げ方・文字の大きさの範囲（OverlayStyle）で、訳の枠を元の位置に描いた画像を半分の大きさで保存する。
     * 枠ごとに（使った文字の大きさsp, 最小の大きさでも枠に収まったか）を返す。
     */
    private fun render(source: Bitmap, slots: List<OverlaySlot>, texts: List<String>, file: File): List<Pair<Float, Boolean>> {
        val fits = mutableListOf<Pair<Float, Boolean>>()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val fill = Paint()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val density = spToPx(1f)
        val innerPad = dp(OverlayStyle.H_PADDING_DP)
        slots.forEachIndexed { i, slot ->
            val box = OverlayStyle.boxRect(this, slot)
            fill.color = slot.colors.background
            paint.color = slot.colors.text
            // 実機の重ね表示と同じ太字と背景色の縁取り
            OverlayStyle.applyHalo(paint, slot.colors.background, resources.displayMetrics.density)
            canvas.drawRect(box, fill)
            if (slot.vertical) {
                // 縦書きの原文には縦書きで描く（画面の重ね表示と同じ Tategaki）
                val pad = VerticalTextView.PADDING_DP * resources.displayMetrics.density
                val area = RectF(box).apply { inset(pad, pad) }
                val (px, fitted) = Tategaki.fit(texts[i], area.width(), area.height(), OverlayStyle.MIN_SP * density, OverlayStyle.MAX_SP * density)
                paint.textSize = px
                Tategaki.draw(canvas, texts[i], area, paint)
                fits += px / density to fitted
                return@forEachIndexed
            }
            // 画面の重ね表示（HorizontalTextView）と同じ計算で、枠に収まる最大の大きさで組んで描く
            val area = RectF(box).apply { inset(innerPad.toFloat(), 0f) }
            val (layout, fitted) = Yokogaki.fit(
                texts[i], paint, area.width().toInt(), area.height().toInt(),
                OverlayStyle.MIN_SP, OverlayStyle.MAX_SP, density, center = slot.bubble,
            )
            fits += paint.textSize / density to fitted
            Yokogaki.draw(canvas, layout, area)
        }
        val small = Bitmap.createScaledBitmap(out, out.width / 2, out.height / 2, true)
        file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        small.recycle()
        out.recycle()
        return fits
    }

    private companion object {
        /** 評価は画像ごとに流れとメモリの控えを作り直し、永続の控えも使わないので、モデルの確かさは比べない。 */
        val BENCH_ORIGIN = ModelOrigin("bench", 0)
        const val TAG = "PlamoTranslate"
    }
}
