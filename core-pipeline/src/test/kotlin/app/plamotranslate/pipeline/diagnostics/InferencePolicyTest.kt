package app.plamotranslate.pipeline.diagnostics

import org.junit.Assert.*
import org.junit.Test

class InferencePolicyTest {
    @Test fun `CPU限定と端末の上限を優先し役割の退避で共通の許可を変えない`() {
        assertFalse(InferencePolicy.target(true, true, true).useGpu)
        assertFalse(InferencePolicy.target(false, false, true).useGpu)
        assertEquals(InferenceTarget(true, false), InferencePolicy.target(true, false, false))
        assertTrue(InferencePolicy.target(true, false, true).useGpu)
    }

    @Test fun `同優先度は到着順を保ち確定書き起こしだけが優先する`() {
        val queue = mutableListOf(1 to GpuWork.Regular, 2 to GpuWork.FinalTranscript,
            3 to GpuWork.Regular, 4 to GpuWork.FinalTranscript)
        val order = generateSequence { InferencePolicy.next(queue) { it.second }?.also(queue::remove) }.map { it.first }.toList()
        assertEquals(listOf(2, 4, 1, 3), order)
    }
}
