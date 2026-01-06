package com.luma.camera.imaging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ColorFidelity 单元测试
 *
 * 验证色彩保真度处理的基本功能
 */
class ColorFidelityTest {

    private lateinit var colorFidelity: ColorFidelity

    @Before
    fun setup() {
        colorFidelity = ColorFidelity()
    }

    @Test
    fun `test white balance modes defined`() {
        assertEquals(8, ColorFidelity.WhiteBalanceMode.entries.size)
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.AUTO))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.DAYLIGHT))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.CLOUDY))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.TUNGSTEN))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.FLUORESCENT))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.FLASH))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.SHADE))
        assertTrue(ColorFidelity.WhiteBalanceMode.entries.contains(ColorFidelity.WhiteBalanceMode.MANUAL))
    }

    @Test
    fun `test color spaces defined`() {
        assertEquals(4, ColorFidelity.ColorSpace.entries.size)
        assertTrue(ColorFidelity.ColorSpace.entries.contains(ColorFidelity.ColorSpace.SRGB))
        assertTrue(ColorFidelity.ColorSpace.entries.contains(ColorFidelity.ColorSpace.DCI_P3))
        assertTrue(ColorFidelity.ColorSpace.entries.contains(ColorFidelity.ColorSpace.ADOBE_RGB))
        assertTrue(ColorFidelity.ColorSpace.entries.contains(ColorFidelity.ColorSpace.PROPHOTO))
    }

    @Test
    fun `test default color params`() {
        val params = ColorFidelity.ColorParams()
        assertEquals(ColorFidelity.WhiteBalanceMode.AUTO, params.whiteBalanceMode)
        assertEquals(ColorFidelity.ColorSpace.SRGB, params.colorSpace)
        assertEquals(5500, params.colorTemperature)
        assertTrue(params.skinToneProtection)
    }

    @Test
    fun `test colorFidelity instance created`() {
        assertNotNull(colorFidelity)
    }
}
