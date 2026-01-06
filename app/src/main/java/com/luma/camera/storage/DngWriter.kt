package com.luma.camera.storage

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.Image
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStream
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DNG RAW 文件写入器
 *
 * 使用 Android DngCreator API 生成 DNG 格式的 RAW 文件
 * 保留完整的传感器数据，支持后期处理
 */
@Singleton
class DngWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 将 RAW 图像写入 DNG 文件
     *
     * @param outputStream 输出流
     * @param image RAW_SENSOR 格式的 Image 对象
     * @param characteristics 相机特性
     * @param captureResult 拍摄结果（用于 EXIF）
     * @param orientation 图像方向 (0, 90, 180, 270)
     * @param metadata 额外的 EXIF 信息
     */
    suspend fun writeDng(
        outputStream: OutputStream,
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: android.hardware.camera2.CaptureResult,
        orientation: Int = 0,
        metadata: DngMetadata? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(image.format == ImageFormat.RAW_SENSOR) {
                "Image must be RAW_SENSOR format, got ${image.format}"
            }

            val dngCreator = DngCreator(characteristics, captureResult).apply {
                // 设置方向
                setOrientation(orientationToDegrees(orientation))

                // 设置描述
                metadata?.description?.let { setDescription(it) }

                // 设置地理位置
                metadata?.location?.let { setLocation(it) }
            }

            // 写入 DNG 文件
            dngCreator.writeImage(outputStream, image)
            dngCreator.close()

            Timber.d("DNG written successfully: ${image.width}x${image.height}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write DNG")
            Result.failure(e)
        }
    }

    /**
     * 获取 DNG 文件的预估大小
     *
     * @param width 图像宽度
     * @param height 图像高度
     * @param bitsPerPixel 每像素位数（通常为 10, 12 或 14）
     */
    fun estimateDngSize(width: Int, height: Int, bitsPerPixel: Int = 12): Long {
        // DNG 文件大小 ≈ 像素数 × 每像素位数 / 8 + 头部开销
        val rawDataSize = (width.toLong() * height * bitsPerPixel) / 8
        val overheadSize = 64 * 1024L  // 约 64KB 头部开销
        return rawDataSize + overheadSize
    }

    /**
     * 检查设备是否支持 RAW 拍摄
     */
    fun isRawSupported(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
    }

    /**
     * 获取 RAW 传感器尺寸
     */
    fun getRawSensorSize(characteristics: CameraCharacteristics): android.util.Size? {
        val streamConfigurationMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null

        val rawSizes = streamConfigurationMap.getOutputSizes(ImageFormat.RAW_SENSOR)
        return rawSizes?.maxByOrNull { it.width * it.height }
    }

    /**
     * 获取 RAW 传感器的位深度
     */
    fun getRawBitDepth(characteristics: CameraCharacteristics): Int {
        // 大多数现代传感器使用 10-14 位
        // Hasselblad 传感器通常使用 12 或 14 位
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return 12

        return when {
            whiteLevel <= 1023 -> 10    // 2^10 - 1
            whiteLevel <= 4095 -> 12    // 2^12 - 1
            whiteLevel <= 16383 -> 14   // 2^14 - 1
            else -> 16
        }
    }

    private fun orientationToDegrees(orientation: Int): Int {
        return when (orientation) {
            0 -> android.media.ExifInterface.ORIENTATION_NORMAL
            90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }
    }
}

/**
 * DNG 元数据
 */
data class DngMetadata(
    val description: String? = null,
    val location: android.location.Location? = null,
    val captureTime: Date? = null
)
