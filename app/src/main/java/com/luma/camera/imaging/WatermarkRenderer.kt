package com.luma.camera.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import com.luma.camera.R
import com.luma.camera.domain.model.WatermarkPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 水印渲染器
 * 
 * 将 Luma 水印叠加到照片上
 * 
 * 注意：水印会根据照片的 EXIF 方向正确放置，确保无论照片如何旋转，
 * 水印始终显示在用户期望的位置（底部）
 */
@Singleton
class WatermarkRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    // 水印缓存（按尺寸缓存）
    private var cachedWatermark: Bitmap? = null
    private var cachedWatermarkWidth: Int = 0
    
    /**
     * 将水印应用到照片上（考虑 EXIF 方向）
     * 
     * @param photo 原始照片（未旋转的原始像素）
     * @param position 水印位置
     * @param exifOrientation EXIF 方向值，用于确定照片的实际显示方向
     * @return 带水印的照片（新 Bitmap，水印位置已根据 EXIF 调整）
     */
    fun applyWatermark(
        photo: Bitmap, 
        position: WatermarkPosition,
        exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Bitmap {
        val startTime = System.currentTimeMillis()
        
        // 根据 EXIF 方向旋转照片，然后添加水印，再旋转回去
        // 这样可以确保水印在最终显示时位于正确的位置
        val rotatedPhoto = rotateBitmapByExif(photo, exifOrientation)
        val needsRotation = rotatedPhoto != photo
        
        // 创建可编辑的副本
        val result = if (needsRotation) {
            rotatedPhoto.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            photo.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = Canvas(result)
        
        // 计算水印尺寸（照片短边的 25%，更大更显眼）
        // 使用短边确保水印在横向和纵向照片上大小一致
        val shortSide = minOf(result.width, result.height)
        val watermarkWidth = (shortSide * 0.30f).toInt()
        val watermarkHeight = (watermarkWidth * 0.21f).toInt() // 保持 2000:420 的比例
        
        Timber.d("Watermark size: ${watermarkWidth}x${watermarkHeight} for photo ${result.width}x${result.height}")
        
        // 获取或创建缩放后的水印
        val watermark = getScaledWatermark(watermarkWidth, watermarkHeight)
        if (watermark == null) {
            Timber.w("Failed to load watermark drawable")
            if (needsRotation) rotatedPhoto.recycle()
            return result
        }
        
        // 计算水印位置（在旋转后的照片坐标系中）
        val margin = (result.width * 0.04f).toInt() // 4% 边距
        val bottomMargin = (result.height * 0.04f).toInt()
        
        val x = when (position) {
            WatermarkPosition.BOTTOM_LEFT -> margin.toFloat()
            WatermarkPosition.BOTTOM_CENTER -> (result.width - watermarkWidth) / 2f
            WatermarkPosition.BOTTOM_RIGHT -> (result.width - watermarkWidth - margin).toFloat()
        }
        val y = (result.height - watermarkHeight - bottomMargin).toFloat()
        
        Timber.d("Watermark position: x=$x, y=$y (result: ${result.width}x${result.height}, watermark: ${watermarkWidth}x${watermarkHeight})")
        
        // 绘制水印（半透明）
        val paint = Paint().apply {
            alpha = 200 // 约 78% 不透明度
            isAntiAlias = true
            isFilterBitmap = true
        }
        
        canvas.drawBitmap(watermark, x, y, paint)
        
        // 如果之前旋转了，需要旋转回原始方向
        // 这样 EXIF 方向信息仍然有效
        val finalResult = if (needsRotation) {
            val inverse = rotateBitmapByExifInverse(result, exifOrientation)
            result.recycle()
            rotatedPhoto.recycle()
            inverse
        } else {
            result
        }
        
        Timber.d("Watermark applied at ${position.name} (EXIF: $exifOrientation) in ${System.currentTimeMillis() - startTime}ms")
        
        return finalResult
    }
    
    /**
     * 根据 EXIF 方向旋转 Bitmap 到正确的显示方向
     */
    private fun rotateBitmapByExif(bitmap: Bitmap, orientation: Int): Bitmap {
        Timber.d("rotateBitmapByExif: input ${bitmap.width}x${bitmap.height}, orientation=$orientation")
        
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            else -> {
                Timber.d("rotateBitmapByExif: no rotation needed, returning original")
                return bitmap // 无需旋转
            }
        }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        Timber.d("rotateBitmapByExif: output ${result.width}x${result.height}")
        return result
    }
    
    /**
     * 逆向旋转 Bitmap 回原始方向（用于保持 EXIF 兼容性）
     */
    private fun rotateBitmapByExifInverse(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(-90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(-180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(-90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.preScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            else -> return bitmap // 无需旋转
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    /**
     * 获取缩放后的水印 Bitmap
     */
    private fun getScaledWatermark(targetWidth: Int, targetHeight: Int): Bitmap? {
        // 检查缓存
        if (cachedWatermark != null && cachedWatermarkWidth == targetWidth) {
            return cachedWatermark
        }
        
        return try {
            // 加载水印 drawable
            val drawable = AppCompatResources.getDrawable(context, R.drawable.watermark_luma)
            if (drawable == null) {
                Timber.e("Watermark drawable not found")
                return null
            }
            
            // 转换为 Bitmap 并缩放
            val originalBitmap = drawable.toBitmap(
                width = targetWidth.coerceAtLeast(1),
                height = targetHeight.coerceAtLeast(1)
            )
            
            // 缓存
            cachedWatermark?.recycle()
            cachedWatermark = originalBitmap
            cachedWatermarkWidth = targetWidth
            
            originalBitmap
        } catch (e: Exception) {
            Timber.e(e, "Failed to load watermark")
            null
        }
    }
    
    /**
     * 清理缓存
     */
    fun clearCache() {
        cachedWatermark?.recycle()
        cachedWatermark = null
        cachedWatermarkWidth = 0
    }
}
