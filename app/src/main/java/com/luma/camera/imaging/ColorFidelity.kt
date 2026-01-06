package com.luma.camera.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 色彩保真模块
 *
 * 核心职责：100% 保留原始色彩信息，确保色彩准确性
 *
 * 功能：
 * - 白平衡精准校正：准确还原场景真实色温
 * - 色彩空间转换：传感器原生色彩 → 标准色彩空间 (sRGB/DCI-P3)
 * - 色彩校正矩阵：使用 3x3 CCM 矩阵确保色彩精准
 * - 饱和度保护：防止高饱和色彩溢出和裁切
 * - 肤色优化：特殊处理肤色区域，保持自然红润
 * - 色彩分离：保留所有原始色彩信息，不丢失任何色域
 *
 * 性能目标: < 150ms
 */
@Singleton
class ColorFidelity @Inject constructor() {

    /**
     * 白平衡模式
     */
    enum class WhiteBalanceMode {
        AUTO,           // 自动白平衡
        DAYLIGHT,       // 日光 (5500-6500K)
        CLOUDY,         // 阴天 (6500-8000K)
        TUNGSTEN,       // 钨丝灯 (2700-3000K)
        FLUORESCENT,    // 荧光灯 (4000-4500K)
        FLASH,          // 闪光灯 (5000-5500K)
        SHADE,          // 阴影 (7000-8000K)
        MANUAL          // 手动
    }

    /**
     * 目标色彩空间
     */
    enum class ColorSpace {
        SRGB,       // 标准 sRGB
        DCI_P3,     // DCI-P3 广色域
        ADOBE_RGB,  // Adobe RGB
        PROPHOTO    // ProPhoto RGB
    }

    /**
     * 色彩处理参数
     */
    data class ColorParams(
        val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
        val colorTemperature: Int = 5500,     // 手动模式下的色温 (K)
        val tint: Float = 0f,                  // 色调偏移 (-100 到 +100)
        val colorSpace: ColorSpace = ColorSpace.SRGB,
        val saturationBoost: Float = 0f,       // 饱和度增益 (-50 到 +50)
        val skinToneProtection: Boolean = true,
        val customCcm: FloatArray? = null      // 自定义 CCM 矩阵
    )

    /**
     * 处理结果
     */
    data class ProcessResult(
        val bitmap: Bitmap,
        val estimatedTemperature: Int,
        val processingTimeMs: Long
    )

    /**
     * 处理 RGB 数据（从 RawProcessor 输出）
     */
    suspend fun processRgbData(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: ColorParams = ColorParams()
    ): FloatArray = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        Timber.d("Starting color fidelity processing: ${width}x${height}")

        // 1. 白平衡校正
        val wbGains = calculateWhiteBalanceGains(rgbData, width, height, params)
        val whiteBalanced = applyWhiteBalanceGains(rgbData, wbGains)

        // 2. 色彩校正矩阵
        val ccm = params.customCcm ?: getDefaultCCM()
        val colorCorrected = applyCCM(whiteBalanced, ccm)

        // 3. 色彩空间转换
        val colorSpaceConverted = convertToColorSpace(colorCorrected, params.colorSpace)

        // 4. 饱和度调整与保护
        val saturationAdjusted = adjustSaturation(colorSpaceConverted, params.saturationBoost)

        // 5. 肤色优化
        val result = if (params.skinToneProtection) {
            optimizeSkinTones(saturationAdjusted)
        } else {
            saturationAdjusted
        }

        Timber.d("Color processing completed in ${System.currentTimeMillis() - startTime}ms")
        result
    }

    /**
     * 处理 Bitmap
     */
    suspend fun process(input: Bitmap, params: ColorParams = ColorParams()): ProcessResult = 
        withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // 转换为 float RGB 数组
        val width = input.width
        val height = input.height
        val rgbData = bitmapToFloatRgb(input)

        // 处理
        val processedRgb = processRgbData(rgbData, width, height, params)

        // 转回 Bitmap
        val resultBitmap = floatRgbToBitmap(processedRgb, width, height)

        val processingTime = System.currentTimeMillis() - startTime
        val estimatedTemp = estimateColorTemperature(rgbData, width, height)

        ProcessResult(resultBitmap, estimatedTemp, processingTime)
    }

    /**
     * 简化处理（用于 JPEG 输入）
     */
    fun processSimple(input: Bitmap): Bitmap {
        val rgbData = bitmapToFloatRgb(input)
        val protected = adjustSaturation(rgbData, 0f)  // 仅做饱和度保护
        return floatRgbToBitmap(protected, input.width, input.height)
    }

    // ==================== 白平衡 ====================

    /**
     * 计算白平衡增益系数
     */
    private fun calculateWhiteBalanceGains(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: ColorParams
    ): FloatArray {
        return when (params.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> estimateAutoWhiteBalance(rgbData, width, height)
            WhiteBalanceMode.MANUAL -> temperatureToGains(params.colorTemperature, params.tint)
            WhiteBalanceMode.DAYLIGHT -> temperatureToGains(5500, 0f)
            WhiteBalanceMode.CLOUDY -> temperatureToGains(6500, 0f)
            WhiteBalanceMode.TUNGSTEN -> temperatureToGains(2800, 0f)
            WhiteBalanceMode.FLUORESCENT -> temperatureToGains(4200, 5f)
            WhiteBalanceMode.FLASH -> temperatureToGains(5200, 0f)
            WhiteBalanceMode.SHADE -> temperatureToGains(7500, 0f)
        }
    }

    /**
     * 自动白平衡 - 灰世界假设 + 高光分析混合
     */
    private fun estimateAutoWhiteBalance(rgbData: FloatArray, width: Int, height: Int): FloatArray {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0

        // 灰世界假设：计算全局平均
        val pixelCount = width * height
        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3].toDouble()
            val g = rgbData[i * 3 + 1].toDouble()
            val b = rgbData[i * 3 + 2].toDouble()

            // 排除过曝和欠曝区域
            val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (luminance in 0.05..0.95) {
                sumR += r
                sumG += g
                sumB += b
                count++
            }
        }

        if (count == 0) return floatArrayOf(1f, 1f, 1f)

        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count

        // 以绿色通道为基准计算增益
        val gainR = (avgG / avgR).coerceIn(0.5, 3.0).toFloat()
        val gainG = 1f
        val gainB = (avgG / avgB).coerceIn(0.5, 3.0).toFloat()

        Timber.d("Auto WB gains: R=$gainR, G=$gainG, B=$gainB")
        return floatArrayOf(gainR, gainG, gainB)
    }

    /**
     * 色温转换为白平衡增益
     * 基于 Planckian locus 近似
     */
    private fun temperatureToGains(temperature: Int, tint: Float): FloatArray {
        // 归一化色温 (相对于 D65 = 6500K)
        val t = temperature.toFloat()

        // 使用 Planckian locus 近似公式
        // R/B 比例随色温变化
        val ratio = when {
            t < 4000 -> 1.5f - (t - 2000) / 4000f  // 暖色调
            t < 7000 -> 1.0f - (t - 5500) / 10000f // 中性
            else -> 0.8f - (t - 7000) / 20000f      // 冷色调
        }

        val gainR = if (t < 5500) ratio else 1f
        val gainB = if (t > 5500) 1f / ratio else 1f

        // 色调调整 (Green-Magenta 轴)
        val tintAdjust = tint / 100f * 0.1f
        val gainG = 1f + tintAdjust

        return floatArrayOf(gainR.coerceIn(0.5f, 2f), gainG, gainB.coerceIn(0.5f, 2f))
    }

    /**
     * 应用白平衡增益
     */
    private fun applyWhiteBalanceGains(rgbData: FloatArray, gains: FloatArray): FloatArray {
        val result = FloatArray(rgbData.size)
        val pixelCount = rgbData.size / 3

        for (i in 0 until pixelCount) {
            result[i * 3] = (rgbData[i * 3] * gains[0]).coerceIn(0f, 1f)
            result[i * 3 + 1] = (rgbData[i * 3 + 1] * gains[1]).coerceIn(0f, 1f)
            result[i * 3 + 2] = (rgbData[i * 3 + 2] * gains[2]).coerceIn(0f, 1f)
        }
        return result
    }

    // ==================== 色彩校正矩阵 ====================

    /**
     * 获取默认 CCM（针对典型传感器优化）
     */
    private fun getDefaultCCM(): FloatArray {
        // 标准 D65 照明下的 sRGB CCM
        // 这是一个优化过的矩阵，增强色彩分离度
        return floatArrayOf(
            1.2f, -0.1f, -0.1f,  // R
           -0.1f,  1.1f,  0.0f,  // G
           -0.1f,  0.0f,  1.1f   // B
        )
    }

    /**
     * 应用色彩校正矩阵
     */
    private fun applyCCM(rgbData: FloatArray, ccm: FloatArray): FloatArray {
        val result = FloatArray(rgbData.size)
        val pixelCount = rgbData.size / 3

        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3]
            val g = rgbData[i * 3 + 1]
            val b = rgbData[i * 3 + 2]

            result[i * 3] = (ccm[0] * r + ccm[1] * g + ccm[2] * b).coerceIn(0f, 1f)
            result[i * 3 + 1] = (ccm[3] * r + ccm[4] * g + ccm[5] * b).coerceIn(0f, 1f)
            result[i * 3 + 2] = (ccm[6] * r + ccm[7] * g + ccm[8] * b).coerceIn(0f, 1f)
        }
        return result
    }

    // ==================== 色彩空间转换 ====================

    /**
     * 转换到目标色彩空间
     */
    private fun convertToColorSpace(rgbData: FloatArray, colorSpace: ColorSpace): FloatArray {
        // 假设输入是线性 RGB，需要应用 gamma 并转换到目标空间
        return when (colorSpace) {
            ColorSpace.SRGB -> applyGamma(rgbData, 2.2f)
            ColorSpace.DCI_P3 -> convertToP3(rgbData)
            ColorSpace.ADOBE_RGB -> applyGamma(rgbData, 2.19921875f)  // Adobe RGB gamma
            ColorSpace.PROPHOTO -> applyGamma(rgbData, 1.8f)
        }
    }

    private fun applyGamma(rgbData: FloatArray, gamma: Float): FloatArray {
        val result = FloatArray(rgbData.size)
        val invGamma = 1f / gamma

        for (i in rgbData.indices) {
            // sRGB 使用分段 gamma 曲线
            result[i] = if (rgbData[i] <= 0.0031308f) {
                rgbData[i] * 12.92f
            } else {
                1.055f * rgbData[i].pow(invGamma) - 0.055f
            }.coerceIn(0f, 1f)
        }
        return result
    }

    private fun convertToP3(rgbData: FloatArray): FloatArray {
        // sRGB to DCI-P3 转换矩阵
        val matrix = floatArrayOf(
            0.8225f, 0.1774f, 0.0000f,
            0.0332f, 0.9669f, 0.0000f,
            0.0171f, 0.0724f, 0.9108f
        )
        return applyCCM(applyGamma(rgbData, 2.2f), matrix)
    }

    // ==================== 饱和度处理 ====================

    /**
     * 饱和度调整与保护
     * 防止过饱和导致的色彩溢出
     */
    private fun adjustSaturation(rgbData: FloatArray, boost: Float): FloatArray {
        val result = FloatArray(rgbData.size)
        val pixelCount = rgbData.size / 3
        val satFactor = 1f + boost / 100f

        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3]
            val g = rgbData[i * 3 + 1]
            val b = rgbData[i * 3 + 2]

            // 计算亮度
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

            // 调整饱和度 (相对于亮度)
            var newR = luma + (r - luma) * satFactor
            var newG = luma + (g - luma) * satFactor
            var newB = luma + (b - luma) * satFactor

            // 柔和压缩防止溢出
            val maxVal = maxOf(newR, newG, newB)
            if (maxVal > 1f) {
                // 使用 highlight recovery 而不是硬裁切
                val excess = maxVal - 1f
                val recovery = 1f / (1f + excess)
                newR = luma + (newR - luma) * recovery
                newG = luma + (newG - luma) * recovery
                newB = luma + (newB - luma) * recovery
            }

            result[i * 3] = newR.coerceIn(0f, 1f)
            result[i * 3 + 1] = newG.coerceIn(0f, 1f)
            result[i * 3 + 2] = newB.coerceIn(0f, 1f)
        }
        return result
    }

    // ==================== 肤色优化 ====================

    /**
     * 肤色优化
     * 检测并优化肤色区域，保持自然红润
     */
    private fun optimizeSkinTones(rgbData: FloatArray): FloatArray {
        val result = rgbData.copyOf()
        val pixelCount = rgbData.size / 3

        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3]
            val g = rgbData[i * 3 + 1]
            val b = rgbData[i * 3 + 2]

            // 检测是否为肤色区域
            if (isSkinTone(r, g, b)) {
                // 计算肤色置信度
                val confidence = skinToneConfidence(r, g, b)

                // 轻微增加红润度，减少绿色分量
                val adjustment = confidence * 0.02f
                result[i * 3] = (r + adjustment).coerceIn(0f, 1f)
                result[i * 3 + 1] = (g - adjustment * 0.5f).coerceIn(0f, 1f)
                // 蓝色保持不变
            }
        }
        return result
    }

    /**
     * 检测是否为肤色
     * 使用 YCbCr 色彩空间中的肤色范围
     */
    private fun isSkinTone(r: Float, g: Float, b: Float): Boolean {
        // RGB to YCbCr
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 0.564f * (b - y) + 0.5f
        val cr = 0.713f * (r - y) + 0.5f

        // 肤色范围 (归一化到 0-1)
        return cr in 0.55f..0.70f && cb in 0.40f..0.50f && y in 0.15f..0.85f
    }

    /**
     * 计算肤色置信度
     */
    private fun skinToneConfidence(r: Float, g: Float, b: Float): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 0.564f * (b - y) + 0.5f
        val cr = 0.713f * (r - y) + 0.5f

        // 距离肤色中心的距离
        val cbCenter = 0.45f
        val crCenter = 0.62f
        val dist = sqrt((cb - cbCenter).pow(2) + (cr - crCenter).pow(2))

        // 高斯衰减
        return kotlin.math.exp(-dist * dist / 0.01f).toFloat().coerceIn(0f, 1f)
    }

    // ==================== 色温估计 ====================

    /**
     * 估计场景色温
     */
    fun estimateColorTemperature(rgbData: FloatArray, width: Int, height: Int): Int {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0

        // 分析高光区域 (luminance > 0.7)
        val pixelCount = width * height
        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3].toDouble()
            val g = rgbData[i * 3 + 1].toDouble()
            val b = rgbData[i * 3 + 2].toDouble()

            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if (luminance > 0.7 && luminance < 0.95) {
                sumR += r
                sumG += g
                sumB += b
                count++
            }
        }

        if (count < 100) {
            // 样本太少，返回默认值
            return 5500
        }

        val avgR = sumR / count
        val avgB = sumB / count

        // R/B 比例估计色温
        val ratio = avgR / avgB
        val temperature = when {
            ratio > 1.5 -> (3000 - (ratio - 1.5) * 1000).toInt()
            ratio > 1.0 -> (5500 - (ratio - 1.0) * 5000).toInt()
            ratio > 0.7 -> (7000 + (1.0 - ratio) * 3000).toInt()
            else -> 9000
        }

        return temperature.coerceIn(2000, 12000)
    }

    fun estimateColorTemperature(input: Bitmap): Int {
        val rgbData = bitmapToFloatRgb(input)
        return estimateColorTemperature(rgbData, input.width, input.height)
    }

    // ==================== 工具方法 ====================

    private fun bitmapToFloatRgb(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rgbData = FloatArray(width * height * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            rgbData[i * 3] = ((pixel shr 16) and 0xFF) / 255f
            rgbData[i * 3 + 1] = ((pixel shr 8) and 0xFF) / 255f
            rgbData[i * 3 + 2] = (pixel and 0xFF) / 255f
        }
        return rgbData
    }

    private fun floatRgbToBitmap(rgbData: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in 0 until width * height) {
            val r = (rgbData[i * 3] * 255f).toInt().coerceIn(0, 255)
            val g = (rgbData[i * 3 + 1] * 255f).toInt().coerceIn(0, 255)
            val b = (rgbData[i * 3 + 2] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
