package com.luma.camera.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 直方图分析器
 *
 * 职责：
 * - RGB 直方图计算
 * - 亮度直方图
 * - 曝光分析（过曝/欠曝检测）
 * - 动态范围分析
 *
 * 性能目标: < 16ms (用于实时预览)
 */
@Singleton
class HistogramAnalyzer @Inject constructor() {

    companion object {
        private const val HISTOGRAM_SIZE = 256
        private const val CLIPPING_THRESHOLD = 0.01f  // 1% 被裁切视为过曝/欠曝
        private const val SAMPLE_STEP = 4  // 采样步长（用于加速计算）
    }

    /**
     * 直方图数据
     */
    data class HistogramData(
        val red: IntArray,
        val green: IntArray,
        val blue: IntArray,
        val luminance: IntArray,
        val maxValue: Int,           // 最大计数值（用于归一化显示）
        val analysisTimeMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HistogramData) return false
            return red.contentEquals(other.red) && green.contentEquals(other.green) &&
                   blue.contentEquals(other.blue) && luminance.contentEquals(other.luminance)
        }
        override fun hashCode(): Int = red.contentHashCode()

        val isEmpty: Boolean
            get() = maxValue == 0
    }

    /**
     * 曝光分析结果
     */
    data class ExposureAnalysis(
        val overexposedRatio: Float,     // 过曝像素比例 (0-1)
        val underexposedRatio: Float,    // 欠曝像素比例 (0-1)
        val midtonesRatio: Float,        // 中间调比例 (0-1)
        val averageLuminance: Float,     // 平均亮度 (0-255)
        val dynamicRange: Float,         // 动态范围（档数）
        val isOverexposed: Boolean,
        val isUnderexposed: Boolean,
        val exposureAdvice: String
    )

    /**
     * 从 Bitmap 计算直方图
     */
    suspend fun analyze(bitmap: Bitmap): HistogramData = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val red = IntArray(HISTOGRAM_SIZE)
        val green = IntArray(HISTOGRAM_SIZE)
        val blue = IntArray(HISTOGRAM_SIZE)
        val luminance = IntArray(HISTOGRAM_SIZE)

        // 采样计算直方图
        for (i in pixels.indices step SAMPLE_STEP) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            red[r]++
            green[g]++
            blue[b]++

            // 计算亮度 (BT.709 标准)
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            luminance[luma]++
        }

        // 找最大值（用于归一化显示）
        val maxValue = maxOf(
            red.max(),
            green.max(),
            blue.max(),
            luminance.max()
        )

        val analysisTime = System.currentTimeMillis() - startTime
        Timber.v("Histogram analyzed in ${analysisTime}ms")

        HistogramData(red, green, blue, luminance, maxValue, analysisTime)
    }

    /**
     * 从 RGB float 数据计算直方图
     */
    suspend fun analyzeRgbData(
        rgbData: FloatArray,
        width: Int,
        height: Int
    ): HistogramData = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val red = IntArray(HISTOGRAM_SIZE)
        val green = IntArray(HISTOGRAM_SIZE)
        val blue = IntArray(HISTOGRAM_SIZE)
        val luminance = IntArray(HISTOGRAM_SIZE)

        val pixelCount = width * height
        for (i in 0 until pixelCount step SAMPLE_STEP) {
            val r = (rgbData[i * 3] * 255f).roundToInt().coerceIn(0, 255)
            val g = (rgbData[i * 3 + 1] * 255f).roundToInt().coerceIn(0, 255)
            val b = (rgbData[i * 3 + 2] * 255f).roundToInt().coerceIn(0, 255)

            red[r]++
            green[g]++
            blue[b]++

            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            luminance[luma]++
        }

        val maxValue = maxOf(red.max(), green.max(), blue.max(), luminance.max())
        val analysisTime = System.currentTimeMillis() - startTime

        HistogramData(red, green, blue, luminance, maxValue, analysisTime)
    }

    /**
     * 分析曝光状态
     */
    fun analyzeExposure(histogram: HistogramData): ExposureAnalysis {
        val totalSamples = histogram.luminance.sum().toFloat()
        if (totalSamples == 0f) {
            return ExposureAnalysis(0f, 0f, 1f, 128f, 0f, false, false, "无数据")
        }

        // 计算过曝（亮度 > 250）
        val overexposedCount = histogram.luminance.slice(250..255).sum()
        val overexposedRatio = overexposedCount / totalSamples

        // 计算欠曝（亮度 < 5）
        val underexposedCount = histogram.luminance.slice(0..4).sum()
        val underexposedRatio = underexposedCount / totalSamples

        // 中间调（亮度 50-200）
        val midtonesCount = histogram.luminance.slice(50..200).sum()
        val midtonesRatio = midtonesCount / totalSamples

        // 计算平均亮度
        var luminanceSum = 0L
        for (i in 0 until HISTOGRAM_SIZE) {
            luminanceSum += histogram.luminance[i].toLong() * i
        }
        val averageLuminance = luminanceSum.toFloat() / totalSamples

        // 计算动态范围（找到有效亮度范围）
        var minBin = 0
        var maxBin = 255
        val threshold = totalSamples * 0.001f  // 0.1% 作为有效像素阈值

        for (i in 0 until HISTOGRAM_SIZE) {
            if (histogram.luminance[i] > threshold) {
                minBin = i
                break
            }
        }
        for (i in HISTOGRAM_SIZE - 1 downTo 0) {
            if (histogram.luminance[i] > threshold) {
                maxBin = i
                break
            }
        }
        // 动态范围（档数）：log2(maxBin / minBin)
        val dynamicRange = if (minBin > 0) {
            (kotlin.math.ln((maxBin + 1f) / (minBin + 1f)) / kotlin.math.ln(2f))
        } else {
            8f  // 默认 8 档
        }

        // 曝光建议
        val isOverexposed = overexposedRatio > CLIPPING_THRESHOLD
        val isUnderexposed = underexposedRatio > CLIPPING_THRESHOLD

        val advice = when {
            isOverexposed && isUnderexposed -> "对比度过高，考虑使用 HDR"
            isOverexposed -> "高光溢出，减少曝光 ${(overexposedRatio * 100).roundToInt()}%"
            isUnderexposed -> "暗部丢失，增加曝光或暗部提升"
            averageLuminance < 80 -> "整体偏暗，可增加曝光"
            averageLuminance > 180 -> "整体偏亮，可减少曝光"
            else -> "曝光正常"
        }

        return ExposureAnalysis(
            overexposedRatio = overexposedRatio,
            underexposedRatio = underexposedRatio,
            midtonesRatio = midtonesRatio,
            averageLuminance = averageLuminance,
            dynamicRange = dynamicRange,
            isOverexposed = isOverexposed,
            isUnderexposed = isUnderexposed,
            exposureAdvice = advice
        )
    }

    /**
     * 渲染直方图为 Bitmap
     *
     * @param histogram 直方图数据
     * @param width 输出宽度
     * @param height 输出高度
     * @param showRgb 是否显示 RGB 分量
     * @param showLuminance 是否显示亮度
     * @param fillMode 是否填充（否则只画线条）
     */
    fun renderHistogram(
        histogram: HistogramData,
        width: Int,
        height: Int,
        showRgb: Boolean = true,
        showLuminance: Boolean = true,
        fillMode: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景
        canvas.drawColor(Color.argb(180, 0, 0, 0))

        if (histogram.isEmpty) return bitmap

        val maxValue = histogram.maxValue.toFloat()
        val scaleX = width.toFloat() / HISTOGRAM_SIZE
        val scaleY = height * 0.95f / maxValue

        val paint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 1f
        }

        // 绘制 RGB
        if (showRgb) {
            // 红色
            paint.color = Color.argb(150, 255, 0, 0)
            paint.style = if (fillMode) Paint.Style.FILL else Paint.Style.STROKE
            drawHistogramChannel(canvas, histogram.red, scaleX, scaleY, height, paint, fillMode)

            // 绿色
            paint.color = Color.argb(150, 0, 255, 0)
            drawHistogramChannel(canvas, histogram.green, scaleX, scaleY, height, paint, fillMode)

            // 蓝色
            paint.color = Color.argb(150, 0, 0, 255)
            drawHistogramChannel(canvas, histogram.blue, scaleX, scaleY, height, paint, fillMode)
        }

        // 绘制亮度
        if (showLuminance) {
            paint.color = Color.argb(200, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            drawHistogramChannel(canvas, histogram.luminance, scaleX, scaleY, height, paint, false)
        }

        return bitmap
    }

    private fun drawHistogramChannel(
        canvas: Canvas,
        data: IntArray,
        scaleX: Float,
        scaleY: Float,
        height: Int,
        paint: Paint,
        fill: Boolean
    ) {
        val path = Path()
        path.moveTo(0f, height.toFloat())

        for (i in 0 until HISTOGRAM_SIZE) {
            val x = i * scaleX
            val y = height - data[i] * scaleY
            if (i == 0) {
                path.lineTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        if (fill) {
            path.lineTo(HISTOGRAM_SIZE * scaleX, height.toFloat())
            path.close()
        }

        canvas.drawPath(path, paint)
    }
}
