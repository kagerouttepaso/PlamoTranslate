package app.plamotranslate.pipeline.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitJudgeTest {
    private fun exit(cause: ExitCause, status: Int = 0) = ProcessExit(time = 0L, pid = 1, cause = cause, status = status)

    @Test
    fun 落ちた終わり方は異常() {
        // ggml-opencl のカーネルの組み立ての失敗は exit(1)（2026-11-09、ROG Phone の報告）
        assertTrue(ExitJudge.abnormal(exit(ExitCause.EXIT_SELF, 1)))
        assertTrue(ExitJudge.abnormal(exit(ExitCause.NATIVE_CRASH, 6)))
        assertTrue(ExitJudge.abnormal(exit(ExitCause.SIGNALED, 6)))
        assertTrue(ExitJudge.abnormal(exit(ExitCause.NOT_RESPONDING)))
        assertTrue(ExitJudge.abnormal(exit(ExitCause.EXCEPTION)))
    }

    @Test
    fun 本人やOSが止めた終わり方は異常でない() {
        assertFalse(ExitJudge.abnormal(exit(ExitCause.USER_STOPPED)))
        assertFalse(ExitJudge.abnormal(exit(ExitCause.LOW_MEMORY)))
        assertFalse(ExitJudge.abnormal(exit(ExitCause.SIGNALED, 9)))
        assertFalse(ExitJudge.abnormal(exit(ExitCause.EXIT_SELF, 0)))
        assertFalse(ExitJudge.abnormal(exit(ExitCause.OTHER)))
    }
}
