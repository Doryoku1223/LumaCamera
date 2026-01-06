package com.luma.camera.imaging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * DetailPreserver 单元测试
 */
class DetailPreserverTest {

    private lateinit var detailPreserver: DetailPreserver

    @Before
    fun setup() {
        detailPreserver = DetailPreserver()
    }

    @Test
    fun `test default detail params`() {
        val params = DetailPreserver.DetailParams()
        assertEquals(0.5f, params.denoiseStrength, 0.01f)
        assertEquals(0.7f, params.textureProtection, 0.01f)
        assertEquals(0.3f, params.sharpenAmount, 0.01f)
        assertEquals(0.8f, params.edgePreservation, 0.01f)
    }

    @Test
    fun `test detailPreserver instance created`() {
        assertNotNull(detailPreserver)
    }
}
