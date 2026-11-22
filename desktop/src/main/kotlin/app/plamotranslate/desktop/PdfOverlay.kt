package app.plamotranslate.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import app.plamotranslate.desktop.pdf.PdfFonts
import app.plamotranslate.desktop.pdf.PdfViewport
import app.plamotranslate.pipeline.pdf.PdfLayout
import app.plamotranslate.pipeline.pdf.PdfMark
import app.plamotranslate.pipeline.pdf.PdfMeasure
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import kotlin.math.atan2
import kotlin.math.hypot

/** PDF に埋め込むフォントの同じバイト列を Skia でも使う。取得中は Windows の日本語フォントで配置する。 */
internal class PdfOverlayFonts(fonts: PdfFonts?) : AutoCloseable {
    private fun face(bytes: ByteArray?, style: FontStyle) = bytes?.let {
        Data.makeFromBytes(it).use { data -> FontMgr.default.makeFromData(data) }
    } ?: FontMgr.default.matchFamilyStyle("Yu Gothic", style)

    private val regularFace = face(fonts?.regular, FontStyle.NORMAL)
    private val boldFace = face(fonts?.bold, FontStyle.BOLD)
    private val regular = Font(regularFace, 100f)
    private val bold = Font(boldFace, 100f)
    val measure: PdfMeasure = fonts?.measure ?: { text, heavy, size ->
        at(heavy, 100.0).measureTextWidth(text) * size / 100
    }

    fun at(heavy: Boolean, size: Double): Font = (if (heavy) bold else regular).apply { this.size = size.toFloat() }

    override fun close() {
        regular.close()
        bold.close()
        regularFace?.close()
        boldFace?.close()
    }
}

@Composable
internal fun PdfOverlay(marks: List<PdfMark>, viewport: PdfViewport, fonts: PdfOverlayFonts,
                        fading: Boolean, onFaded: () -> Unit, background: (PdfMark) -> Color) {
    val placements = remember(marks, fonts) {
        marks.map { PdfLayout.planParagraph(it.geometry, it.pieces, fonts.measure) }
    }
    val alpha = remember(fading) { Animatable(1f) }
    LaunchedEffect(fading) {
        if (fading) {
            alpha.animateTo(0f, tween(250, easing = LinearEasing))
            onFaded()
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
        // 四角をすべて先に描き、隣の段落の背景で訳文を消さない。
        for (mark in marks) {
            val box = mark.geometry.bounds
            val padded = box.copy(left = box.left - 1, bottom = box.bottom - 1, right = box.right + 1, top = box.top + 1)
            val rect = viewport.toView(padded)
            drawRect(background(mark).copy(alpha = if (mark.pieces.isEmpty()) .45f else 1f),
                Offset(rect.left * size.width, rect.top * size.height),
                Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height))
        }
        // PDF の+x と-y を画面座標に写す。回転したページでも文字と原文の基線をそろえる。
        val origin = viewport.toView(0.0, 0.0)
        val right = viewport.toView(1.0, 0.0)
        val down = viewport.toView(0.0, -1.0)
        val dx = (right.x - origin.x) * size.width
        val dy = (right.y - origin.y) * size.height
        val xScale = hypot(dx, dy).toFloat()
        val yScale = hypot((down.x - origin.x) * size.width, (down.y - origin.y) * size.height).toFloat()
        drawIntoCanvas { target ->
            val canvas = target.nativeCanvas
            canvas.save()
            try {
                canvas.translate((origin.x * size.width).toFloat(), (origin.y * size.height).toFloat())
                canvas.rotate(Math.toDegrees(atan2(dy, dx)).toFloat())
                canvas.scale(xScale, yScale)
                Paint().use { paint ->
                    for ((index, mark) in marks.withIndex()) {
                        val layout = placements[index]
                        val color = mark.paragraph.color
                        for (line in layout.lines) for (run in line.runs) {
                            paint.color = org.jetbrains.skia.Color.makeARGB(if (run.source) 140 else 255,
                                color.red, color.green, color.blue)
                            canvas.drawString(run.text, run.x.toFloat(), -line.baseline.toFloat(),
                                fonts.at(run.bold, layout.size), paint)
                        }
                    }
                }
            } finally {
                canvas.restore()
            }
        }
    }
}
