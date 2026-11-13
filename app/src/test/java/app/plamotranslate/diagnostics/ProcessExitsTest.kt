package app.plamotranslate.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import app.plamotranslate.pipeline.diagnostics.ExitCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OS の終わり方の値の読み替え（[ProcessExits]）。 */
class ProcessExitsTest {
    @Test
    fun osの理由を原因に読み替える() {
        assertEquals(ExitCause.EXIT_SELF, ProcessExits.causeOf(ApplicationExitInfo.REASON_EXIT_SELF))
        assertEquals(ExitCause.NATIVE_CRASH, ProcessExits.causeOf(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(ExitCause.EXCEPTION, ProcessExits.causeOf(ApplicationExitInfo.REASON_CRASH))
        assertEquals(ExitCause.NOT_RESPONDING, ProcessExits.causeOf(ApplicationExitInfo.REASON_ANR))
        assertEquals(ExitCause.SIGNALED, ProcessExits.causeOf(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(ExitCause.LOW_MEMORY, ProcessExits.causeOf(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(ExitCause.USER_STOPPED, ProcessExits.causeOf(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(ExitCause.OTHER, ProcessExits.causeOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun 前面とフォアグラウンドサービスは使っている最中() {
        assertTrue(ProcessExits.inUse(RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(ProcessExits.inUse(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(ProcessExits.inUse(RunningAppProcessInfo.IMPORTANCE_CACHED))
    }
}
