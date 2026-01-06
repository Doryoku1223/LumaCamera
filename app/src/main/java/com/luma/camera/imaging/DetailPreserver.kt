package com.luma.camera.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 细节保留模块
 *
 * 核心职责：最大程度保留图像的细节信息
 *
 * 功能：
 * - 边缘感知去噪：保留边缘的同时降低噪点
 * - 纹理保护：识别并保护毛发、织物等精细纹理区域
 * - 解析力增强：提升图像锐度但不产生振铃效应
 * - 细节频率分离：分离高频细节和低频色块，独立处理后合并
 *
 * 性能目标: < 200ms
 */
@Singleton
class DetailPreserver @Inject constructor() {

    companion object {
        private const val BILATERAL_SPATIAL_SIGMA = 3.0f
        private const val BILATERAL_RANGE_SIGMA = 0.1f
        private const val TEXTURE_VARIANCE_THRESHOLD = 0.02f
        private const val DEFAULT_SHARPEN_AMOUNT = 0.5f
        private const val SHARPEN_RADIUS = 1
    }

    /**
     * 细节处理参数
     */
    data class DetailParams(
        val denoiseStrength: Float = 0.5f,      // 去噪强度 0-1
        val textureProtection: Float = 0.7f,    // 纹理保护强度 0-1
        val sharpenAmount: Float = 0.3f,        // 锐化强度 0-1
        val sharpenRadius: Int = 1,             // 锐化半径 1-3
        val sharpenThreshold: Float = 0.01f,    // 锐化阈值（避免锐化噪点）
        val edgePreservation: Float = 0.8f      // 边缘保护强度 0-1
    )

    /**
     * 处理 RGB 数据
     */
    suspend fun processRgbData(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DetailParams = DetailParams()
    ): FloatArray = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        Timber.d("Starting detail preservation: ${width}x${height}")

        // 1. 边缘感知去噪（双边滤波）
        val denoised = applyBilateralFilter(rgbData, width, height, params)

        // 2. 纹理保护
        val textureProtected = protectTexturesRgb(denoised, rgbData, width, height, params)

        // 3. 锐化增强
        val sharpened = applyUnsharpMask(textureProtected, width, height, params)

        Timber.d("Detail preservation completed in ${System.currentTimeMillis() - startTime}ms")
        sharpened
    }

    /**
     * 处理图像，保留细节
     */
    fun process(input: Bitmap, params: DetailParams = DetailParams()): Bitmap {
        val width = input.width
        val height = input.height
        val rgbData = bitmapToFloatRgb(input)

        val denoised = applyBilateralFilter(rgbData, width, height, params)
        val textureProtected = protectTexturesRgb(denoised, rgbData, width, height, params)
        val sharpened = applyUnsharpMask(textureProtected, width, height, params)

        return floatRgbToBitmap(sharpened, width, height)
    }

    // ==================== 双边滤波去噪 ====================

    /**
     * 双边滤波
     * 边缘感知去噪，保留边缘的同时降低噪点
     */
    private fun applyBilateralFilter(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DetailParams
    ): FloatArray {
        if (params.denoiseStrength <= 0f) return rgbData

        val result = FloatArray(rgbData.size)
        val radius = 2
        val spatialSigma = BILATERAL_SPATIAL_SIGMA * params.denoiseStrength
        val rangeSigma = BILATERAL_RANGE_SIGMA * params.denoiseStrength

        // 预计算空间权重
        val spatialWeights = Array(radius * 2 + 1) { dy ->
            FloatArray(radius * 2 + 1) { dx ->
                val dist = sqrt(((dx - radius) * (dx - radius) + (dy - radius) * (dy - radius)).toFloat())
                exp(-dist * dist / (2f * spatialSigma * spatialSigma))
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = (y * width + x) * 3
                val centerR = rgbData[idx]
                val centerG = rgbData[idx + 1]
                val centerB = rgbData[idx + 2]

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var totalWeight = 0f

                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue

                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue

                        val nIdx = (ny * width + nx) * 3
                        val nR = rgbData[nIdx]
                        val nG = rgbData[nIdx + 1]
                        val nB = rgbData[nIdx + 2]

                        // 空间权重
                        val spatialWeight = spatialWeights[dy + radius][dx + radius]

                        // 颜色权重（基于颜色差异）
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
                    result[idx] = sumR / totalWeight
                    result[idx + 1] = sumG / totalWeight
                    result[idx + 2] = sumB / totalWeight
                } else {
                    result[idx] = centerR
                    result[idx + 1] = centerG
                    result[idx + 2] = centerB
                }
            }
        }

        return result
    }

    // ==================== 纹理保护 ====================

    /**
     * 纹理保护
     * 识别并保护高细节纹理区域
     */
    private fun protectTexturesRgb(
        processedData: FloatArray,
        originalData: FloatArray,
        width: Int,
        height: Int,
        params: DetailParams
    ): FloatArray {
        if (params.textureProtection <= 0f) return processedData

        val result = FloatArray(processedData.size)
        val windowSize = 3

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = (y * width + x) * 3

                // 计算局部方差（纹理检测）
                val variance = calculateLocalVariance(originalData, width, height, x, y, windowSize)

                // 方差越高，保留原始数据越多
                val textureWeight = if (variance > TEXTURE_VARIANCE_THRESHOLD) {
                    val excess = (variance - TEXTURE_VARIANCE_THRESHOLD) / TEXTURE_VARIANCE_THRESHOLD
                    (excess * params.textureProtection).coerceIn(0f, 1f)
                } else {
                    0f
                }

                // 混合处理后和原始数据
                result[idx] = processedData[idx] * (1f - textureWeight) + originalData[idx] * textureWeight
                result[idx + 1] = processedData[idx + 1] * (1f - textureWeight) + originalData[idx + 1] * textureWeight
                result[idx + 2] = processedData[idx + 2] * (1f - textureWeight) + originalData[idx + 2] * textureWeight
            }
        }

        return result
    }

    /**
     * 计算局部方差
     */
    private fun calculateLocalVariance(
        data: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        windowSize: Int
    ): Float {
        val halfWindow = windowSize / 2
        var sum = 0f
        var sumSq = 0f
        var count = 0

        for (dy in -halfWindow..halfWindow) {
            val ny = y + dy
            if (ny < 0 || ny >= height) continue

            for (dx in -halfWindow..halfWindow) {
                val nx = x + dx
                if (nx < 0 || nx >= width) continue

                val idx = (ny * width + nx) * 3
                // 使用亮度计算方差
                val luma = 0.2126f * data[idx] + 0.7152f * data[idx + 1] + 0.0722f * data[idx + 2]
                sum += luma
                sumSq += luma * luma
                count++
            }
        }

        if (count < 2) return 0f

        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return variance.coerceAtLeast(0f)
    }

    // ==================== 锐化增强 ====================

    /**
     * Unsharp Mask 锐化
     * 公式：Sharpened = Original + (Original - Blurred) * Amount
     */
    private fun applyUnsharpMask(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        params: DetailParams
    ): FloatArray {
        if (params.sharpenAmount <= 0f) return rgbData

        // 1. 创建模糊版本
        val blurred = gaussianBlur(rgbData, width, height, params.sharpenRadius)

        // 2. 计算高频细节并增强
        val result = FloatArray(rgbData.size)
        val amount = params.sharpenAmount

        for (i in 0 until width * height) {
            val idx = i * 3

            // 计算细节（原始 - 模糊）
            val detailR = rgbData[idx] - blurred[idx]
            val detailG = rgbData[idx + 1] - blurred[idx + 1]
            val detailB = rgbData[idx + 2] - blurred[idx + 2]

            // 计算细节幅度
            val detailMag = sqrt(detailR * detailR + detailG * detailG + detailB * detailB)

            // 阈值处理（避免锐化噪点）
            val factor = if (detailMag > params.sharpenThreshold) {
                amount
            } else {
                amount * (detailMag / params.sharpenThreshold)
            }

            // 应用锐化
            result[idx] = (rgbData[idx] + detailR * factor).coerceIn(0f, 1f)
            result[idx + 1] = (rgbData[idx + 1] + detailG * factor).coerceIn(0f, 1f)
            result[idx + 2] = (rgbData[idx + 2] + detailB * factor).coerceIn(0f, 1f)
        }

        return result
    }

    /**
     * 高斯模糊
     */
    private fun gaussianBlur(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ): FloatArray {
        // 使用可分离的高斯核进行两遍模糊
        val sigma = radius.toFloat()
        val kernelSize = radius * 2 + 1

        // 创建一维高斯核
        val kernel = FloatArray(kernelSize)
        var kernelSum = 0f
        for (i in 0 until kernelSize) {
            val x = (i - radius).toFloat()
            kernel[i] = exp(-x * x / (2f * sigma * sigma))
            kernelSum += kernel[i]
        }
        // 归一化
        for (i in 0 until kernelSize) {
            kernel[i] /= kernelSum
        }

        // 水平模糊
        val tempData = FloatArray(rgbData.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f

                for (k in -radius..radius) {
                    val nx = (x + k).coerceIn(0, width - 1)
                    val nIdx = (y * width + nx) * 3
                    val w = kernel[k + radius]
                    sumR += rgbData[nIdx] * w
                    sumG += rgbData[nIdx + 1] * w
                    sumB += rgbData[nIdx + 2] * w
                }

                val idx = (y * width + x) * 3
                tempData[idx] = sumR
                tempData[idx + 1] = sumG
                tempData[idx + 2] = sumB
            }
        }

        // 垂直模糊
        val result = FloatArray(rgbData.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f

                for (k in -radius..radius) {
                    val ny = (y + k).coerceIn(0, height - 1)
                    val nIdx = (ny * width + x) * 3
                    val w = kernel[k + radius]
                    sumR += tempData[nIdx] * w
                    sumG += tempData[nIdx + 1] * w
                    sumB += tempData[nIdx + 2] * w
                }

                val idx = (y * width + x) * 3
                result[idx] = sumR
                result[idx + 1] = sumG
                result[idx + 2] = sumB
            }
        }

        return result
    }

    // ==================== 频率分离 ====================

    /**
     * 频率分离
     * 将图像分离为高频（细节）和低频（色块）层
     */
    suspend fun separateFrequencies(
        rgbData: FloatArray,
        width: Int,
        height: Int,
        blurRadius: Int = 5
    ): Pair<FloatArray, FloatArray> = withContext(Dispatchers.Default) {
        // 低频 = 高斯模糊
        val lowFreq = gaussianBlur(rgbData, width, height, blurRadius)

        // 高频 = 原图 - 低频 + 0.5（为了可视化）
        val highFreq = FloatArray(rgbData.size)
        for (i in rgbData.indices) {
            highFreq[i] = (rgbData[i] - lowFreq[i] + 0.5f).coerceIn(0f, 1f)
        }

        Pair(highFreq, lowFreq)
    }

    /**
     * 合并频率分离的图层
     */
    fun mergeFrequencies(
        highFreq: FloatArray,
        lowFreq: FloatArray
    ): FloatArray {
        val result = FloatArray(highFreq.size)
        for (i in highFreq.indices) {
            // 高频层以 0.5 为中性，所以减去 0.5
            result[i] = (lowFreq[i] + (highFreq[i] - 0.5f)).coerceIn(0f, 1f)
        }
        return result
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
