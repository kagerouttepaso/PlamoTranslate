package app.plamotranslate.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import app.plamotranslate.pipeline.diagnostics.ExitCause
import app.plamotranslate.pipeline.diagnostics.ProcessImportance
import org.junit.Assert.assertEquals
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
        assertEquals(ExitCause.SYSTEM_CHANGE, ProcessExits.causeOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals(ExitCause.SYSTEM_CHANGE, ProcessExits.causeOf(ApplicationExitInfo.REASON_PERMISSION_CHANGE))
        // 知らない理由は OTHER（記録の対象に残す）
        assertEquals(ExitCause.OTHER, ProcessExits.causeOf(ApplicationExitInfo.REASON_OTHER))
        assertEquals(ExitCause.OTHER, ProcessExits.causeOf(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test
    fun osの重要度を読み替える() {
        assertEquals(ProcessImportance.FOREGROUND, ProcessExits.importanceOf(RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(ProcessImportance.FOREGROUND_SERVICE, ProcessExits.importanceOf(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals(ProcessImportance.PERCEPTIBLE, ProcessExits.importanceOf(RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
        assertEquals(ProcessImportance.BACKGROUND, ProcessExits.importanceOf(RunningAppProcessInfo.IMPORTANCE_CACHED))
    }
}
