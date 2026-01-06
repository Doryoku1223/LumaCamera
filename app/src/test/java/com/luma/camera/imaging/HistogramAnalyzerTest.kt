package com.luma.camera.imaging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * HistogramAnalyzer 单元测试
 */
class HistogramAnalyzerTest {

    private lateinit var histogramAnalyzer: HistogramAnalyzer

    @Before
    fun setup() {
        histogramAnalyzer = HistogramAnalyzer()
    }

    @Test
    fun `test histogramAnalyzer instance created`() {
        assertNotNull(histogramAnalyzer)
    }
}
