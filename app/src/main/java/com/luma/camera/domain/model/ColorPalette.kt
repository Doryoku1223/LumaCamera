package com.luma.camera.domain.model

import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.sign

/**
 * 调色盘参数（专业图像处理管线）
 * 
 * 用于实时调色的三个维度（UI 范围均为 -100 ~ +100）：
 * - 色温 (tempUI): 控制画面冷暖
 * - 曝光 (expUI): 控制亮度（在线性空间进行增益）
 * - 饱和度 (satUI): 控制颜色鲜艳程度（使用 OKLab 色彩空间 + 高光保护）
 * 
 * 处理顺序：sRGB→Linear → 白平衡 → 曝光增益 → 饱和度(OKLab) → Linear→sRGB
 */
data class ColorPalette(
    /**
     * 色温 UI 值
     * 范围：-100 ~ +100
     * 0 = 5500K (日光白)
     * -100 = 9000K (冷色)
     * +100 = 2500K (暖色)
     */
    val tempUI: Float = 0f,
    
    /**
     * 曝光 UI 值
     * 范围：-100 ~ +100
     * 0 = 0 EV
     * +100 = +2 EV (4x 增益)
     * -100 = -2 EV (0.25x 增益)
     */
    val expUI: Float = 0f,
    
    /**
     * 饱和度 UI 值
     * 范围：-100 ~ +100
     * 0 = 原始饱和度
     * +100 = 更鲜艳（带高光保护）
     * -100 = 更接近灰度
     */
    val satUI: Float = 0f
) {
    companion object {
        // 色温常量（Kelvin）
        const val K_WARM = 2500f    // 暖色极限
        const val K_COOL = 9000f    // 冷色极限
        const val K0 = 5500f        // 中性白点
        
        // 曝光常量
        const val EV_MAX = 2.0f     // 最大 EV 范围 (±2 EV)
        
        // 饱和度常量
        const val SAT_MAX = 0.35f   // 最大饱和度调整幅度
        const val SAT_GAMMA = 1.4f  // 饱和度 gamma 曲线（使小调整更精细）
        
        // UI 范围
        const val UI_MIN = -100f
        const val UI_MAX = 100f
        
        /**
         * 默认参数（原图）
         */
        val DEFAULT = ColorPalette()
        
        /**
         * Mired 值计算 (Micro Reciprocal Degrees)
         * mired = 1,000,000 / K
         * Mired 插值使色温变化在视觉上更均匀
         */
        fun mired(kelvin: Float): Float = 1_000_000f / kelvin
        
        /**
         * 从 Mired 转回开尔文
         */
        fun kelvinFromMired(mired: Float): Float = 1_000_000f / mired
        
        // 兼容旧 API 的常量
        const val TEMP_MIN = 2500f
        const val TEMP_MAX = 9000f
        const val DEFAULT_TEMP = 5500f
        const val SAT_MIN = -0.35f
        const val DEFAULT_SAT = 0f
        const val TONE_MIN = -1.0f
        const val TONE_MAX = 1.0f
        const val DEFAULT_TONE = 0.0f
        
        // 兼容旧 API 的转换方法
        fun normalizedToTemperature(normalized: Float): Float {
            val ui = (normalized * 2f - 1f) * 100f  // 0~1 -> -100~+100
            return ColorPalette(tempUI = ui).targetKelvin
        }
        
        fun normalizedToSaturation(normalized: Float): Float {
            return SAT_MIN + (SAT_MAX - SAT_MIN) * normalized
        }
        
        fun normalizedToTone(normalized: Float): Float {
            return TONE_MIN + (TONE_MAX - TONE_MIN) * normalized
        }
        
        /**
         * 从旧版 API 参数创建 ColorPalette
         * @param temperatureKelvin 色温 (K), 2500-9000
         * @param saturation 饱和度调整值, -0.35 ~ +0.35
         * @param tone 光影/曝光值, -1 ~ +1
         */
        fun fromLegacy(temperatureKelvin: Float, saturation: Float, tone: Float): ColorPalette {
            // 色温 → tempUI: 使用 Mired 反向计算
            val miredK0 = mired(K0)
            val miredTarget = mired(temperatureKelvin.coerceIn(K_WARM, K_COOL))
            val miredDelta = miredTarget - miredK0
            val tempUI = if (miredDelta > 0) {
                // 暖色方向
                val miredWarm = mired(K_WARM)
                (miredDelta / (miredWarm - miredK0) * 100f).coerceIn(-100f, 100f)
            } else {
                // 冷色方向
                val miredCool = mired(K_COOL)
                (miredDelta / (miredCool - miredK0) * 100f).coerceIn(-100f, 100f)
            }
            
            // 饱和度 → satUI: saturation ∈ [-0.35, 0.35] 映射到 [-100, 100]
            // 需要反向计算 gamma 曲线
            val satNorm = saturation.coerceIn(SAT_MIN, SAT_MAX) / SAT_MAX
            val satUI = if (abs(satNorm) < 0.001f) 0f else {
                sign(satNorm) * abs(satNorm).toDouble().pow(1.0 / SAT_GAMMA).toFloat() * 100f
            }
            
            // 光影 → expUI: tone ∈ [-1, 1] 映射到 [-100, 100]
            val expUI = (tone.coerceIn(-1f, 1f) * 100f)
            
            return ColorPalette(tempUI = tempUI, expUI = expUI, satUI = satUI)
        }
    }
    
    /**
     * 从 tempUI 计算目标色温 (Kelvin)
     * 使用 Mired 插值确保色温变化在视觉上更均匀
     */
    val targetKelvin: Float
        get() {
            val miredK0 = mired(K0)
            val miredTarget = if (tempUI < 0) {
                // 冷色方向：K0 → K_COOL
                val t = -tempUI / 100f
                miredK0 + t * (mired(K_COOL) - miredK0)
            } else {
                // 暖色方向：K0 → K_WARM
                val t = tempUI / 100f
                miredK0 + t * (mired(K_WARM) - miredK0)
            }
            return kelvinFromMired(miredTarget)
        }
    
    /**
     * 从 expUI 计算曝光 EV 值
     */
    val exposureEV: Float
        get() = (expUI / 100f) * EV_MAX
    
    /**
     * 从 expUI 计算曝光增益 (线性空间)
     * gain = 2^EV
     */
    val exposureGain: Float
        get() = 2f.pow(exposureEV)
    
    /**
     * 从 satUI 计算饱和度调整值
     * 使用 gamma 曲线使小调整更精细
     */
    val saturationAdjust: Float
        get() {
            val a = abs(satUI) / 100f
            return sign(satUI) * a.pow(SAT_GAMMA) * SAT_MAX
        }
    
    /**
     * 是否为默认值
     */
    fun isDefault(): Boolean {
        return abs(tempUI) < 0.5f && abs(expUI) < 0.5f && abs(satUI) < 0.5f
    }
    
    // ==================== 兼容旧 API ====================
    
    /**
     * 获取色温 (兼容旧 API)
     */
    val temperatureKelvin: Float
        get() = targetKelvin
    
    /**
     * 获取饱和度 (兼容旧 API，范围 -0.35 ~ +0.35)
     */
    val saturation: Float
        get() = saturationAdjust
    
    /**
     * 获取光影/曝光 (兼容旧 API，范围 -1 ~ +1)
     */
    val tone: Float
        get() = expUI / 100f
    
    /**
     * 转换为归一化 X 坐标 (色温 0~1)
     */
    val temperatureNormalized: Float
        get() = (tempUI - UI_MIN) / (UI_MAX - UI_MIN)
    
    /**
     * 转换为归一化 Y 坐标 (饱和度 0~1)
     */
    val saturationNormalized: Float
        get() = (satUI - UI_MIN) / (UI_MAX - UI_MIN)
    
    /**
     * 转换为归一化曝光值 (0~1)
     */
    val toneNormalized: Float
        get() = (expUI - UI_MIN) / (UI_MAX - UI_MIN)
    
    /**
     * 获取传递给 Shader 的参数
     * @return FloatArray [tempUI, expUI, satUI, targetKelvin]
     */
    fun getShaderParams(): FloatArray {
        return floatArrayOf(tempUI, expUI, satUI, targetKelvin)
    }
    
    /**
     * 获取色温的 RGB 乘数 (兼容旧 Shader)
     */
    fun getTemperatureMultipliers(): FloatArray {
        return kelvinToRgbMultipliers(targetKelvin)
    }
    
    /**
     * 获取饱和度系数 (兼容旧 Shader)
     */
    fun getSaturationFactor(): Float {
        return 1.0f + saturationAdjust
    }
    
    /**
     * 获取光影曲线系数 (兼容旧 Shader)
     */
    fun getToneFactor(): Float {
        return tone
    }
}

/**
 * 色温转 RGB 乘数（用于兼容旧 Shader）
 */
private fun kelvinToRgbMultipliers(kelvin: Float): FloatArray {
    val temp = kelvin / 100.0
    
    val red: Double
    val green: Double
    val blue: Double
    
    // 计算红色分量
    red = if (temp <= 66) {
        1.0
    } else {
        val r = temp - 60
        1.292936186 * r.pow(-0.1332047592)
    }
    
    // 计算绿色分量
    green = if (temp <= 66) {
        0.390081579 * ln(temp) - 0.631841444
    } else {
        val g = temp - 60
        1.129890861 * g.pow(-0.0755148492)
    }
    
    // 计算蓝色分量
    blue = if (temp >= 66) {
        1.0
    } else if (temp <= 19) {
        0.0
    } else {
        val b = temp - 10
        0.543206789 * ln(b) - 1.19625409
    }
    
    // 归一化，使参考白点（5500K）的乘数为 [1, 1, 1]
    val refTemp = 55.0 // 5500K / 100
    val refRed = 1.0
    val refGreen = 0.390081579 * ln(refTemp) - 0.631841444
    val refBlue = 0.543206789 * ln(refTemp - 10) - 1.19625409
    
    return floatArrayOf(
        (red / refRed).coerceIn(0.5, 2.0).toFloat(),
        (green / refGreen).coerceIn(0.5, 2.0).toFloat(),
        (blue / refBlue).coerceIn(0.5, 2.0).toFloat()
    )
}
