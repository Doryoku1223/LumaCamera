package com.luma.camera.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * 图像保存管理器
 *
 * 负责协调所有图像保存操作：
 * - JPEG 保存 < 200ms
 * - RAW+JPEG 保存 < 800ms
 * - 支持 HEIC 编码
 * - 异步队列处理
 */
@Singleton
class ImageSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreHelper: MediaStoreHelper,
    private val exifWriter: ExifWriter,
    private val dngWriter: DngWriter,
    private val heicEncoder: HeicEncoder
) {
    private val saveScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "ImageSaver coroutine error")
        }
    )

    private val pendingTasks = ConcurrentLinkedQueue<SaveTask>()
    private val activeTasks = AtomicInteger(0)

    /**
     * 保存回调接口
     */
    interface SaveCallback {
        fun onSaveStarted(taskId: String)
        fun onSaveProgress(taskId: String, progress: Float)
        fun onSaveCompleted(taskId: String, uri: Uri)
        fun onSaveFailed(taskId: String, error: Throwable)
    }

    private var saveCallback: SaveCallback? = null

    fun setSaveCallback(callback: SaveCallback?) {
        saveCallback = callback
    }

    /**
     * 保存 JPEG 图像
     *
     * 目标：< 200ms
     *
     * @param jpegData JPEG 字节数据
     * @param metadata EXIF 元数据
     * @param fileName 文件名（可选，自动生成）
     * @return 保存结果
     */
    suspend fun saveJpeg(
        jpegData: ByteArray,
        metadata: ExifMetadata? = null,
        fileName: String? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val taskId = generateTaskId()
        val displayName = fileName ?: generateFileName("IMG")

        Timber.d("Starting JPEG save: $displayName, size=${jpegData.size / 1024}KB")
        saveCallback?.onSaveStarted(taskId)

        val elapsed = measureTimeMillis {
            try {
                // 创建 MediaStore 条目
                val (uri, outputStream) = mediaStoreHelper.createImageFile(
                    displayName,
                    MediaStoreHelper.MIME_TYPE_JPEG
                ).getOrThrow()

                saveCallback?.onSaveProgress(taskId, 0.3f)

                // 写入 JPEG 数据
                outputStream.use { it.write(jpegData) }
                saveCallback?.onSaveProgress(taskId, 0.7f)

                // 完成待处理状态
                mediaStoreHelper.finishPendingFile(uri).getOrThrow()

                // 更新 MediaStore 元数据
                metadata?.let { meta ->
                    mediaStoreHelper.updateImageMetadata(
                        uri,
                        width = meta.imageWidth,
                        height = meta.imageHeight,
                        dateTaken = meta.dateTime?.time,
                        latitude = meta.latitude,
                        longitude = meta.longitude
                    )
                }

                saveCallback?.onSaveProgress(taskId, 1.0f)
                saveCallback?.onSaveCompleted(taskId, uri)

                Timber.d("JPEG saved successfully: $uri")
                return@withContext Result.success(uri)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save JPEG")
                saveCallback?.onSaveFailed(taskId, e)
                return@withContext Result.failure(e)
            }
        }

        Timber.d("JPEG save completed in ${elapsed}ms (target: <200ms)")
        Result.failure(Exception("Unreachable"))
    }

    /**
     * 保存 Bitmap 为 JPEG
     */
    suspend fun saveBitmapAsJpeg(
        bitmap: Bitmap,
        quality: Int = 95,
        metadata: ExifMetadata? = null,
        fileName: String? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val jpegData = outputStream.toByteArray()

            // 添加图像尺寸到元数据
            val fullMetadata = metadata?.copy(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            ) ?: ExifMetadata(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                dateTime = Date()
            )

            saveJpeg(jpegData, fullMetadata, fileName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存 HEIC 图像
     */
    suspend fun saveHeic(
        bitmap: Bitmap,
        quality: Int = 95,
        metadata: ExifMetadata? = null,
        fileName: String? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val taskId = generateTaskId()
        val displayName = fileName ?: generateFileName("IMG")

        if (!heicEncoder.isHeicSupported()) {
            // 降级到 JPEG
            Timber.w("HEIC not supported, falling back to JPEG")
            return@withContext saveBitmapAsJpeg(bitmap, quality, metadata, fileName)
        }

        try {
            saveCallback?.onSaveStarted(taskId)

            val (uri, outputStream) = mediaStoreHelper.createImageFile(
                displayName,
                MediaStoreHelper.MIME_TYPE_HEIC
            ).getOrThrow()

            saveCallback?.onSaveProgress(taskId, 0.3f)

            outputStream.use {
                heicEncoder.encodeToHeic(bitmap, it, quality).getOrThrow()
            }

            saveCallback?.onSaveProgress(taskId, 0.8f)

            mediaStoreHelper.finishPendingFile(uri).getOrThrow()

            saveCallback?.onSaveProgress(taskId, 1.0f)
            saveCallback?.onSaveCompleted(taskId, uri)

            Result.success(uri)
        } catch (e: Exception) {
            saveCallback?.onSaveFailed(taskId, e)
            Result.failure(e)
        }
    }

    /**
     * 保存 RAW (DNG) 图像
     */
    suspend fun saveRaw(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        orientation: Int = 0,
        metadata: DngMetadata? = null,
        fileName: String? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val taskId = generateTaskId()
        val displayName = fileName ?: generateFileName("RAW")

        try {
            saveCallback?.onSaveStarted(taskId)

            val (uri, outputStream) = mediaStoreHelper.createImageFile(
                displayName,
                MediaStoreHelper.MIME_TYPE_DNG
            ).getOrThrow()

            saveCallback?.onSaveProgress(taskId, 0.2f)

            outputStream.use {
                dngWriter.writeDng(
                    it, image, characteristics, captureResult, orientation, metadata
                ).getOrThrow()
            }

            saveCallback?.onSaveProgress(taskId, 0.9f)

            mediaStoreHelper.finishPendingFile(uri).getOrThrow()

            saveCallback?.onSaveProgress(taskId, 1.0f)
            saveCallback?.onSaveCompleted(taskId, uri)

            Timber.d("RAW saved: $uri")
            Result.success(uri)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save RAW")
            saveCallback?.onSaveFailed(taskId, e)
            Result.failure(e)
        }
    }

    /**
     * 同时保存 RAW+JPEG
     *
     * 目标：< 800ms
     *
     * @param rawImage RAW_SENSOR 格式的 Image
     * @param jpegData 处理后的 JPEG 数据
     * @param characteristics 相机特性
     * @param captureResult 拍摄结果
     * @param metadata EXIF 元数据
     */
    suspend fun saveRawPlusJpeg(
        rawImage: Image,
        jpegData: ByteArray,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        metadata: ExifMetadata? = null,
        orientation: Int = 0
    ): Result<Pair<Uri, Uri>> = withContext(Dispatchers.IO) {
        val taskId = generateTaskId()
        val baseName = generateFileName("BURST")

        Timber.d("Starting RAW+JPEG save: $baseName")
        saveCallback?.onSaveStarted(taskId)

        val startTime = System.currentTimeMillis()

        try {
            // 并行保存 RAW 和 JPEG
            val rawDeferred = async {
                saveRaw(
                    rawImage,
                    characteristics,
                    captureResult,
                    orientation,
                    DngMetadata(
                        description = metadata?.imageDescription,
                        location = metadata?.location
                    ),
                    baseName
                )
            }

            val jpegDeferred = async {
                saveJpeg(jpegData, metadata, baseName)
            }

            saveCallback?.onSaveProgress(taskId, 0.5f)

            val rawResult = rawDeferred.await()
            val jpegResult = jpegDeferred.await()

            if (rawResult.isFailure) {
                return@withContext Result.failure(
                    rawResult.exceptionOrNull() ?: Exception("RAW save failed")
                )
            }
            if (jpegResult.isFailure) {
                return@withContext Result.failure(
                    jpegResult.exceptionOrNull() ?: Exception("JPEG save failed")
                )
            }

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("RAW+JPEG saved in ${elapsed}ms (target: <800ms)")

            saveCallback?.onSaveProgress(taskId, 1.0f)
            saveCallback?.onSaveCompleted(taskId, jpegResult.getOrThrow())

            Result.success(Pair(rawResult.getOrThrow(), jpegResult.getOrThrow()))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save RAW+JPEG")
            saveCallback?.onSaveFailed(taskId, e)
            Result.failure(e)
        }
    }

    /**
     * 保存 JPEG Image 对象
     */
    suspend fun saveJpegImage(
        image: Image,
        metadata: ExifMetadata? = null,
        fileName: String? = null
    ): Result<Uri> {
        require(image.format == ImageFormat.JPEG) {
            "Image must be JPEG format"
        }

        val buffer = image.planes[0].buffer
        val jpegData = ByteArray(buffer.remaining())
        buffer.get(jpegData)

        return saveJpeg(jpegData, metadata, fileName)
    }

    /**
     * 获取当前待处理任务数
     */
    fun getPendingTaskCount(): Int = pendingTasks.size + activeTasks.get()

    /**
     * 取消所有待处理任务
     */
    fun cancelAllTasks() {
        pendingTasks.clear()
        saveScope.coroutineContext.cancelChildren()
        Timber.d("All save tasks cancelled")
    }

    /**
     * 清理资源
     */
    fun release() {
        cancelAllTasks()
        saveScope.cancel()
    }

    private fun generateTaskId(): String = UUID.randomUUID().toString().take(8)

    private fun generateFileName(prefix: String): String {
        val timestamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            java.util.Locale.US
        ).format(Date())
        return "${prefix}_$timestamp"
    }

    /**
     * 保存任务数据类
     */
    private data class SaveTask(
        val id: String,
        val type: SaveType,
        val data: Any,
        val metadata: Any?
    )

    private enum class SaveType {
        JPEG, HEIC, RAW, RAW_PLUS_JPEG
    }

    companion object {
        private const val MAX_CONCURRENT_SAVES = 3
    }
}
