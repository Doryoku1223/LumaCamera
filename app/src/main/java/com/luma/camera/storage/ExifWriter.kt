package com.luma.camera.storage

import android.content.Context
import android.location.Location
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EXIF 信息写入器
 *
 * 负责将拍摄参数、地理位置、设备信息等写入图片 EXIF
 */
@Singleton
class ExifWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 写入 EXIF 信息到文件
     *
     * @param filePath 图片文件路径
     * @param metadata EXIF 元数据
     */
    fun writeExif(filePath: String, metadata: ExifMetadata): Result<Unit> {
        return try {
            val exif = ExifInterface(filePath)
            applyMetadata(exif, metadata)
            exif.saveAttributes()
            Timber.d("EXIF written to $filePath")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write EXIF")
            Result.failure(e)
        }
    }

    /**
     * 写入 EXIF 信息到输入流（需要可写入的流）
     */
    fun writeExif(inputStream: InputStream, metadata: ExifMetadata): Result<ExifInterface> {
        return try {
            val exif = ExifInterface(inputStream)
            applyMetadata(exif, metadata)
            Result.success(exif)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write EXIF to stream")
            Result.failure(e)
        }
    }

    private fun applyMetadata(exif: ExifInterface, metadata: ExifMetadata) {
        // 基础信息
        exif.setAttribute(ExifInterface.TAG_MAKE, metadata.make ?: Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, metadata.model ?: Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, metadata.software ?: APP_NAME)

        // 拍摄时间
        metadata.dateTime?.let { date ->
            val dateTimeFormat = SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val dateTimeStr = dateTimeFormat.format(date)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTimeStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTimeStr)
        }

        // 图像尺寸
        metadata.imageWidth?.let {
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, it.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, it.toString())
        }
        metadata.imageHeight?.let {
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, it.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, it.toString())
        }

        // 拍摄参数
        metadata.iso?.let {
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
        }
        metadata.exposureTime?.let {
            // EXIF 使用分数形式的快门速度
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, it.toString())
        }
        metadata.fNumber?.let {
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
        }
        metadata.focalLength?.let {
            // 使用有理数格式 (分子/分母)
            val rational = "${(it * 1000).toInt()}/1000"
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, rational)
        }
        metadata.focalLength35mm?.let {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString())
        }

        // 曝光模式
        metadata.exposureMode?.let {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, it.toString())
        }
        metadata.whiteBalance?.let {
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, it.toString())
        }
        metadata.flash?.let {
            exif.setAttribute(ExifInterface.TAG_FLASH, it.toString())
        }

        // 地理位置
        metadata.location?.let { location ->
            exif.setGpsInfo(location)
        }
        
        // 或者使用经纬度
        if (metadata.location == null) {
            val lat = metadata.latitude
            val lon = metadata.longitude
            if (lat != null && lon != null) {
                exif.setLatLong(lat, lon)
                metadata.altitude?.let {
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${it.toInt()}/1")
                    exif.setAttribute(
                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                        if (it >= 0) "0" else "1"
                    )
                }
            }
        }

        // 方向
        metadata.orientation?.let {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString())
        }

        // 自定义标签 - 使用 UserComment 存储 LUT 信息
        metadata.lutName?.let { lutName ->
            val userComment = "LUT: $lutName, Intensity: ${metadata.lutIntensity ?: 100}%"
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment)
        }

        // 图像描述
        metadata.imageDescription?.let {
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, it)
        }

        // 色彩空间
        metadata.colorSpace?.let {
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, it.toString())
        }
    }

    /**
     * 从文件读取 EXIF 信息
     */
    fun readExif(filePath: String): Result<ExifMetadata> {
        return try {
            val exif = ExifInterface(filePath)
            val metadata = ExifMetadata(
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                dateTime = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME)),
                imageWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 },
                imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 },
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 },
                fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
                focalLength = parseFocalLength(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)),
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ).apply {
                val latLong = exif.latLong
                if (latLong != null) {
                    latitude = latLong[0]
                    longitude = latLong[1]
                }
            }
            Result.success(metadata)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read EXIF")
            Result.failure(e)
        }
    }

    private fun parseExifDate(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US).parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFocalLength(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        return try {
            if (value.contains("/")) {
                val parts = value.split("/")
                parts[0].toDouble() / parts[1].toDouble()
            } else {
                value.toDouble()
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val APP_NAME = "Luma Camera"
        private const val EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss"
    }
}

/**
 * EXIF 元数据
 */
data class ExifMetadata(
    // 设备信息
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,

    // 时间
    val dateTime: Date? = null,

    // 图像尺寸
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,

    // 拍摄参数
    val iso: Int? = null,
    val exposureTime: Double? = null,  // 秒
    val fNumber: Double? = null,       // 光圈值 f/x.x
    val focalLength: Double? = null,   // 实际焦距 mm
    val focalLength35mm: Int? = null,  // 35mm 等效焦距

    // 曝光模式
    val exposureMode: Int? = null,     // 0=Auto, 1=Manual
    val whiteBalance: Int? = null,     // 0=Auto, 1=Manual
    val flash: Int? = null,            // 0=No flash, 1=Flash fired

    // 地理位置
    val location: Location? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    val altitude: Double? = null,

    // 方向
    val orientation: Int? = null,

    // 自定义
    val lutName: String? = null,
    val lutIntensity: Int? = null,
    val imageDescription: String? = null,

    // 色彩空间 (1=sRGB, 2=Adobe RGB, 65535=Uncalibrated)
    val colorSpace: Int? = null
)
