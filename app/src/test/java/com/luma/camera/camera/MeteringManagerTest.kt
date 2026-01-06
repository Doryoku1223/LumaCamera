package com.luma.camera.camera

import org.junit.Assert.*
import org.junit.Test

/**
 * MeteringManager 单元测试
 */
class MeteringManagerTest {

    @Test
    fun `test metering modes defined`() {
        assertEquals(4, MeteringManager.MeteringMode.entries.size)
        assertTrue(MeteringManager.MeteringMode.entries.contains(MeteringManager.MeteringMode.MATRIX))
        assertTrue(MeteringManager.MeteringMode.entries.contains(MeteringManager.MeteringMode.CENTER_WEIGHTED))
        assertTrue(MeteringManager.MeteringMode.entries.contains(MeteringManager.MeteringMode.SPOT))
        assertTrue(MeteringManager.MeteringMode.entries.contains(MeteringManager.MeteringMode.HIGHLIGHT))
    }

    @Test
    fun `test exposure triangle data class`() {
        val exposure = MeteringManager.ExposureTriangle(
            iso = 100,
            shutterSpeed = 8_000_000L, // 8ms in nanoseconds
            aperture = 2.8f,
            ev = 0f
        )
        
        assertEquals(100, exposure.iso)
        assertEquals(8_000_000L, exposure.shutterSpeed)
        assertEquals(2.8f, exposure.aperture, 0.01f)
        assertEquals(0f, exposure.ev, 0.01f)
    }

    @Test
    fun `test metering manager instantiation`() {
        val manager = MeteringManager()
        assertNotNull(manager)
    }
}
