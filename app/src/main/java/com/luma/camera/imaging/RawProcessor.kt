package com.luma.camera.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/**
 * RAW 处理器
 *
 * 职责：
 * - Bayer 阵列去马赛克
 * - 黑电平校正
 * - 白电平标定
 * - 坏点检测与修复
 *
 * 性能目标: < 300ms (5000万像素)
 */
@Singleton
class RawProcessor @Inject constructor() {

    /**
     * Bayer 模式
     */
    enum class BayerPattern {
        RGGB,  // Red-Green-Green-Blue
        BGGR,  // Blue-Green-Green-Red
        GRBG,  // Green-Red-Blue-Green
        GBRG   // Green-Blue-Red-Green
    }

    /**
     * 去马赛克算法
     */
    enum class DemosaicMethod {
        BILINEAR,  // 双线性插值 (最快，质量一般)
        VNG,       // Variable Number of Gradients (平衡)
        AHD,       // Adaptive Homogeneity-Directed (最佳质量)
        DCB        // DCB 算法
    }

    /**
     * RAW 处理结果
     */
    data class ProcessResult(
        val rgbData: FloatArray,  // RGB interleaved, 归一化到 0-1
        val width: Int,
        val height: Int,
        val processingTimeMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProcessResult) return false
            return rgbData.contentEquals(other.rgbData) && width == other.width && height == other.height
        }
        override fun hashCode(): Int = rgbData.contentHashCode()
    }

    /**
     * 处理 RAW 数据
     *
     * @param rawData RAW Bayer 数据
     * @param width 图像宽度
     * @param height 图像高度
     * @param bayerPattern Bayer 模式
     * @param bitDepth 位深度（通常 10, 12, 14）
     * @param blackLevel 每通道黑电平 [R, Gr, Gb, B]
     * @param whiteLevel 白电平值
     * @param method 去马赛克算法
     * @return 处理后的 RGB 数据
     */
    suspend fun process(
        rawData: ByteArray,
        width: Int,
        height: Int,
        bayerPattern: BayerPattern = BayerPattern.RGGB,
        bitDepth: Int = 14,
        blackLevel: FloatArray = floatArrayOf(64f, 64f, 64f, 64f),
        whiteLevel: Float = 16383f,
        method: DemosaicMethod = DemosaicMethod.VNG
    ): ProcessResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        Timber.d("Starting RAW processing: ${width}x${height}, $bayerPattern, $bitDepth-bit, method=$method")

        // 1. 将 RAW 数据转换为 Float 数组
        val rawFloat = unpackRawData(rawData, bitDepth)

        // 2. 黑电平校正 + 白电平归一化
        val correctedData = applyLevelCorrection(rawFloat, width, height, bayerPattern, blackLevel, whiteLevel)

        // 3. 坏点检测与修复
        val cleanData = fixDeadPixels(correctedData, width, height)

        // 4. 去马赛克
        val rgbData = when (method) {
            DemosaicMethod.BILINEAR -> demosaicBilinear(cleanData, width, height, bayerPattern)
            DemosaicMethod.VNG -> demosaicVNG(cleanData, width, height, bayerPattern)
            DemosaicMethod.AHD -> demosaicAHD(cleanData, width, height, bayerPattern)
            DemosaicMethod.DCB -> demosaicDCB(cleanData, width, height, bayerPattern)
        }

        val processingTime = System.currentTimeMillis() - startTime
        Timber.d("RAW processing completed in ${processingTime}ms")

        ProcessResult(rgbData, width, height, processingTime)
    }

    /**
     * 处理 RAW 数据并返回 Bitmap
     */
    suspend fun processToBitmap(
        rawData: ByteArray,
        width: Int,
        height: Int,
        bayerPattern: BayerPattern = BayerPattern.RGGB,
        bitDepth: Int = 14,
        blackLevel: FloatArray = floatArrayOf(64f, 64f, 64f, 64f),
        whiteLevel: Float = 16383f,
        method: DemosaicMethod = DemosaicMethod.VNG
    ): Bitmap = withContext(Dispatchers.Default) {
        val result = process(rawData, width, height, bayerPattern, bitDepth, blackLevel, whiteLevel, method)
        rgbToBitmap(result.rgbData, result.width, result.height)
    }

    // ==================== 数据解包 ====================

    /**
     * 解包 RAW 数据
     * 支持 10/12/14/16 位打包格式
     */
    private fun unpackRawData(rawData: ByteArray, bitDepth: Int): FloatArray {
        return when (bitDepth) {
            10 -> unpack10Bit(rawData)
            12 -> unpack12Bit(rawData)
            14 -> unpack14Bit(rawData)
            16 -> unpack16Bit(rawData)
            else -> {
                Timber.w("Unsupported bit depth: $bitDepth, treating as 16-bit")
                unpack16Bit(rawData)
            }
        }
    }

    private fun unpack10Bit(data: ByteArray): FloatArray {
        // 10-bit: 4 pixels packed in 5 bytes
        val pixelCount = (data.size * 8) / 10
        val result = FloatArray(pixelCount)
        var byteIndex = 0
        var pixelIndex = 0

        while (pixelIndex < pixelCount - 3 && byteIndex < data.size - 4) {
            val b0 = data[byteIndex++].toInt() and 0xFF
            val b1 = data[byteIndex++].toInt() and 0xFF
            val b2 = data[byteIndex++].toInt() and 0xFF
            val b3 = data[byteIndex++].toInt() and 0xFF
            val b4 = data[byteIndex++].toInt() and 0xFF

            result[pixelIndex++] = ((b0 shl 2) or (b1 shr 6)).toFloat()
            result[pixelIndex++] = (((b1 and 0x3F) shl 4) or (b2 shr 4)).toFloat()
            result[pixelIndex++] = (((b2 and 0x0F) shl 6) or (b3 shr 2)).toFloat()
            result[pixelIndex++] = (((b3 and 0x03) shl 8) or b4).toFloat()
        }
        return result
    }

    private fun unpack12Bit(data: ByteArray): FloatArray {
        // 12-bit: 2 pixels packed in 3 bytes
        val pixelCount = (data.size * 8) / 12
        val result = FloatArray(pixelCount)
        var byteIndex = 0
        var pixelIndex = 0

        while (pixelIndex < pixelCount - 1 && byteIndex < data.size - 2) {
            val b0 = data[byteIndex++].toInt() and 0xFF
            val b1 = data[byteIndex++].toInt() and 0xFF
            val b2 = data[byteIndex++].toInt() and 0xFF

            result[pixelIndex++] = ((b0 shl 4) or (b1 shr 4)).toFloat()
            result[pixelIndex++] = (((b1 and 0x0F) shl 8) or b2).toFloat()
        }
        return result
    }

    private fun unpack14Bit(data: ByteArray): FloatArray {
        // 14-bit: 通常以 16-bit 存储，高 2 位为 0
        return unpack16Bit(data).map { it * 4f }.toFloatArray() // Scale to 14-bit range
    }

    private fun unpack16Bit(data: ByteArray): FloatArray {
        val pixelCount = data.size / 2
        val result = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt() and 0xFF
            result[i] = ((high shl 8) or low).toFloat()
        }
        return result
    }

    // ==================== 电平校正 ====================

    /**
     * 黑电平校正 + 白电平归一化
     */
    private fun applyLevelCorrection(
        data: FloatArray,
        width: Int,
        height: Int,
        pattern: BayerPattern,
        blackLevel: FloatArray,
        whiteLevel: Float
    ): FloatArray {
        val result = FloatArray(data.size)
        val range = whiteLevel - blackLevel.average().toFloat()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val channelBlack = getBlackLevelForPixel(x, y, pattern, blackLevel)
                val corrected = (data[idx] - channelBlack) / range
                result[idx] = corrected.coerceIn(0f, 1f)
            }
        }
        return result
    }

    private fun getBlackLevelForPixel(x: Int, y: Int, pattern: BayerPattern, blackLevel: FloatArray): Float {
        val phase = (y % 2) * 2 + (x % 2)
        return when (pattern) {
            BayerPattern.RGGB -> blackLevel[phase]  // R, Gr, Gb, B
            BayerPattern.BGGR -> blackLevel[listOf(3, 2, 1, 0)[phase]]
            BayerPattern.GRBG -> blackLevel[listOf(1, 0, 3, 2)[phase]]
            BayerPattern.GBRG -> blackLevel[listOf(2, 3, 0, 1)[phase]]
        }
    }

    // ==================== 坏点修复 ====================

    /**
     * 坏点检测与修复
     * 使用中值滤波检测异常值
     */
    private fun fixDeadPixels(data: FloatArray, width: Int, height: Int): FloatArray {
        val result = data.copyOf()
        val threshold = 0.3f  // 与周围差异超过此阈值判定为坏点

        for (y in 2 until height - 2 step 2) {
            for (x in 2 until width - 2 step 2) {
                // 只检查同色通道（间隔2像素）
                val idx = y * width + x
                val current = data[idx]

                // 收集同色邻域
                val neighbors = floatArrayOf(
                    data[(y - 2) * width + x],
                    data[(y + 2) * width + x],
                    data[y * width + (x - 2)],
                    data[y * width + (x + 2)]
                )
                neighbors.sort()
                val median = (neighbors[1] + neighbors[2]) / 2f

                // 如果差异过大，用中值替换
                if (abs(current - median) > threshold) {
                    result[idx] = median
                }
            }
        }
        return result
    }

    // ==================== 去马赛克算法 ====================

    /**
     * 双线性插值去马赛克
     * 最简单快速的方法，边缘处理一般
     */
    private fun demosaicBilinear(
        data: FloatArray,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): FloatArray {
        val rgb = FloatArray(width * height * 3)

        // 获取每个位置的颜色通道类型
        val colorAt = { x: Int, y: Int -> getColorChannel(x, y, pattern) }

        // 安全获取像素值
        val getPixel = { x: Int, y: Int ->
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            data[cy * width + cx]
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = (y * width + x) * 3
                val color = colorAt(x, y)

                when (color) {
                    0 -> { // Red pixel
                        rgb[idx] = getPixel(x, y)  // R: 直接取值
                        rgb[idx + 1] = (getPixel(x - 1, y) + getPixel(x + 1, y) + 
                                       getPixel(x, y - 1) + getPixel(x, y + 1)) / 4f  // G: 十字平均
                        rgb[idx + 2] = (getPixel(x - 1, y - 1) + getPixel(x + 1, y - 1) + 
                                       getPixel(x - 1, y + 1) + getPixel(x + 1, y + 1)) / 4f  // B: 对角平均
                    }
                    1 -> { // Green pixel (in red row)
                        rgb[idx] = (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f  // R: 水平平均
                        rgb[idx + 1] = getPixel(x, y)  // G: 直接取值
                        rgb[idx + 2] = (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f  // B: 垂直平均
                    }
                    2 -> { // Green pixel (in blue row)
                        rgb[idx] = (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f  // R: 垂直平均
                        rgb[idx + 1] = getPixel(x, y)  // G: 直接取值
                        rgb[idx + 2] = (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f  // B: 水平平均
                    }
                    3 -> { // Blue pixel
                        rgb[idx] = (getPixel(x - 1, y - 1) + getPixel(x + 1, y - 1) + 
                                   getPixel(x - 1, y + 1) + getPixel(x + 1, y + 1)) / 4f  // R: 对角平均
                        rgb[idx + 1] = (getPixel(x - 1, y) + getPixel(x + 1, y) + 
                                       getPixel(x, y - 1) + getPixel(x, y + 1)) / 4f  // G: 十字平均
                        rgb[idx + 2] = getPixel(x, y)  // B: 直接取值
                    }
                }
            }
        }
        return rgb
    }

    /**
     * VNG (Variable Number of Gradients) 去马赛克
     * 通过分析多个方向的梯度选择最佳插值方向
     * 质量优于双线性，边缘保持较好
     */
    private fun demosaicVNG(
        data: FloatArray,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): FloatArray {
        val rgb = FloatArray(width * height * 3)
        val colorAt = { x: Int, y: Int -> getColorChannel(x, y, pattern) }

        val getPixel = { x: Int, y: Int ->
            if (x < 0 || x >= width || y < 0 || y >= height) 0f
            else data[y * width + x]
        }

        // VNG 使用 8 个方向的梯度
        val directions = arrayOf(
            intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
            intArrayOf(-1, 0),                      intArrayOf(1, 0),
            intArrayOf(-1, 1),  intArrayOf(0, 1),  intArrayOf(1, 1)
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = (y * width + x) * 3
                val color = colorAt(x, y)

                if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) {
                    // 边缘使用双线性
                    val bilinear = demosaicBilinearPixel(data, x, y, width, height, pattern)
                    rgb[idx] = bilinear[0]
                    rgb[idx + 1] = bilinear[1]
                    rgb[idx + 2] = bilinear[2]
                    continue
                }

                // 计算各方向梯度
                val gradients = FloatArray(8)
                for (d in 0 until 8) {
                    val dx = directions[d][0]
                    val dy = directions[d][1]
                    gradients[d] = abs(getPixel(x, y) - getPixel(x + dx * 2, y + dy * 2))
                }

                // 找到最小梯度的方向
                val minGrad = gradients.minOrNull() ?: 0f
                val threshold = minGrad * 1.5f + 0.01f

                // 使用梯度小于阈值的方向进行加权平均
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var count = 0f

                for (d in 0 until 8) {
                    if (gradients[d] <= threshold) {
                        val dx = directions[d][0]
                        val dy = directions[d][1]
                        val px = x + dx
                        val py = y + dy
                        val pColor = colorAt(px, py)
                        val weight = 1f / (gradients[d] + 0.01f)

                        when (pColor) {
                            0 -> sumR += getPixel(px, py) * weight
                            1, 2 -> sumG += getPixel(px, py) * weight
                            3 -> sumB += getPixel(px, py) * weight
                        }
                        count += weight
                    }
                }

                // 结合当前像素值
                when (color) {
                    0 -> {
                        rgb[idx] = getPixel(x, y)
                        rgb[idx + 1] = if (count > 0) sumG / count else getPixel(x, y)
                        rgb[idx + 2] = if (count > 0) sumB / count else getPixel(x, y)
                    }
                    1, 2 -> {
                        rgb[idx] = if (count > 0) sumR / count else getPixel(x, y)
                        rgb[idx + 1] = getPixel(x, y)
                        rgb[idx + 2] = if (count > 0) sumB / count else getPixel(x, y)
                    }
                    3 -> {
                        rgb[idx] = if (count > 0) sumR / count else getPixel(x, y)
                        rgb[idx + 1] = if (count > 0) sumG / count else getPixel(x, y)
                        rgb[idx + 2] = getPixel(x, y)
                    }
                }
            }
        }
        return rgb
    }

    /**
     * AHD (Adaptive Homogeneity-Directed) 去马赛克
     * 最高质量，分析水平和垂直方向的同质性来选择插值方向
     */
    private fun demosaicAHD(
        data: FloatArray,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): FloatArray {
        // AHD 算法步骤：
        // 1. 沿水平方向插值
        // 2. 沿垂直方向插值
        // 3. 将 RGB 转换到 Lab 色彩空间
        // 4. 计算每个方向的同质性
        // 5. 选择同质性更高的方向

        // 简化版本：使用水平/垂直梯度比较
        val rgb = FloatArray(width * height * 3)
        val colorAt = { x: Int, y: Int -> getColorChannel(x, y, pattern) }

        val getPixel = { x: Int, y: Int ->
            if (x < 0 || x >= width || y < 0 || y >= height) 0f
            else data[y * width + x]
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = (y * width + x) * 3
                val color = colorAt(x, y)

                // 计算水平和垂直梯度
                val hGrad = abs(getPixel(x - 1, y) - getPixel(x + 1, y))
                val vGrad = abs(getPixel(x, y - 1) - getPixel(x, y + 1))

                // 选择梯度较小的方向
                val useHorizontal = hGrad < vGrad

                when (color) {
                    0 -> { // Red
                        rgb[idx] = getPixel(x, y)
                        rgb[idx + 1] = if (useHorizontal) {
                            (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f
                        } else {
                            (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f
                        }
                        rgb[idx + 2] = (getPixel(x - 1, y - 1) + getPixel(x + 1, y + 1)) / 2f
                    }
                    1 -> { // Green (red row)
                        rgb[idx] = (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f
                        rgb[idx + 1] = getPixel(x, y)
                        rgb[idx + 2] = (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f
                    }
                    2 -> { // Green (blue row)
                        rgb[idx] = (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f
                        rgb[idx + 1] = getPixel(x, y)
                        rgb[idx + 2] = (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f
                    }
                    3 -> { // Blue
                        rgb[idx] = (getPixel(x - 1, y - 1) + getPixel(x + 1, y + 1)) / 2f
                        rgb[idx + 1] = if (useHorizontal) {
                            (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f
                        } else {
                            (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f
                        }
                        rgb[idx + 2] = getPixel(x, y)
                    }
                }
            }
        }
        return rgb
    }

    /**
     * DCB 去马赛克
     * 使用方向校正的双线性插值
     */
    private fun demosaicDCB(
        data: FloatArray,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): FloatArray {
        // DCB 算法类似 VNG，但使用不同的梯度计算方式
        // 这里使用简化版本
        return demosaicVNG(data, width, height, pattern)
    }

    /**
     * 单像素双线性插值（用于边缘处理）
     */
    private fun demosaicBilinearPixel(
        data: FloatArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): FloatArray {
        val result = FloatArray(3)
        val color = getColorChannel(x, y, pattern)

        val getPixel = { px: Int, py: Int ->
            val cx = px.coerceIn(0, width - 1)
            val cy = py.coerceIn(0, height - 1)
            data[cy * width + cx]
        }

        when (color) {
            0 -> {
                result[0] = getPixel(x, y)
                result[1] = (getPixel(x - 1, y) + getPixel(x + 1, y) + getPixel(x, y - 1) + getPixel(x, y + 1)) / 4f
                result[2] = (getPixel(x - 1, y - 1) + getPixel(x + 1, y - 1) + getPixel(x - 1, y + 1) + getPixel(x + 1, y + 1)) / 4f
            }
            1 -> {
                result[0] = (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f
                result[1] = getPixel(x, y)
                result[2] = (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f
            }
            2 -> {
                result[0] = (getPixel(x, y - 1) + getPixel(x, y + 1)) / 2f
                result[1] = getPixel(x, y)
                result[2] = (getPixel(x - 1, y) + getPixel(x + 1, y)) / 2f
            }
            3 -> {
                result[0] = (getPixel(x - 1, y - 1) + getPixel(x + 1, y - 1) + getPixel(x - 1, y + 1) + getPixel(x + 1, y + 1)) / 4f
                result[1] = (getPixel(x - 1, y) + getPixel(x + 1, y) + getPixel(x, y - 1) + getPixel(x, y + 1)) / 4f
                result[2] = getPixel(x, y)
            }
        }
        return result
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取像素位置的颜色通道
     * 返回值: 0=R, 1=G(红行), 2=G(蓝行), 3=B
     */
    private fun getColorChannel(x: Int, y: Int, pattern: BayerPattern): Int {
        val phase = (y % 2) * 2 + (x % 2)
        return when (pattern) {
            BayerPattern.RGGB -> phase  // 0:R, 1:G, 2:G, 3:B
            BayerPattern.BGGR -> listOf(3, 2, 1, 0)[phase]
            BayerPattern.GRBG -> listOf(1, 0, 3, 2)[phase]
            BayerPattern.GBRG -> listOf(2, 3, 0, 1)[phase]
        }
    }

    /**
     * RGB 数据转 Bitmap
     */
    private fun rgbToBitmap(rgb: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in 0 until width * height) {
            val r = (rgb[i * 3] * 255f).toInt().coerceIn(0, 255)
            val g = (rgb[i * 3 + 1] * 255f).toInt().coerceIn(0, 255)
            val b = (rgb[i * 3 + 2] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * 废弃的简单处理方法（保持兼容性）
     */
    @Deprecated("Use process() instead", ReplaceWith("process(rawData, width, height)"))
    fun process(rawData: ByteArray, method: DemosaicMethod = DemosaicMethod.AHD): Bitmap {
        return Bitmap.createBitmap(1920, 1440, Bitmap.Config.ARGB_8888)
    }
}
