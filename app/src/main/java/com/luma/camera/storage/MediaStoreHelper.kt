@file:Suppress("DEPRECATION")

package com.luma.camera.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore 封装类
 *
 * 负责与 Android MediaStore API 交互
 * 支持 Android 10+ Scoped Storage
 */
@Singleton
class MediaStoreHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver
    
    /**
     * 获取 ContentResolver（用于 EXIF 写入等操作）
     */
    fun getContentResolver(): ContentResolver = contentResolver

    /**
     * 创建图片文件并返回输出流
     *
     * @param displayName 文件名（不含扩展名）
     * @param mimeType MIME 类型 (image/jpeg, image/heif, image/x-adobe-dng)
     * @param relativePath 相对路径（默认 DCIM/LumaCamera）
     * @return Pair<Uri, OutputStream> 用于写入数据
     */
    fun createImageFile(
        displayName: String,
        mimeType: String,
        relativePath: String = "$DCIM_FOLDER/$APP_FOLDER"
    ): Result<Pair<Uri, OutputStream>> {
        return try {
            val extension = getExtensionForMimeType(mimeType)
            val fullName = "$displayName.$extension"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fullName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                
                // 标记为待处理状态，防止其他应用访问未完成的文件
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return Result.failure(Exception("Failed to create MediaStore entry"))

            val outputStream = contentResolver.openOutputStream(uri)
                ?: return Result.failure(Exception("Failed to open output stream"))

            Timber.d("Created image file: $fullName at $uri")
            Result.success(Pair(uri, outputStream))
        } catch (e: Exception) {
            Timber.e(e, "Failed to create image file")
            Result.failure(e)
        }
    }

    /**
     * 完成文件写入，将 IS_PENDING 设置为 0
     */
    fun finishPendingFile(uri: Uri): Result<Unit> {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, contentValues, null, null)
            Timber.d("Finished pending file: $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to finish pending file")
            Result.failure(e)
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(uri: Uri): Result<Unit> {
        return try {
            contentResolver.delete(uri, null, null)
            Timber.d("Deleted file: $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete file")
            Result.failure(e)
        }
    }

    /**
     * 更新文件的 EXIF 相关元数据
     */
    fun updateImageMetadata(
        uri: Uri,
        width: Int? = null,
        height: Int? = null,
        dateTaken: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<Unit> {
        return try {
            val contentValues = ContentValues().apply {
                width?.let { put(MediaStore.Images.Media.WIDTH, it) }
                height?.let { put(MediaStore.Images.Media.HEIGHT, it) }
                dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
                latitude?.let { put(MediaStore.Images.Media.LATITUDE, it) }
                longitude?.let { put(MediaStore.Images.Media.LONGITUDE, it) }
            }
            
            if (contentValues.size() > 0) {
                contentResolver.update(uri, contentValues, null, null)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update image metadata")
            Result.failure(e)
        }
    }

    /**
     * 创建视频文件并返回输出流
     */
    fun createVideoFile(
        displayName: String,
        mimeType: String = MIME_TYPE_MP4,
        relativePath: String = "$DCIM_FOLDER/$APP_FOLDER"
    ): Result<Pair<Uri, OutputStream>> {
        return try {
            val extension = getExtensionForMimeType(mimeType)
            val fullName = "$displayName.$extension"

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fullName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return Result.failure(Exception("Failed to create video MediaStore entry"))

            val outputStream = contentResolver.openOutputStream(uri)
                ?: return Result.failure(Exception("Failed to open video output stream"))

            Timber.d("Created video file: $fullName at $uri")
            Result.success(Pair(uri, outputStream))
        } catch (e: Exception) {
            Timber.e(e, "Failed to create video file")
            Result.failure(e)
        }
    }

    private fun getExtensionForMimeType(mimeType: String): String {
        return when (mimeType) {
            MIME_TYPE_JPEG -> "jpg"
            MIME_TYPE_HEIF, MIME_TYPE_HEIC -> "heic"
            MIME_TYPE_DNG -> "dng"
            MIME_TYPE_PNG -> "png"
            MIME_TYPE_MP4 -> "mp4"
            MIME_TYPE_MOV -> "mov"
            else -> "jpg"
        }
    }
    
    /**
     * 获取实况照片输出目录
     */
    fun getLivePhotoOutputDir(): java.io.File {
        val dir = java.io.File(context.cacheDir, "livephoto")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * 将实况照片添加到媒体库
     * Motion Photo 格式：单个 JPEG 文件，视频数据嵌入在末尾
     */
    suspend fun addLivePhotoToMediaStore(
        photoFile: java.io.File,
        videoFile: java.io.File
    ) {
        try {
            // Motion Photo ??????? MVIMG_ JPEG?????????
            val isMotionPhoto = photoFile.name.endsWith("MP.jpg") || photoFile.name.endsWith("MP.jpeg") || photoFile.absolutePath == videoFile.absolutePath
            
            if (isMotionPhoto) {
                val photoResult = createImageFile(
                    displayName = photoFile.nameWithoutExtension,
                    mimeType = MIME_TYPE_JPEG
                )
                
                photoResult.onSuccess { (uri, outputStream) ->
                    outputStream.use { stream ->
                        photoFile.inputStream().use { input ->
                            input.copyTo(stream)
                        }
                    }
                    finishPendingFile(uri)
                    Timber.d("Motion Photo added to MediaStore: $uri")
                }

                if (videoFile.exists() && videoFile.absolutePath != photoFile.absolutePath) {
                    val videoResult = createVideoFile(
                        displayName = videoFile.nameWithoutExtension,
                        mimeType = MIME_TYPE_MP4
                    )
                    videoResult.onSuccess { (uri, outputStream) ->
                        outputStream.use { stream ->
                            videoFile.inputStream().use { input ->
                                input.copyTo(stream)
                            }
                        }
                        finishPendingFile(uri)
                        Timber.d("Motion Photo video added to MediaStore: $uri")
                    }
                }

                photoFile.delete()
                if (videoFile.absolutePath != photoFile.absolutePath) {
                    videoFile.delete()
                }
            } else {
                // ??? - ?????????
                val photoResult = createImageFile(
                    displayName = photoFile.nameWithoutExtension,
                    mimeType = MIME_TYPE_HEIC
                )
                
                photoResult.onSuccess { (uri, outputStream) ->
                    outputStream.use { stream ->
                        photoFile.inputStream().use { input ->
                            input.copyTo(stream)
                        }
                    }
                    finishPendingFile(uri)
                    Timber.d("Live photo added to MediaStore: $uri")
                }
                
                val videoResult = createVideoFile(
                    displayName = videoFile.nameWithoutExtension,
                    mimeType = MIME_TYPE_MP4
                )
                
                videoResult.onSuccess { (uri, outputStream) ->
                    outputStream.use { stream ->
                        videoFile.inputStream().use { input ->
                            input.copyTo(stream)
                        }
                    }
                    finishPendingFile(uri)
                    Timber.d("Live photo video added to MediaStore: $uri")
                }
                
                photoFile.delete()
                videoFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to add live photo to MediaStore")
        }
    }

    companion object {
        val DCIM_FOLDER: String = Environment.DIRECTORY_DCIM
        const val APP_FOLDER = "LumaCamera"

        const val MIME_TYPE_JPEG = "image/jpeg"
        const val MIME_TYPE_HEIF = "image/heif"
        const val MIME_TYPE_HEIC = "image/heic"
        const val MIME_TYPE_DNG = "image/x-adobe-dng"
        const val MIME_TYPE_PNG = "image/png"
        const val MIME_TYPE_MP4 = "video/mp4"
        const val MIME_TYPE_MOV = "video/quicktime"
    }
}
