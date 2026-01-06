package com.luma.camera.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灰片生成器
 *
 * 核心职责：生成信息量最大化的中性灰片
 *
 * 参数设置：
 * - 对比度：30-40%（低对比度保留层次）
 * - 饱和度：40-50%（低饱和保留调色空间）
 * - 锐度：0%（无锐化，最大后期空间）
 * - 高光恢复：100%
 * - 暗部提升：20-30%
 */
@Singleton
class FlatProfileGenerator @Inject constructor() {

    companion object {
        // 灰片参数
        const val CONTRAST = 0.35f      // 35% 对比度
        const val SATURATION = 0.45f    // 45% 饱和度
        const val HIGHLIGHT_RECOVERY = 1.0f
        const val SHADOW_LIFT = 0.25f   // 25% 暗部提升
    }

    /**
     * 生成灰片
     */
    fun generate(input: Bitmap): Bitmap {
        val width = input.width
        val height = input.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        // 应用降低对比度和饱和度的颜色矩阵
        val colorMatrix = createFlatProfileMatrix()
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        canvas.drawBitmap(input, 0f, 0f, paint)

        return output
    }

    /**
     * 创建灰片颜色矩阵
     *
     * 颜色矩阵格式：
     * [a, b, c, d, e]   -> R' = a*R + b*G + c*B + d*A + e
     * [f, g, h, i, j]   -> G' = ...
     * [k, l, m, n, o]   -> B' = ...
     * [p, q, r, s, t]   -> A' = ...
     */
    private fun createFlatProfileMatrix(): ColorMatrix {
        val matrix = ColorMatrix()

        // 1. 降低饱和度
        val saturationMatrix = ColorMatrix()
        saturationMatrix.setSaturation(SATURATION)

        // 2. 降低对比度
        val contrastMatrix = ColorMatrix()
        val scale = CONTRAST + 0.5f  // 0.5-1.0 范围
        val offset = (1f - scale) * 127.5f
        contrastMatrix.set(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))

        // 3. 暗部提升
        val shadowMatrix = ColorMatrix()
        val lift = SHADOW_LIFT * 50f  // 转换为色阶偏移
        shadowMatrix.set(floatArrayOf(
            1f, 0f, 0f, 0f, lift,
            0f, 1f, 0f, 0f, lift,
            0f, 0f, 1f, 0f, lift,
            0f, 0f, 0f, 1f, 0f
        ))

        // 组合矩阵
        matrix.postConcat(saturationMatrix)
        matrix.postConcat(contrastMatrix)
        matrix.postConcat(shadowMatrix)

        return matrix
    }

    /**
     * 获取灰片参数描述
     */
    fun getProfileDescription(): Map<String, Float> {
        return mapOf(
            "对比度" to CONTRAST * 100,
            "饱和度" to SATURATION * 100,
            "锐度" to 0f,
            "高光恢复" to HIGHLIGHT_RECOVERY * 100,
            "暗部提升" to SHADOW_LIFT * 100
        )
    }
}
