package app.plamotranslate.pipeline.screen

import app.plamotranslate.pipeline.layout.Grouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenPresetTest {
    @Test
    fun プリセットの組はそのプリセットとして読み取れる() {
        for (p in ScreenPreset.entries) assertEquals(p, ScreenPreset.matching(p.settings))
    }

    @Test
    fun 行を1つ変えるとカスタムになる() {
        val custom = ScreenPreset.GAME.settings.copy(grouping = Grouping.BUBBLE)
        assertNull(ScreenPreset.matching(custom))
    }

    @Test
    fun プリセットの組は互いに違う() {
        assertEquals(ScreenPreset.entries.size, ScreenPreset.entries.map { it.settings }.toSet().size)
    }

    @Test
    fun 場面の設定は認識モードと消え方から組み立てる() {
        assertEquals(SceneProfile.of(motion = true, untilTouched = true), ScreenPreset.GAME.settings.scene)
    }
}
