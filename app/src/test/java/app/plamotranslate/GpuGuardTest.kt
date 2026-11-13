package app.plamotranslate

import app.plamotranslate.GpuGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GPUで落ちたときにCPUへ切り替える判定（[GpuGuard]）。2026-11-09、ROG Phone（Snapdragon 8 Gen 3）で
 * llama.cppのOpenCLが `exit(1)` し、読み込みのたびにアプリが再起動を繰り返した報告から。
 * 異常終了かどうかの判定は中核の ExitJudgeTest、OS の値の読み替えは ProcessExitsTest で確かめる。
 */
class GpuGuardTest {
    @Test
    fun abnormalExitDisablesGpuAtOnce() {
        assertEquals(Decision.Disable("落ちた"), GpuGuard.decide(pending = true, interruptions = 0, abnormal = true, intended = false, exitLabel = "落ちた"))
    }

    @Test
    fun otherStopsDisableGpuOnlyWhenRepeated() {
        assertEquals(Decision.Retry(1), GpuGuard.decide(pending = true, interruptions = 0, abnormal = false, intended = false, exitLabel = "強制停止"))
        assertEquals(Decision.Retry(1), GpuGuard.decide(pending = true, interruptions = 0, abnormal = false, intended = false, exitLabel = "記録なし"))
        val second = GpuGuard.decide(pending = true, interruptions = 1, abnormal = false, intended = false, exitLabel = "強制停止")
        assertEquals(true, second is Decision.Disable)
    }

    @Test
    fun intendedRestartKeepsGpu() {
        // 設定を変えて起動し直したとき（killProcess）は、読み込み中でも GPU で落ちたとみなさない
        assertEquals(Decision.Normal, GpuGuard.decide(pending = true, interruptions = 1, abnormal = false, intended = true, exitLabel = "シグナル9"))
    }

    @Test
    fun nothingPendingKeepsGpu() {
        assertEquals(Decision.Normal, GpuGuard.decide(pending = false, interruptions = 1, abnormal = true, intended = false, exitLabel = ""))
    }
}
