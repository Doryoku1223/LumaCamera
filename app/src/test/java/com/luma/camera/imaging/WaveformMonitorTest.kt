package com.luma.camera.imaging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * WaveformMonitor 单元测试
 */
class WaveformMonitorTest {

    private lateinit var waveformMonitor: WaveformMonitor

    @Before
    fun setup() {
        waveformMonitor = WaveformMonitor()
    }

    @Test
    fun `test waveform types defined`() {
        assertEquals(3, WaveformMonitor.WaveformType.entries.size)
        assertTrue(WaveformMonitor.WaveformType.entries.contains(WaveformMonitor.WaveformType.LUMA))
        assertTrue(WaveformMonitor.WaveformType.entries.contains(WaveformMonitor.WaveformType.RGB))
        assertTrue(WaveformMonitor.WaveformType.entries.contains(WaveformMonitor.WaveformType.PARADE))
    }

    @Test
    fun `test waveformMonitor instance created`() {
        assertNotNull(waveformMonitor)
    }
}
