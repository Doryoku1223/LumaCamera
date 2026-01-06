package com.luma.camera.imaging

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图像质量分析器
 *
 * 职责：
 * - 自动检测曝光是否正确
 * - 评估动态范围利用率
 * - 噪点水平评估
 * - 清晰度/锐度评估
 * - 根据分析结果自动调整处理策略
 */
@Singleton
class ImageQualityAnalyzer @Inject constructor() {

    /**
     * 质量报告
     */
    data class QualityReport(
        val exposure: ExposureAnalysis,
        val dynamicRange: DynamicRangeAnalysis,
        val noise: NoiseAnalysis,
        val sharpness: SharpnessAnalysis,
        val overallScore: Float  // 0-100
    )

    data class ExposureAnalysis(
        val isCorrect: Boolean,
        val meanBrightness: Float,  // 0-1
        val isOverexposed: Boolean,
        val isUnderexposed: Boolean,
        val overexposedPercentage: Float,
        val underexposedPercentage: Float
    )

    data class DynamicRangeAnalysis(
        val utilizationPercentage: Float,  // 动态范围利用率
        val highlightClipping: Float,       // 高光裁切比例
        val shadowClipping: Float,          // 暗部裁切比例
        val estimatedStops: Float           // 估计的档数
    )

    data class NoiseAnalysis(
        val level: NoiseLevel,
        val estimatedIso: Int?,
        val snrDb: Float  // 信噪比 (dB)
    )

    enum class NoiseLevel {
        VERY_LOW,   // 非常低 (ISO < 200)
        LOW,        // 低 (ISO 200-800)
        MEDIUM,     // 中等 (ISO 800-1600)
        HIGH,       // 高 (ISO 1600-3200)
        VERY_HIGH   // 非常高 (ISO > 3200)
    }

    data class SharpnessAnalysis(
        val score: Float,  // 0-100
        val isFocused: Boolean,
        val hasCameraShake: Boolean
    )

    /**
     * 分析图像质量
     */
    fun analyze(input: Bitmap): QualityReport {
        val exposure = analyzeExposure(input)
        val dynamicRange = analyzeDynamicRange(input)
        val noise = analyzeNoise(input)
        val sharpness = analyzeSharpness(input)

        // 计算综合评分
        val overallScore = calculateOverallScore(exposure, dynamicRange, noise, sharpness)

        return QualityReport(
            exposure = exposure,
            dynamicRange = dynamicRange,
            noise = noise,
            sharpness = sharpness,
            overallScore = overallScore
        )
    }

    /**
     * 分析曝光
     */
    private fun analyzeExposure(input: Bitmap): ExposureAnalysis {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

        var sumBrightness = 0f
        var overexposedCount = 0
        var underexposedCount = 0

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // 计算亮度 (Rec. 709)
            val brightness = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            sumBrightness += brightness

            // 检测过曝（任一通道接近 255）
            if (r > 250 || g > 250 || b > 250) {
                overexposedCount++
            }

            // 检测欠曝（所有通道都很暗）
            if (r < 5 && g < 5 && b < 5) {
                underexposedCount++
            }
        }

        val totalPixels = pixels.size.toFloat()
        val meanBrightness = sumBrightness / totalPixels
        val overexposedPct = overexposedCount / totalPixels
        val underexposedPct = underexposedCount / totalPixels

        return ExposureAnalysis(
            isCorrect = meanBrightness in 0.35f..0.65f && overexposedPct < 0.02f,
            meanBrightness = meanBrightness,
            isOverexposed = overexposedPct > 0.05f,
            isUnderexposed = meanBrightness < 0.25f,
            overexposedPercentage = overexposedPct * 100,
            underexposedPercentage = underexposedPct * 100
        )
    }

    /**
     * 分析动态范围
     */
    private fun analyzeDynamicRange(input: Bitmap): DynamicRangeAnalysis {
        // TODO: 更精确的动态范围分析
        val histogram = IntArray(256)
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = ((r + g + b) / 3)
            histogram[brightness]++
        }

        // 计算直方图的使用范围
        var minUsed = 0
        var maxUsed = 255
        val threshold = pixels.size * 0.001f // 0.1% 阈值

        for (i in 0..255) {
            if (histogram[i] > threshold) {
                minUsed = i
                break
            }
        }
        for (i in 255 downTo 0) {
            if (histogram[i] > threshold) {
                maxUsed = i
                break
            }
        }

        val utilization = (maxUsed - minUsed) / 255f * 100
        val highlightClip = histogram.takeLast(5).sum() / pixels.size.toFloat()
        val shadowClip = histogram.take(5).sum() / pixels.size.toFloat()

        return DynamicRangeAnalysis(
            utilizationPercentage = utilization,
            highlightClipping = highlightClip * 100,
            shadowClipping = shadowClip * 100,
            estimatedStops = (utilization / 100 * 12).coerceIn(0f, 14f)
        )
    }

    /**
     * 分析噪点
     */
    private fun analyzeNoise(input: Bitmap): NoiseAnalysis {
        // TODO: 更精确的噪点分析（局部方差法）
        return NoiseAnalysis(
            level = NoiseLevel.LOW,
            estimatedIso = null,
            snrDb = 40f
        )
    }

    /**
     * 分析锐度
     */
    private fun analyzeSharpness(input: Bitmap): SharpnessAnalysis {
        // TODO: 使用拉普拉斯算子计算锐度
        return SharpnessAnalysis(
            score = 75f,
            isFocused = true,
            hasCameraShake = false
        )
    }

    /**
     * 计算综合评分
     */
    private fun calculateOverallScore(
        exposure: ExposureAnalysis,
        dynamicRange: DynamicRangeAnalysis,
        noise: NoiseAnalysis,
        sharpness: SharpnessAnalysis
    ): Float {
        var score = 50f

        // 曝光权重 30%
        if (exposure.isCorrect) score += 15f
        score -= exposure.overexposedPercentage * 0.5f
        score -= exposure.underexposedPercentage * 0.3f

        // 动态范围权重 25%
        score += dynamicRange.utilizationPercentage * 0.15f

        // 噪点权重 20%
        score += when (noise.level) {
            NoiseLevel.VERY_LOW -> 10f
            NoiseLevel.LOW -> 7f
            NoiseLevel.MEDIUM -> 4f
            NoiseLevel.HIGH -> 0f
            NoiseLevel.VERY_HIGH -> -5f
        }

        // 锐度权重 25%
        score += sharpness.score * 0.15f

        return score.coerceIn(0f, 100f)
    }
}
