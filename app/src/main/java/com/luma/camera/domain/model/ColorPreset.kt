package com.luma.camera.domain.model

import androidx.compose.ui.graphics.Color

/**
 * 调色预设
 * 
 * 包含预定义的调色参数组合，类似胶片风格
 * 使用新的 UI 参数模型：tempUI, expUI, satUI ∈ [-100, +100]
 */
data class ColorPreset(
    /** 唯一标识 */
    val id: String,
    
    /** 显示名称 */
    val name: String,
    
    /** 调色参数 */
    val palette: ColorPalette,
    
    /** 是否为用户自定义预设 */
    val isCustom: Boolean = false,
    
    /** 预览颜色（用于UI展示） */
    val previewColor: Color = Color(0xFFD4A574)
) {
    companion object {
        /**
         * 原图预设
         */
        val PRESET_ORIGINAL = ColorPreset(
            id = "original",
            name = "Original",
            palette = ColorPalette.DEFAULT,
            previewColor = Color(0xFFE0E0E0)
        )
        
        /**
         * 自然预设 - 轻微暖调，适度饱和
         * tempUI=+15 (略暖), expUI=0, satUI=+15 (略增饱和)
         */
        val PRESET_NATURAL = ColorPreset(
            id = "natural",
            name = "Natural",
            palette = ColorPalette(
                tempUI = 15f,
                expUI = 0f,
                satUI = 15f
            ),
            previewColor = Color(0xFFE8D4C0)
        )
        
        /**
         * 胶片预设 - 经典胶片色调
         * tempUI=-10 (略冷), expUI=+10 (略亮), satUI=-25 (降低饱和)
         */
        val PRESET_FILM = ColorPreset(
            id = "film",
            name = "Film",
            palette = ColorPalette(
                tempUI = -10f,
                expUI = 10f,
                satUI = -25f
            ),
            previewColor = Color(0xFFD4A574)
        )
        
        /**
         * 电影预设 - 青橙色调
         * tempUI=-30 (冷调), expUI=+5, satUI=+30 (高饱和)
         */
        val PRESET_CINEMA = ColorPreset(
            id = "cinema",
            name = "Cinema",
            palette = ColorPalette(
                tempUI = -30f,
                expUI = 5f,
                satUI = 30f
            ),
            previewColor = Color(0xFFC4A080)
        )
        
        /**
         * 黑白预设
         * tempUI=0, expUI=+5, satUI=-100 (完全去饱和)
         */
        val PRESET_BW = ColorPreset(
            id = "bw",
            name = "B&W",
            palette = ColorPalette(
                tempUI = 0f,
                expUI = 5f,
                satUI = -100f
            ),
            previewColor = Color(0xFF808080)
        )
        
        /**
         * 默认预设列表
         */
        fun defaultPresets(): List<ColorPreset> = listOf(
            PRESET_ORIGINAL,
            PRESET_NATURAL,
            PRESET_FILM,
            PRESET_CINEMA,
            PRESET_BW
        )
    }
}
