package com.luma.camera.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 波形监视器
 *
 * 职责：
 * - 亮度波形显示
 * - RGB 分量波形
 * - RGB Parade（分离 RGB 波形）
 * - 向量示波器（色相/饱和度）
 *
 * 用于专业视频拍摄时的精确曝光监控
 *
 * 性能目标: < 30ms
 */
@Singleton
class WaveformMonitor @Inject constructor() {

    companion object {
        private const val WAVEFORM_HEIGHT = 256
        private const val SAMPLE_COLS = 256      // 水平采样列数
        private const val VECTORSCOPE_SIZE = 256 // 向量示波器大小
    }

    /**
     * 波形类型
     */
    enum class WaveformType {
        LUMA,       // 仅亮度
        RGB,        // RGB 叠加
        PARADE      // RGB 分离排列
    }

    /**
     * 波形数据
     */
    data class WaveformData(
        val columns: Array<IntArray>,  // 每列的亮度分布
        val type: WaveformType,
        val analysisTimeMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WaveformData) return false
            return columns.contentDeepEquals(other.columns)
        }
        override fun hashCode(): Int = columns.contentDeepHashCode()
    }

    /**
     * 向量示波器数据
     */
    data class VectorscopeData(
        val points: Array<IntArray>,   // 256x256 的热力图
        val maxValue: Int,
        val analysisTimeMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VectorscopeData) return false
            return points.contentDeepEquals(other.points)
        }
        override fun hashCode(): Int = points.contentDeepHashCode()
    }

    /**
     * 分析波形（从 Bitmap）
     */
    suspend fun analyzeWaveform(
        bitmap: Bitmap,
        type: WaveformType = WaveformType.LUMA
    ): WaveformData = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val columnCount = when (type) {
            WaveformType.PARADE -> SAMPLE_COLS * 3  // RGB 各占 1/3
            else -> SAMPLE_COLS
        }

        val columns = Array(columnCount) { IntArray(WAVEFORM_HEIGHT) }

        // 计算每列需要采样的源列数
        val colStep = width.toFloat() / SAMPLE_COLS

        for (col in 0 until SAMPLE_COLS) {
            val srcCol = (col * colStep).toInt().coerceIn(0, width - 1)

            for (row in 0 until height) {
                val pixel = pixels[row * width + srcCol]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                when (type) {
                    WaveformType.LUMA -> {
                        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
                        columns[col][luma]++
                    }
                    WaveformType.RGB -> {
                        // RGB 叠加在同一列
                        columns[col][r]++
                        columns[col][g]++
                        columns[col][b]++
                    }
                    WaveformType.PARADE -> {
                        // RGB 分离到不同区域
                        columns[col][r]++                              // R: 0 - SAMPLE_COLS
                        columns[col + SAMPLE_COLS][g]++                // G: SAMPLE_COLS - 2*SAMPLE_COLS
                        columns[col + SAMPLE_COLS * 2][b]++            // B: 2*SAMPLE_COLS - 3*SAMPLE_COLS
                    }
                }
            }
        }

        val analysisTime = System.currentTimeMillis() - startTime
        Timber.v("Waveform analyzed in ${analysisTime}ms")

        WaveformData(columns, type, analysisTime)
    }

    /**
     * 分析向量示波器（色度图）
     */
    suspend fun analyzeVectorscope(bitmap: Bitmap): VectorscopeData = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val points = Array(VECTORSCOPE_SIZE) { IntArray(VECTORSCOPE_SIZE) }
        var maxValue = 0

        // 采样步长
        val step = maxOf(1, (width * height) / 50000)  // 限制采样点数

        for (i in pixels.indices step step) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // RGB to YCbCr
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 0.564f * (b - y)
            val cr = 0.713f * (r - y)

            // 映射到示波器坐标（中心是 128, 128）
            val x = ((cr + 128) * VECTORSCOPE_SIZE / 256).toInt().coerceIn(0, VECTORSCOPE_SIZE - 1)
            val y2 = ((cb + 128) * VECTORSCOPE_SIZE / 256).toInt().coerceIn(0, VECTORSCOPE_SIZE - 1)

            points[x][y2]++
            maxValue = maxOf(maxValue, points[x][y2])
        }

        val analysisTime = System.currentTimeMillis() - startTime
        Timber.v("Vectorscope analyzed in ${analysisTime}ms")

        VectorscopeData(points, maxValue, analysisTime)
    }

    /**
     * 渲染波形为 Bitmap
     */
    fun renderWaveform(
        waveformData: WaveformData,
        width: Int,
        height: Int,
        showGuides: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景
        canvas.drawColor(Color.argb(200, 0, 0, 0))

        val paint = Paint().apply {
            isAntiAlias = true
        }

        // 绘制参考线
        if (showGuides) {
            paint.color = Color.argb(80, 255, 255, 255)
            paint.strokeWidth = 1f

            // 水平线（0%, 50%, 100%）
            val lineY100 = height * 0.05f  // 100% 在顶部
            val lineY50 = height * 0.5f
            val lineY0 = height * 0.95f    // 0% 在底部

            canvas.drawLine(0f, lineY100, width.toFloat(), lineY100, paint)
            canvas.drawLine(0f, lineY50, width.toFloat(), lineY50, paint)
            canvas.drawLine(0f, lineY0, width.toFloat(), lineY0, paint)

            // 标签
            paint.textSize = 12f
            paint.color = Color.argb(150, 255, 255, 255)
            canvas.drawText("100", 2f, lineY100 + 12f, paint)
            canvas.drawText("50", 2f, lineY50 + 12f, paint)
            canvas.drawText("0", 2f, lineY0 - 2f, paint)
        }

        // 绘制波形
        val colWidth = width.toFloat() / waveformData.columns.size
        val rowHeight = height * 0.9f / WAVEFORM_HEIGHT

        for ((colIndex, column) in waveformData.columns.withIndex()) {
            val x = colIndex * colWidth

            // 确定颜色
            val color = when (waveformData.type) {
                WaveformType.LUMA -> Color.argb(200, 180, 180, 180)
                WaveformType.RGB -> Color.argb(150, 255, 255, 255)
                WaveformType.PARADE -> {
                    when {
                        colIndex < SAMPLE_COLS -> Color.argb(180, 255, 50, 50)         // R
                        colIndex < SAMPLE_COLS * 2 -> Color.argb(180, 50, 255, 50)    // G
                        else -> Color.argb(180, 50, 50, 255)                           // B
                    }
                }
            }
            paint.color = color

            for (level in 0 until WAVEFORM_HEIGHT) {
                val count = column[level]
                if (count > 0) {
                    val y = height * 0.95f - level * rowHeight
                    // 根据计数调整亮度
                    val alpha = (count.coerceAtMost(10) * 20 + 50).coerceIn(50, 255)
                    paint.alpha = alpha
                    canvas.drawRect(x, y - rowHeight, x + colWidth, y, paint)
                }
            }
        }

        return bitmap
    }

    /**
     * 渲染向量示波器为 Bitmap
     */
    fun renderVectorscope(
        data: VectorscopeData,
        size: Int,
        showGuides: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景
        canvas.drawColor(Color.argb(200, 0, 0, 0))

        val paint = Paint().apply {
            isAntiAlias = true
        }

        val scale = size.toFloat() / VECTORSCOPE_SIZE
        val center = size / 2f

        // 绘制参考线
        if (showGuides) {
            paint.color = Color.argb(60, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f

            // 中心十字
            canvas.drawLine(center, 0f, center, size.toFloat(), paint)
            canvas.drawLine(0f, center, size.toFloat(), center, paint)

            // 圆形边界
            val radius = size * 0.45f
            canvas.drawCircle(center, center, radius, paint)
            canvas.drawCircle(center, center, radius * 0.5f, paint)

            // 色彩目标点（标准色条位置）
            paint.style = Paint.Style.FILL
            val targets = listOf(
                Triple("R", 0.7f, 0.0f),     // 红
                Triple("Mg", 0.5f, -0.5f),   // 品红
                Triple("B", 0.0f, -0.7f),    // 蓝
                Triple("Cy", -0.5f, -0.35f), // 青
                Triple("G", -0.6f, 0.3f),    // 绿
                Triple("Yl", 0.3f, 0.5f)     // 黄
            )

            paint.textSize = 10f
            for ((name, cr, cb) in targets) {
                val x = center + cr * radius
                val y = center - cb * radius
                paint.color = Color.argb(100, 255, 255, 255)
                canvas.drawCircle(x, y, 4f, paint)
                canvas.drawText(name, x + 6f, y + 4f, paint)
            }
        }

        // 绘制数据点
        if (data.maxValue > 0) {
            for (x in 0 until VECTORSCOPE_SIZE) {
                for (y in 0 until VECTORSCOPE_SIZE) {
                    val count = data.points[x][y]
                    if (count > 0) {
                        // 根据计数映射颜色强度
                        val intensity = (count.toFloat() / data.maxValue * 255).toInt().coerceIn(20, 255)

                        // 根据位置确定颜色（基于 CbCr 值）
                        val cr = (x - VECTORSCOPE_SIZE / 2f) / (VECTORSCOPE_SIZE / 2f)
                        val cb = (y - VECTORSCOPE_SIZE / 2f) / (VECTORSCOPE_SIZE / 2f)

                        // 将 CbCr 转回 RGB（大致）
                        val r = (128 + cr * 180).toInt().coerceIn(0, 255)
                        val g = (128 - cr * 90 - cb * 90).toInt().coerceIn(0, 255)
                        val b = (128 + cb * 180).toInt().coerceIn(0, 255)

                        paint.color = Color.argb(intensity, r, g, b)

                        val px = x * scale
                        val py = y * scale
                        canvas.drawRect(px, py, px + scale, py + scale, paint)
                    }
                }
            }
        }

        return bitmap
    }

    /**
     * 分析波形获取曝光建议
     */
    fun analyzeExposureFromWaveform(waveformData: WaveformData): String {
        var highCount = 0
        var lowCount = 0
        var totalCount = 0

        for (column in waveformData.columns) {
            for (level in 0 until WAVEFORM_HEIGHT) {
                val count = column[level]
                totalCount += count
                if (level > 240) highCount += count
                if (level < 16) lowCount += count
            }
        }

        if (totalCount == 0) return "无数据"

        val highRatio = highCount.toFloat() / totalCount
        val lowRatio = lowCount.toFloat() / totalCount

        return when {
            highRatio > 0.05f && lowRatio > 0.05f -> "对比度过高"
            highRatio > 0.05f -> "高光溢出 (${(highRatio * 100).roundToInt()}%)"
            lowRatio > 0.1f -> "暗部丢失 (${(lowRatio * 100).roundToInt()}%)"
            else -> "曝光正常"
        }
    }
}
