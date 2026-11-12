package app.plamotranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import java.lang.Character.UnicodeScript

/**
 * ゲームの白縁取り・白抜きの文字を読むための補助（2026-11-12、作者の要望「ゲーム画面の文字は白抜きや白縁取りが多いので、
 * それで認識が弱くならないように」。docs/設計/画面翻訳.md「白縁の文字」）。
 *
 * 白い縁取りの文字は、字の中身（黄・赤・黒など）と縁（白）の差で読ませる作りなので、明るさの画像では字の中身と縁が近い色の
 * ときに字の形が消える（2026-11-12の試験で、黄色い字に白縁の「LEVEL UP! Your attack power increased」を
 * 「EEL UR秒OuRateckipoeRincreased」と読んだ）。画素ごとにRGBの最小値を取ると、白い縁は白（255）のまま、
 * 色の付いた字の中身はどの色でも暗くなるので、「白い紙に黒い字」の形になる（[whiteness]）。
 * ただし暗い背景の上の色の字はかえって薄くなるので、元の画像の読み取りと両方を使い、行ごとに崩れの少ない方を採る（[pick]）。
 */
object OutlinedText {

    /** 画素ごとにRGBの最小値を取った灰色の画像。白い縁取りは白く、色の付いた字の中身は暗くなる。 */
    fun whiteness(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val m = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            px[i] = (0xFF shl 24) or (m shl 16) or (m shl 8) or m
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 元の画像の読み取り [primary] と、[whiteness] の画像の読み取り [alternate] を行（まとまり）ごとに比べてまとめる。
     * - 重なるまとまりがあれば、[score] がはっきり高い方を採る（元の読み取りを優先し、同じくらいなら元を残す）。
     * - 片方にしかないまとまりは、崩れていなければ足す（白縁の字は元の画像では見つからないことがある）。
     */
    fun pick(primary: List<OcrBlock>, alternate: List<OcrBlock>): List<OcrBlock> {
        val result = primary.toMutableList()
        for (alt in alternate) {
            val overlapping = result.filter { overlaps(it.bounds, alt.bounds) }
            if (overlapping.isEmpty()) {
                if (!TextQuality.looksGarbled(alt.text) && alt.text.count { it.isLetter() } >= 2) result += alt
                continue
            }
            val primaryScore = overlapping.sumOf { score(it) }
            if (score(alt) > primaryScore + MARGIN) {
                android.util.Log.d(
                    "PlamoTranslate",
                    "白縁の読み比べ: 元「${overlapping.joinToString("／") { it.text }}」${primaryScore}点を、" +
                        "最小値の画像の「${alt.text}」${score(alt)}点に置き換えた",
                )
                result.removeAll(overlapping.toSet())
                result += alt
            }
        }
        return result.sortedWith(compareBy<OcrBlock> { it.bounds.top }.thenBy { it.bounds.left })
    }

    /**
     * 読み取りのもっともらしさ。まとまりの文字種の字を1点とし、ほかの文字種の字（ラテン文字の行に混じった漢字など）、
     * 崩れた文字列、空白の抜けた長すぎるラテン文字の語（「OuRateckipoeRincreased」）を減点する。
     */
    fun score(block: OcrBlock): Int {
        val text = block.text
        var own = 0
        var foreign = 0
        text.codePoints().forEach { cp ->
            if (!Character.isLetter(cp)) return@forEach
            if (matches(block.script, cp)) own++ else foreign++
        }
        var s = own - foreign * 3
        if (TextQuality.looksGarbled(text)) s -= 10
        if (block.script == Script.LATIN) {
            s -= text.split(Regex("[^A-Za-z]+")).count { it.length > LONG_WORD } * 6
        }
        return s
    }

    private fun matches(script: Script, cp: Int): Boolean = when (UnicodeScript.of(cp)) {
        UnicodeScript.HANGUL -> script == Script.HANGUL
        UnicodeScript.HAN -> script == Script.HAN || script == Script.KANA
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> script == Script.KANA
        UnicodeScript.LATIN -> script == Script.LATIN
        else -> false
    }

    /** 小さい方の面積の半分以上が重なるか（同じ行を2つの画像で読んだもの）。 */
    private fun overlaps(a: Rect, b: Rect): Boolean {
        val r = Rect()
        if (!r.setIntersect(a, b)) return false
        val smaller = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return r.width() * r.height() * 2 >= smaller
    }

    /** 置き換えるのに要る点の差（同じくらいなら元の読み取りを残す）。 */
    private const val MARGIN = 3

    /** これより長いラテン文字の語は、空白が抜けて語がつながった読み違いとみなす。 */
    private const val LONG_WORD = 15
}
