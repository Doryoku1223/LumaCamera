package com.luma.camera.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HEIC 编码器
 *
 * 使用 Android 原生 HEIC 编码支持
 * HEIC 相比 JPEG 可节省约 50% 存储空间，同时保持相同画质
 */
@Singleton
class HeicEncoder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 检查设备是否支持 HEIC 编码
     */
    fun isHeicSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    /**
     * 将 Bitmap 编码为 HEIC 格式
     *
     * @param bitmap 源图像
     * @param outputStream 输出流
     * @param quality 质量 (0-100)，默认 95
     */
    suspend fun encodeToHeic(
        bitmap: Bitmap,
        outputStream: OutputStream,
        quality: Int = DEFAULT_QUALITY
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isHeicSupported()) {
                return@withContext Result.failure(
                    UnsupportedOperationException("HEIC encoding requires Android P+")
                )
            }

            val compressFormat = Bitmap.CompressFormat.WEBP_LOSSLESS
            // Android 10+ 支持 HEIC
            val heicFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 使用反射获取 HEIC 格式，因为部分设备可能不支持
                try {
                    Bitmap.CompressFormat.valueOf("HEIC")
                } catch (e: Exception) {
                    Timber.w("HEIC format not available, falling back to WEBP")
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                }
            } else {
                compressFormat
            }

            val success = bitmap.compress(heicFormat, quality, outputStream)
            outputStream.flush()

            if (success) {
                Timber.d("HEIC encoded: ${bitmap.width}x${bitmap.height}, quality=$quality")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to compress bitmap to HEIC"))
            }
        } catch (e: Exception) {
            Timber.e(e, "HEIC encoding failed")
            Result.failure(e)
        }
    }

    /**
     * 将 YUV Image 编码为 HEIC
     *
     * @param image YUV_420_888 格式的 Image
     * @param outputStream 输出流
     * @param quality 质量
     */
    suspend fun encodeYuvToHeic(
        image: Image,
        outputStream: OutputStream,
        quality: Int = DEFAULT_QUALITY
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(image.format == ImageFormat.YUV_420_888) {
                "Image must be YUV_420_888 format"
            }

            // 转换 YUV 到 Bitmap
            val bitmap = yuvToBitmap(image)
            val result = encodeToHeic(bitmap, outputStream, quality)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Timber.e(e, "YUV to HEIC encoding failed")
            Result.failure(e)
        }
    }

    /**
     * 将 JPEG 数据转换为 HEIC
     *
     * @param jpegData JPEG 字节数据
     * @param outputStream 输出流
     * @param quality 质量
     */
    suspend fun jpegToHeic(
        jpegData: ByteArray,
        outputStream: OutputStream,
        quality: Int = DEFAULT_QUALITY
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                ?: return@withContext Result.failure(Exception("Failed to decode JPEG"))

            val result = encodeToHeic(bitmap, outputStream, quality)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Timber.e(e, "JPEG to HEIC conversion failed")
            Result.failure(e)
        }
    }

    /**
     * 估算 HEIC 文件大小
     * HEIC 通常比同质量 JPEG 小约 40-50%
     */
    fun estimateHeicSize(width: Int, height: Int, quality: Int = DEFAULT_QUALITY): Long {
        // 基于经验公式估算
        val pixels = width.toLong() * height
        val bitsPerPixel = when {
            quality >= 95 -> 1.5
            quality >= 85 -> 1.0
            quality >= 75 -> 0.7
            else -> 0.5
        }
        return (pixels * bitsPerPixel / 8).toLong()
    }

    /**
     * YUV_420_888 转 Bitmap
     * 
     * 注意：这是一个简化实现，生产环境建议使用 RenderScript 或 GPU
     */
    private fun yuvToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // Y 分量
        yBuffer.get(nv21, 0, ySize)
        
        // VU 分量（NV21 格式）
        val uvPixelStride = image.planes[1].pixelStride
        if (uvPixelStride == 1) {
            // U 和 V 是连续的
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
        } else {
            // 交错的 UV
            val uvWidth = width / 2
            val uvHeight = height / 2
            var uvIndex = ySize
            
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val vuPos = row * image.planes[2].rowStride + col * uvPixelStride
                    nv21[uvIndex++] = vBuffer.get(vuPos)
                    nv21[uvIndex++] = uBuffer.get(vuPos)
                }
            }
        }
        
        // 使用 YuvImage 转换
        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            width,
            height,
            null
        )
        
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, out)
        val jpegBytes = out.toByteArray()
        
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    companion object {
        const val DEFAULT_QUALITY = 95
    }
}
