package com.luma.camera.imaging

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

/**
 * Luma Log 曲线
 *
 * 核心职责：将线性数据映射为对数编码
 *
 * 曲线特性：
 * - 暗部区域 (0-2%)：轻微线性提升，避免 log(0) 问题
 * - 阴影区域 (2-18%)：对数压缩，提升暗部可见性
 * - 中间调 (18%)：对应输出的中性灰点
 * - 高光区域 (18%-90%)：渐进压缩，保留层次
 * - 超高光 (90%-100%)：柔和滚降，防止硬切白
 *
 * 比 Sony S-Log3 更"可看"，暗部噪点控制更好
 *
 * 性能目标: < 100ms
 */
@Singleton
class LumaLogCurve @Inject constructor() {

    companion object {
        // Luma Log 曲线参数（内置固定，用户不可选）
        
        // 暗部线性区域阈值
        private const val TOE_THRESHOLD = 0.02f
        // 暗部线性斜率
        private const val TOE_SLOPE = 0.5f
        
        // 中间调基准点 (18% 灰)
        private const val MID_GRAY_IN = 0.18f
        private const val MID_GRAY_OUT = 0.42f  // 输出灰度值
        
        // 高光滚降起始点
        private const val SHOULDER_START = 0.9f
        // 高光最大输出
        private const val SHOULDER_MAX = 0.95f
        
        // Log 基数
        private const val LOG_BASE = 10f
    }

    /**
     * 应用 Luma Log 曲线
     */
    fun apply(input: Bitmap): Bitmap {
        val width = input.width
        val height = input.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xFF
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            // 应用 Log 曲线到每个通道
            val rLog = applyLogCurve(r)
            val gLog = applyLogCurve(g)
            val bLog = applyLogCurve(b)

            // 重新组合像素
            val rOut = (rLog * 255).toInt().coerceIn(0, 255)
            val gOut = (gLog * 255).toInt().coerceIn(0, 255)
            val bOut = (bLog * 255).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 单个值的 Log 曲线映射
     */
    private fun applyLogCurve(linear: Float): Float {
        return when {
            // 暗部线性区域：避免 log(0) 问题
            linear < TOE_THRESHOLD -> {
                linear * TOE_SLOPE
            }
            // 超高光滚降区域
            linear > SHOULDER_START -> {
                // 柔和滚降到最大值
                val t = (linear - SHOULDER_START) / (1f - SHOULDER_START)
                val shoulder = applyLogCurve(SHOULDER_START)
                shoulder + (SHOULDER_MAX - shoulder) * smoothstep(t)
            }
            // 主 Log 区域
            else -> {
                // Log 映射公式
                // output = offset + gain * log10(linear + offset)
                val logValue = kotlin.math.log10(linear / MID_GRAY_IN + 0.01f)
                val normalized = logValue / kotlin.math.log10(1f / MID_GRAY_IN + 0.01f)
                MID_GRAY_OUT + (normalized * (1f - MID_GRAY_OUT))
            }
        }.coerceIn(0f, 1f)
    }

    /**
     * 平滑插值函数
     */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * 逆 Log 曲线（用于预览或导出线性图像）
     */
    fun inverse(input: Bitmap): Bitmap {
        // TODO: 实现逆 Log 曲线
        return input.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 生成 LUT 查找表（用于 GPU 加速）
     */
    fun generateLut(size: Int = 256): FloatArray {
        return FloatArray(size) { i ->
            applyLogCurve(i.toFloat() / (size - 1))
        }
    }
}
