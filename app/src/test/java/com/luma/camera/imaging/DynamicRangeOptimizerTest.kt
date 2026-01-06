package com.luma.camera.imaging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * DynamicRangeOptimizer 单元测试
 */
class DynamicRangeOptimizerTest {

    private lateinit var dro: DynamicRangeOptimizer

    @Before
    fun setup() {
        dro = DynamicRangeOptimizer()
    }

    @Test
    fun `test default DRO params`() {
        val params = DynamicRangeOptimizer.DroParams()
        assertEquals(0.8f, params.highlightRecovery, 0.01f)
        assertEquals(0.5f, params.shadowLift, 0.01f)
        assertEquals(0.3f, params.localContrast, 0.01f)
        assertTrue(params.preserveHighlightColor)
    }

    @Test
    fun `test DRO instance created`() {
        assertNotNull(dro)
    }
}
