package app.plamotranslate.ocr

import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlinedTextTest {
    private fun block(text: String, script: Script) = OcrBlock(text, Rect(), script)

    @Test
    fun 崩れた英語より正しい英語の点が高い() {
        val broken = block("EEL UR秒OuRateckipoeRincreased", Script.LATIN)
        val good = block("LEVEL UP! Your attack power increased", Script.LATIN)
        assertTrue(OutlinedText.score(good) > OutlinedText.score(broken) + 3)
    }

    @Test
    fun ほかの文字種の混入は減点する() {
        val mixed = block("새로운 스킬을 ab배웠습니다", Script.HANGUL)
        val clean = block("새로운 스킬을 배웠습니다", Script.HANGUL)
        assertTrue(OutlinedText.score(clean) > OutlinedText.score(mixed))
    }
}
