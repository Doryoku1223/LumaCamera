package com.luma.camera.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 动态范围优化器
 *
 * 核心职责：保留从最暗到最亮的所有层次信息
 *
 * 功能：
 * - 高光恢复：利用未过曝通道重建过曝区域的信息
 * - 暗部提升：智能提亮阴影区域，同时控制噪点放大
 * - 局部对比度增强：使用 CLAHE 或多尺度 Retinex
 * - 多帧 HDR 合成：可选的多帧融合扩展动态范围
 *
 * 目标：保留传感器 12+ 档动态范围
 *
 * 性能目标: < 200ms
 */
@Singleton
class DynamicRangeOptimizer @Inject constructor() {

    companion object {
        private const val HIGHLIGHT_THRESHOLD = 0.95f  // 高光过曝阈值
        private const val SHADOW_THRESHOLD = 0.15f      // 阴影阈值
        private const val CLAHE_CLIP_LIMIT = 2.5f      // CLAHE 裁剪限制
        private const val CLAHE_TILE_SIZE = 8          // CLAHE 分块大小
    }

    /**
     * 动态范围优化参数
     */
    data class DroParams(
        val highlightRecovery: Float = 0.8f,    // 高光恢复强度 0-1
        val shadowLift: Float = 0.5f,           // 暗部提升强度 0-1
        val localContrast: Float = 0.3f,        // 局部对比度增强 0-1
        val noiseReduction: Float = 0.2f,       // 降噪强度（暗部提升时）0-1
        val preserveHighlightColor: Boolean = true
    )

    /**
     * 处理 RGB 数据（从 ColorFidelity 输出）
     */
    suspend fun processRgbData(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DroParams = DroParams()
    ): FloatArray = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        Timber.d("Starting DRO processing: ${width}x${height}")

        // 1. 高光恢复
        val highlightRecovered = recoverHighlightsRgb(rgbData, width, height, params)

        // 2. 暗部提升
        val shadowsLifted = liftShadowsRgb(highlightRecovered, width, height, params)

        // 3. 局部对比度增强 (CLAHE)
        val contrastEnhanced = applyClahe(shadowsLifted, width, height, params)

        Timber.d("DRO processing completed in ${System.currentTimeMillis() - startTime}ms")
        contrastEnhanced
    }

    /**
     * 处理图像，优化动态范围
     */
    fun process(input: Bitmap, params: DroParams = DroParams()): Bitmap {
        val width = input.width
        val height = input.height
        val rgbData = bitmapToFloatRgb(input)

        // 同步版本处理
        val highlightRecovered = recoverHighlightsRgb(rgbData, width, height, params)
        val shadowsLifted = liftShadowsRgb(highlightRecovered, width, height, params)
        val contrastEnhanced = applyClahe(shadowsLifted, width, height, params)

        return floatRgbToBitmap(contrastEnhanced, width, height)
    }

    /**
     * 简化处理（用于 JPEG 输入）
     */
    fun processSimple(input: Bitmap): Bitmap {
        return process(input, DroParams(
            highlightRecovery = 0f,  // JPEG 已无原始数据
            shadowLift = 0.3f,
            localContrast = 0.2f
        ))
    }

    // ==================== 高光恢复 ====================

    /**
     * 高光恢复算法
     * 利用未过曝通道重建过曝信息
     */
    private fun recoverHighlightsRgb(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DroParams
    ): FloatArray {
        if (params.highlightRecovery <= 0f) return rgbData

        val result = rgbData.copyOf()
        val pixelCount = width * height

        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3]
            val g = rgbData[i * 3 + 1]
            val b = rgbData[i * 3 + 2]

            // 检测过曝通道
            val rClipped = r >= HIGHLIGHT_THRESHOLD
            val gClipped = g >= HIGHLIGHT_THRESHOLD
            val bClipped = b >= HIGHLIGHT_THRESHOLD
            val clippedCount = listOf(rClipped, gClipped, bClipped).count { it }

            if (clippedCount == 0) continue  // 无过曝

            // 计算恢复强度
            val strength = params.highlightRecovery

            when (clippedCount) {
                1 -> {
                    // 单通道过曝：使用其他通道比例估算
                    when {
                        rClipped -> {
                            val ratio = if (g > 0.01f && b > 0.01f) {
                                // 使用 G/B 估算合理的 R 值
                                val estimatedR = (g + b) / 2f * 1.1f
                                estimatedR.coerceAtMost(1.2f)
                            } else r
                            result[i * 3] = r + (ratio - r) * strength
                        }
                        gClipped -> {
                            val ratio = (r + b) / 2f
                            result[i * 3 + 1] = g + (ratio - g) * strength * 0.7f
                        }
                        bClipped -> {
                            val estimatedB = (r + g) / 2f * 0.9f
                            result[i * 3 + 2] = b + (estimatedB - b) * strength
                        }
                    }
                }
                2 -> {
                    // 两通道过曝：用未过曝通道轻微恢复
                    val factor = 1f - strength * 0.3f
                    when {
                        !rClipped -> {
                            result[i * 3 + 1] = g * factor + r * (1f - factor)
                            result[i * 3 + 2] = b * factor + r * (1f - factor)
                        }
                        !gClipped -> {
                            result[i * 3] = r * factor + g * (1f - factor)
                            result[i * 3 + 2] = b * factor + g * (1f - factor)
                        }
                        !bClipped -> {
                            result[i * 3] = r * factor + b * (1f - factor)
                            result[i * 3 + 1] = g * factor + b * (1f - factor)
                        }
                    }
                }
                // 3 通道全过曝：无法恢复，保持原样
            }
        }

        return result
    }

    // ==================== 暗部提升 ====================

    /**
     * 暗部提升算法
     * 使用自适应曲线提亮阴影，控制噪点放大
     */
    private fun liftShadowsRgb(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DroParams
    ): FloatArray {
        if (params.shadowLift <= 0f) return rgbData

        val result = FloatArray(rgbData.size)
        val pixelCount = width * height

        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3]
            val g = rgbData[i * 3 + 1]
            val b = rgbData[i * 3 + 2]

            // 计算亮度
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

            // 阴影提升权重（暗部权重高，亮部权重低）
            val shadowWeight = if (luma < SHADOW_THRESHOLD) {
                1f
            } else if (luma < 0.5f) {
                1f - (luma - SHADOW_THRESHOLD) / (0.5f - SHADOW_THRESHOLD)
            } else {
                0f
            }

            // 计算提升量
            val liftAmount = shadowWeight * params.shadowLift * 0.3f

            // 应用提升（保持色彩比例）
            if (liftAmount > 0f && luma > 0.001f) {
                val boost = 1f + liftAmount / (luma + 0.1f)
                result[i * 3] = (r * boost).coerceIn(0f, 1f)
                result[i * 3 + 1] = (g * boost).coerceIn(0f, 1f)
                result[i * 3 + 2] = (b * boost).coerceIn(0f, 1f)
            } else {
                result[i * 3] = r
                result[i * 3 + 1] = g
                result[i * 3 + 2] = b
            }
        }

        // 可选：对暗部区域应用轻微降噪
        return if (params.noiseReduction > 0f) {
            applyLightDenoise(result, width, height, params.noiseReduction)
        } else {
            result
        }
    }

    /**
     * 轻微降噪（用于暗部提升后）
     * 使用简化的双边滤波
     */
    private fun applyLightDenoise(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        strength: Float
    ): FloatArray {
        val result = rgbData.copyOf()
        val kernelSize = 3
        val spatialSigma = 1.5f
        val rangeSigma = 0.1f * strength

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = (y * width + x) * 3
                val centerR = rgbData[idx]
                val centerG = rgbData[idx + 1]
                val centerB = rgbData[idx + 2]
                val centerLuma = 0.2126f * centerR + 0.7152f * centerG + 0.0722f * centerB

                // 只对暗部应用降噪
                if (centerLuma > SHADOW_THRESHOLD) continue

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var totalWeight = 0f

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val nIdx = ((y + ky) * width + (x + kx)) * 3
                        val nR = rgbData[nIdx]
                        val nG = rgbData[nIdx + 1]
                        val nB = rgbData[nIdx + 2]

                        // 空间权重
                        val spatialDist = sqrt((kx * kx + ky * ky).toFloat())
                        val spatialWeight = exp(-spatialDist * spatialDist / (2f * spatialSigma * spatialSigma))

                        // 颜色权重
                        val colorDist = sqrt(
                            (nR - centerR).pow(2) + (nG - centerG).pow(2) + (nB - centerB).pow(2)
                        )
                        val rangeWeight = exp(-colorDist * colorDist / (2f * rangeSigma * rangeSigma))

                        val weight = spatialWeight * rangeWeight
                        sumR += nR * weight
                        sumG += nG * weight
                        sumB += nB * weight
                        totalWeight += weight
                    }
                }

                if (totalWeight > 0f) {
                    val blend = strength * 0.5f  // 混合原始值
                    result[idx] = (sumR / totalWeight) * blend + centerR * (1f - blend)
                    result[idx + 1] = (sumG / totalWeight) * blend + centerG * (1f - blend)
                    result[idx + 2] = (sumB / totalWeight) * blend + centerB * (1f - blend)
                }
            }
        }
        return result
    }

    // ==================== CLAHE 对比度增强 ====================

    /**
     * CLAHE (Contrast Limited Adaptive Histogram Equalization)
     * 局部对比度增强
     */
    private fun applyClahe(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DroParams
    ): FloatArray {
        if (params.localContrast <= 0f) return rgbData

        val result = FloatArray(rgbData.size)
        val pixelCount = width * height

        // 转换到 YUV，只处理 Y 通道
        val yData = FloatArray(pixelCount)
        val uData = FloatArray(pixelCount)
        val vData = FloatArray(pixelCount)

        // RGB to YUV
        for (i in 0 until pixelCount) {
            val r = rgbData[i * 3]
            val g = rgbData[i * 3 + 1]
            val b = rgbData[i * 3 + 2]

            yData[i] = 0.299f * r + 0.587f * g + 0.114f * b
            uData[i] = 0.492f * (b - yData[i])
            vData[i] = 0.877f * (r - yData[i])
        }

        // 对 Y 通道应用 CLAHE
        val tileWidth = width / CLAHE_TILE_SIZE
        val tileHeight = height / CLAHE_TILE_SIZE
        val yEnhanced = FloatArray(pixelCount)

        // 简化版 CLAHE：对每个区块计算局部均衡
        for (ty in 0 until CLAHE_TILE_SIZE) {
            for (tx in 0 until CLAHE_TILE_SIZE) {
                val startX = tx * tileWidth
                val startY = ty * tileHeight
                val endX = if (tx == CLAHE_TILE_SIZE - 1) width else (tx + 1) * tileWidth
                val endY = if (ty == CLAHE_TILE_SIZE - 1) height else (ty + 1) * tileHeight

                // 计算区块直方图
                val histogram = IntArray(256)
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val idx = y * width + x
                        val bin = (yData[idx] * 255f).toInt().coerceIn(0, 255)
                        histogram[bin]++
                    }
                }

                // 裁剪直方图（限制对比度增强）
                val clipLimit = (CLAHE_CLIP_LIMIT * (endX - startX) * (endY - startY) / 256f).toInt()
                var excess = 0
                for (bin in 0 until 256) {
                    if (histogram[bin] > clipLimit) {
                        excess += histogram[bin] - clipLimit
                        histogram[bin] = clipLimit
                    }
                }

                // 重新分配多余的计数
                val redistribute = excess / 256
                for (bin in 0 until 256) {
                    histogram[bin] += redistribute
                }

                // 计算 CDF
                val cdf = IntArray(256)
                cdf[0] = histogram[0]
                for (bin in 1 until 256) {
                    cdf[bin] = cdf[bin - 1] + histogram[bin]
                }
                val cdfMin = cdf.firstOrNull { it > 0 } ?: 0
                val cdfMax = cdf[255]

                // 应用均衡
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val idx = y * width + x
                        val bin = (yData[idx] * 255f).toInt().coerceIn(0, 255)
                        val equalized = if (cdfMax > cdfMin) {
                            ((cdf[bin] - cdfMin).toFloat() / (cdfMax - cdfMin).toFloat())
                        } else {
                            yData[idx]
                        }
                        // 混合原始值和均衡值
                        yEnhanced[idx] = yData[idx] * (1f - params.localContrast) + 
                                        equalized * params.localContrast
                    }
                }
            }
        }

        // YUV to RGB
        for (i in 0 until pixelCount) {
            val y = yEnhanced[i]
            val u = uData[i]
            val v = vData[i]

            result[i * 3] = (y + 1.14f * v).coerceIn(0f, 1f)
            result[i * 3 + 1] = (y - 0.395f * u - 0.581f * v).coerceIn(0f, 1f)
            result[i * 3 + 2] = (y + 2.033f * u).coerceIn(0f, 1f)
        }

        return result
    }

    // ==================== HDR 合成 ====================

    /**
     * 多帧 HDR 合成
     * 融合不同曝光的多张图片
     */
    suspend fun mergeHdr(
        frames: List<Bitmap>,
        exposures: List<Float>
    ): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) {
            return@withContext Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        if (frames.size == 1) {
            return@withContext frames[0].copy(Bitmap.Config.ARGB_8888, false)
        }

        val startTime = System.currentTimeMillis()
        Timber.d("Starting HDR merge with ${frames.size} frames")

        val width = frames[0].width
        val height = frames[0].height

        // 转换所有帧为 float RGB
        val frameData = frames.map { bitmapToFloatRgb(it) }

        // 曝光归一化因子
        val expFactors = exposures.map { 1f / it }

        // 合成
        val resultRgb = FloatArray(width * height * 3)
        val pixelCount = width * height

        for (i in 0 until pixelCount) {
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var totalWeight = 0f

            for (f in frameData.indices) {
                val r = frameData[f][i * 3]
                val g = frameData[f][i * 3 + 1]
                val b = frameData[f][i * 3 + 2]

                // 权重：避免过曝和欠曝区域
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val weight = calculateHdrWeight(luma) * expFactors[f]

                sumR += r * expFactors[f] * weight
                sumG += g * expFactors[f] * weight
                sumB += b * expFactors[f] * weight
                totalWeight += weight
            }

            if (totalWeight > 0f) {
                resultRgb[i * 3] = (sumR / totalWeight).coerceIn(0f, 1f)
                resultRgb[i * 3 + 1] = (sumG / totalWeight).coerceIn(0f, 1f)
                resultRgb[i * 3 + 2] = (sumB / totalWeight).coerceIn(0f, 1f)
            }
        }

        Timber.d("HDR merge completed in ${System.currentTimeMillis() - startTime}ms")
        floatRgbToBitmap(resultRgb, width, height)
    }

    /**
     * HDR 权重函数
     * 给予中间亮度区域更高权重
     */
    private fun calculateHdrWeight(luma: Float): Float {
        // 高斯权重，中心在 0.5
        val sigma = 0.2f
        return exp(-(luma - 0.5f).pow(2) / (2f * sigma * sigma))
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
