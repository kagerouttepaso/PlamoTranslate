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
}
