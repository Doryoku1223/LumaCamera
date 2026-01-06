package com.luma.camera.imaging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * RawProcessor 单元测试
 *
 * 验证 RAW 处理基本功能
 */
class RawProcessorTest {

    private lateinit var rawProcessor: RawProcessor

    @Before
    fun setup() {
        rawProcessor = RawProcessor()
    }

    @Test
    fun `test bayer patterns are defined`() {
        assertEquals(4, RawProcessor.BayerPattern.entries.size)
        assertTrue(RawProcessor.BayerPattern.entries.contains(RawProcessor.BayerPattern.RGGB))
        assertTrue(RawProcessor.BayerPattern.entries.contains(RawProcessor.BayerPattern.BGGR))
        assertTrue(RawProcessor.BayerPattern.entries.contains(RawProcessor.BayerPattern.GRBG))
        assertTrue(RawProcessor.BayerPattern.entries.contains(RawProcessor.BayerPattern.GBRG))
    }

    @Test
    fun `test demosaic methods are defined`() {
        assertEquals(4, RawProcessor.DemosaicMethod.entries.size)
        assertTrue(RawProcessor.DemosaicMethod.entries.contains(RawProcessor.DemosaicMethod.BILINEAR))
        assertTrue(RawProcessor.DemosaicMethod.entries.contains(RawProcessor.DemosaicMethod.VNG))
        assertTrue(RawProcessor.DemosaicMethod.entries.contains(RawProcessor.DemosaicMethod.AHD))
        assertTrue(RawProcessor.DemosaicMethod.entries.contains(RawProcessor.DemosaicMethod.DCB))
    }

    @Test
    fun `test rawProcessor instance created`() {
        assertNotNull(rawProcessor)
    }
}
