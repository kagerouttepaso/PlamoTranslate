package app.plamotranslate.pipeline.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitJudgeTest {
    private fun exit(
        cause: ExitCause,
        status: Int = 0,
        time: Long = 0L,
        pid: Int = 1,
        importance: ProcessImportance = ProcessImportance.FOREGROUND,
        mark: ProcessMark? = null,
    ) = ProcessExit(time = time, pid = pid, cause = cause, status = status, importance = importance, mark = mark)

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

    @Test
    fun 使っている最中にOSに止められたものは記録し裏で片付けられたものは記録しない() {
        assertTrue(ExitJudge.recordable(exit(ExitCause.LOW_MEMORY, importance = ProcessImportance.FOREGROUND_SERVICE)))
        assertTrue(ExitJudge.recordable(exit(ExitCause.SIGNALED, 9, importance = ProcessImportance.FOREGROUND_SERVICE)))
        assertFalse(ExitJudge.recordable(exit(ExitCause.LOW_MEMORY, importance = ProcessImportance.BACKGROUND)))
        assertFalse(ExitJudge.recordable(exit(ExitCause.USER_STOPPED)))
        assertTrue(ExitJudge.recordable(exit(ExitCause.NATIVE_CRASH, importance = ProcessImportance.BACKGROUND)))
    }

    @Test
    fun 使っている最中に自分で終わったものも記録する() {
        // 画面翻訳中（フォアグラウンドサービス）に終了コード0で終わった（2026-11-13、作者の端末）
        assertTrue(ExitJudge.recordable(exit(ExitCause.EXIT_SELF, 0, importance = ProcessImportance.FOREGROUND_SERVICE, mark = ProcessMark("a"))))
        // 裏に回った後なら記録しない。更新や権限の変更（SYSTEM_CHANGE）は使っている最中でも記録しない
        assertFalse(ExitJudge.recordable(exit(ExitCause.EXIT_SELF, 0, importance = ProcessImportance.BACKGROUND)))
        assertFalse(ExitJudge.recordable(exit(ExitCause.SYSTEM_CHANGE, importance = ProcessImportance.FOREGROUND)))
        // 知らない理由は、使っている最中なら記録する
        assertTrue(ExitJudge.recordable(exit(ExitCause.OTHER, importance = ProcessImportance.FOREGROUND)))
    }

    @Test
    fun 自分で起動し直したときは記録しない() {
        // killProcess で自分を終えると、OS には前面でシグナル9と残る（featurelab で確かめた）
        val restart = exit(ExitCause.SIGNALED, 9, mark = ProcessMark("a", intended = true))
        assertTrue(ExitJudge.intended(restart))
        assertFalse(ExitJudge.recordable(restart))
        assertFalse(ExitJudge.abnormal(exit(ExitCause.EXIT_SELF, 1, mark = ProcessMark("a", intended = true))))
    }

    @Test
    fun 印はOSに預けた形から戻る() {
        val mark = ProcessMark.create()
        assertEquals(mark, ProcessMark.decode(mark.encode()))
        assertEquals(mark.copy(intended = true), ProcessMark.decode(mark.copy(intended = true).encode()))
        assertNull(ProcessMark.decode("lab:1".toByteArray()))
        assertNull(ProcessMark.decode(null))
        assertTrue(mark.copy(intended = true).encode().size <= 128)
    }

    @Test
    fun 例外で落ちたときの2件のOSの記録は1件にする() {
        // 受け口の直後の記録（印あり）と、停止の知らせを閉じた後の記録（印なし、裏）の2件が残る（featurelab で確かめた）
        val mark = ProcessMark("p1")
        val first = exit(ExitCause.EXCEPTION, time = 100, pid = 7, mark = mark)
        val second = exit(ExitCause.EXCEPTION, time = 5_800, pid = 7, importance = ProcessImportance.BACKGROUND)
        val other = exit(ExitCause.NATIVE_CRASH, time = 9_000, pid = 8, mark = ProcessMark("p2"))
        val plan = ExitSelection.plan(listOf(other, second, first), { it }, readUntil = 0)
        assertEquals(listOf(first, other), plan.record)
        assertEquals(9_000L, plan.readUntil)
    }

    @Test
    fun 読んだ時刻より前と記録しないものは取り込まないが目印は進める() {
        val old = exit(ExitCause.EXCEPTION, time = 10, pid = 1)
        val stopped = exit(ExitCause.USER_STOPPED, time = 50, pid = 2)
        val plan = ExitSelection.plan(listOf(old, stopped), { it }, readUntil = 20)
        assertTrue(plan.record.isEmpty())
        assertEquals(50L, plan.readUntil)
        assertEquals(20L, ExitSelection.plan(emptyList<ProcessExit>(), { it }, readUntil = 20).readUntil)
    }

    @Test
    fun 印の違う記録は同じpidでも別のプロセス() {
        assertFalse(ExitJudge.sameProcess(exit(ExitCause.EXCEPTION, mark = ProcessMark("a")), exit(ExitCause.EXCEPTION, mark = ProcessMark("b"))))
        assertTrue(ExitJudge.sameProcess(exit(ExitCause.EXCEPTION, mark = ProcessMark("a")), exit(ExitCause.EXCEPTION)))
    }
}
