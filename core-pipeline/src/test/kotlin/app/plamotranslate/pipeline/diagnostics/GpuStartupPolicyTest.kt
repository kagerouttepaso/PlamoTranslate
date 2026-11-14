package app.plamotranslate.pipeline.diagnostics

import app.plamotranslate.pipeline.diagnostics.GpuStartupPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuStartupPolicyTest {
    private fun decide(saved: Boolean = false, pending: Boolean = true, interruptions: Int = 0,
                       abnormal: Boolean = false, intended: Boolean = false) =
        GpuStartupPolicy.decide(saved, pending, interruptions, abnormal, intended, "終了理由")

    @Test fun 保存したCPU退避は新しい起動でも解除しない() {
        assertEquals(Decision.KeepCpu, decide(saved = true, pending = false))
        assertEquals(Decision.KeepCpu, decide(saved = true, intended = true))
        // 明示的な再試行で退避と準備中の印を消した後だけ、通常の選択へ戻る。
        assertEquals(Decision.Normal, decide(saved = false, pending = false))
    }

    @Test fun 異常終了はすぐに退避する() {
        assertEquals(Decision.Disable("終了理由"), decide(abnormal = true))
    }

    @Test fun 原因不明の中断は二回続いたときだけ退避する() {
        assertEquals(Decision.Retry(1), decide())
        assertEquals(Decision.Disable("GPUでの読み込みが2回続けて終わりませんでした"), decide(interruptions = 1))
    }

    @Test fun 意図した再起動と準備中でない終了はGPUを外さない() {
        assertEquals(Decision.Normal, decide(interruptions = 1, intended = true))
        assertEquals(Decision.Normal, decide(pending = false, abnormal = true))
    }
}
