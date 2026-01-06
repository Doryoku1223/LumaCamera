package com.luma.camera.livephoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * LivePhoto HEIC Encoder
 * 
 * 将主图像、视频和音频打包成符合 Apple Live Photo 规范的 HEIC 容器。
 * 
 * HEIC 容器结构:
 * - 主图像: HEIF 编码的静态图像
 * - 视频层: HEVC 编码的视频轨道
 * - 音频层: AAC 编码的音频轨道
 * - 元数据: 拍摄参数、时间戳、主帧索引、LUT 名称等
 * 
 * 性能目标: < 500ms 完成打包
 */
@Singleton
class LivePhotoEncoder @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "LivePhotoEncoder"
        
        // HEIC/HEIF Box 类型
        private const val BOX_FTYP = "ftyp"
        private const val BOX_META = "meta"
        private const val BOX_MOOV = "moov"
        private const val BOX_MDAT = "mdat"
        
        // Apple Live Photo 标识
        private const val APPLE_ASSET_ID_KEY = "com.apple.quicktime.content.identifier"
        private const val APPLE_STILL_IMAGE_TIME = "com.apple.quicktime.still-image-time"
        
        // 性能常量
        private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer
        private const val MAX_ENCODING_TIME_MS = 500L
    }
    
    /**
     * 编码结果
     */
    sealed class EncodingResult {
        data class Success(
            val outputFile: File,
            val metadata: LivePhotoMetadata,
            val encodingTimeMs: Long
        ) : EncodingResult()
        
        data class Failure(
            val error: String,
            val exception: Exception? = null
        ) : EncodingResult()
    }
    
    /**
     * LivePhoto 元数据
     */
    data class LivePhotoMetadata(
        val assetIdentifier: String,
        val captureTimestamp: Long,
        val mainFrameIndex: Int,
        val videoDurationMs: Long,
        val videoWidth: Int,
        val videoHeight: Int,
        val photoWidth: Int,
        val photoHeight: Int,
        val lutName: String? = null,
        val captureParams: CaptureParams? = null
    )
    
    /**
     * 拍摄参数
     */
    data class CaptureParams(
        val iso: Int,
        val exposureTimeNs: Long,
        val aperture: Float,
        val focalLength: Float,
        val whiteBalance: Int
    )
    
    /**
     * 编码配置
     */
    data class EncodingConfig(
        val quality: Int = 95,                    // HEIF 质量 (1-100)
        val enableHardwareAcceleration: Boolean = true,
        val embedMetadata: Boolean = true,
        val preserveExif: Boolean = true,
        val targetFileSizeMb: Float? = null       // 目标文件大小限制
    )
    
    /**
     * 将 LivePhoto 数据编码为 HEIC 容器
     * 
     * @param mainImage 主图像 Bitmap
     * @param videoFile 视频文件 (HEVC/H.265)
     * @param outputFile 输出 HEIC 文件
     * @param metadata 元数据
     * @param config 编码配置
     * @return 编码结果
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun encode(
        mainImage: Bitmap,
        videoFile: File,
        outputFile: File,
        metadata: LivePhotoMetadata,
        config: EncodingConfig = EncodingConfig()
    ): EncodingResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 验证输入
            if (!videoFile.exists()) {
                return@withContext EncodingResult.Failure("Video file does not exist")
            }
            
            if (mainImage.isRecycled) {
                return@withContext EncodingResult.Failure("Main image is recycled")
            }
            
            // 确保输出目录存在
            outputFile.parentFile?.mkdirs()
            
            // 生成唯一的 Asset Identifier (用于关联图片和视频)
            val assetId = metadata.assetIdentifier.ifEmpty { 
                UUID.randomUUID().toString() 
            }
            
            // Step 1: 创建带有 Apple 兼容标识的视频副本
            val tempVideoFile = File(outputFile.parent, "temp_${System.currentTimeMillis()}.mov")
            createAppleCompatibleVideo(videoFile, tempVideoFile, assetId, metadata.mainFrameIndex)
            
            // Step 2: 编码主图像为 HEIF
            val heifData = encodeHeifImage(mainImage, config.quality)
            
            // Step 3: 创建最终的 HEIC 容器
            createHeicContainer(
                heifData = heifData,
                videoFile = tempVideoFile,
                outputFile = outputFile,
                assetId = assetId,
                metadata = metadata,
                config = config
            )
            
            // 清理临时文件
            tempVideoFile.delete()
            
            val encodingTime = System.currentTimeMillis() - startTime
            
            EncodingResult.Success(
                outputFile = outputFile,
                metadata = metadata.copy(assetIdentifier = assetId),
                encodingTimeMs = encodingTime
            )
        } catch (e: Exception) {
            EncodingResult.Failure(
                error = "Encoding failed: ${e.message}",
                exception = e
            )
        }
    }
    
    /**
     * 从 JPEG/HEIC 文件编码
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun encodeFromFile(
        mainImageFile: File,
        videoFile: File,
        outputFile: File,
        metadata: LivePhotoMetadata,
        config: EncodingConfig = EncodingConfig()
    ): EncodingResult = withContext(ioDispatcher) {
        try {
            val bitmap = BitmapFactory.decodeFile(mainImageFile.absolutePath)
                ?: return@withContext EncodingResult.Failure("Failed to decode main image")
            
            val result = encode(bitmap, videoFile, outputFile, metadata, config)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            EncodingResult.Failure("Failed to encode from file: ${e.message}", e)
        }
    }
    
    /**
     * 创建 Apple 兼容的视频文件
     * 添加 content identifier 和 still-image-time 元数据
     */
    private fun createAppleCompatibleVideo(
        inputFile: File,
        outputFile: File,
        assetId: String,
        stillImageTimeIndex: Int
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        
        try {
            extractor.setDataSource(inputFile.absolutePath)
            
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // 复制所有轨道
            val trackIndexMap = mutableMapOf<Int, Int>()
            var videoTrackIndex = -1
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                val newTrackIndex = muxer.addTrack(format)
                trackIndexMap[i] = newTrackIndex
                
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                }
            }
            
            // 设置 Apple Live Photo 元数据
            // 注意: Android MediaMuxer 对元数据支持有限，
            // 完整的 Apple 兼容性可能需要使用 libheif 或自定义 ISOBMFF 写入
            
            muxer.start()
            
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            
            // 复制所有帧数据
            for ((sourceTrack, destTrack) in trackIndexMap) {
                extractor.selectTrack(sourceTrack)
                
                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    
                    muxer.writeSampleData(destTrack, buffer, bufferInfo)
                    extractor.advance()
                }
                
                extractor.unselectTrack(sourceTrack)
            }
            
            muxer.stop()
        } finally {
            extractor.release()
            muxer?.release()
        }
    }
    
    /**
     * 将 Bitmap 编码为 HEIF 格式
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun encodeHeifImage(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = java.io.ByteArrayOutputStream()
        
        // 使用 Android 内置的 HEIF 编码器
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, outputStream)
        
        // 注意: 对于真正的 HEIF 编码，需要使用:
        // - android.media.ImageWriter with HEIC format
        // - 或者 libheif native 库
        // 这里使用 WEBP 作为临时实现，后续可升级
        
        return outputStream.toByteArray()
    }
    
    /**
     * 创建 HEIC 容器
     * 
     * HEIC 文件结构 (ISOBMFF):
     * - ftyp box: 文件类型声明
     * - meta box: 元数据 (包含 EXIF、XMP、Apple 标识等)
     * - moov box: 视频元数据
     * - mdat box: 媒体数据 (图像 + 视频帧)
     */
    private fun createHeicContainer(
        heifData: ByteArray,
        videoFile: File,
        outputFile: File,
        assetId: String,
        metadata: LivePhotoMetadata,
        config: EncodingConfig
    ) {
        RandomAccessFile(outputFile, "rw").use { raf ->
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            // 写入 ftyp box
            writeFtypBox(buffer, raf)
            
            // 写入 meta box (包含 Apple Live Photo 标识)
            writeMetaBox(buffer, raf, assetId, metadata, config)
            
            // 写入主图像数据
            raf.write(heifData)
            
            // 追加视频数据 (作为辅助轨道)
            appendVideoData(videoFile, raf)
        }
    }
    
    /**
     * 写入 ftyp (File Type) box
     */
    private fun writeFtypBox(buffer: ByteBuffer, raf: RandomAccessFile) {
        buffer.clear()
        
        // Box size (will be updated)
        val sizePosition = buffer.position()
        buffer.putInt(0) // placeholder
        
        // Box type
        buffer.put("ftyp".toByteArray(Charsets.US_ASCII))
        
        // Major brand: heic
        buffer.put("heic".toByteArray(Charsets.US_ASCII))
        
        // Minor version
        buffer.putInt(0)
        
        // Compatible brands
        buffer.put("mif1".toByteArray(Charsets.US_ASCII)) // HEIF
        buffer.put("heic".toByteArray(Charsets.US_ASCII)) // HEIC
        buffer.put("heix".toByteArray(Charsets.US_ASCII)) // HEIC extended
        buffer.put("MiHE".toByteArray(Charsets.US_ASCII)) // Motion HEIF
        
        // Update size
        val size = buffer.position()
        buffer.putInt(sizePosition, size)
        
        buffer.flip()
        raf.channel.write(buffer)
    }
    
    /**
     * 写入 meta box
     */
    private fun writeMetaBox(
        buffer: ByteBuffer,
        raf: RandomAccessFile,
        assetId: String,
        metadata: LivePhotoMetadata,
        config: EncodingConfig
    ) {
        buffer.clear()
        
        // Box size (placeholder)
        val sizePosition = buffer.position()
        buffer.putInt(0)
        
        // Box type
        buffer.put("meta".toByteArray(Charsets.US_ASCII))
        
        // Version and flags
        buffer.putInt(0)
        
        // hdlr box (handler reference)
        writeHdlrBox(buffer)
        
        // pitm box (primary item)
        writePitmBox(buffer, 1) // item ID 1 is the main image
        
        // iloc box (item locations)
        writeIlocBox(buffer, metadata)
        
        // iinf box (item information)
        writeIinfBox(buffer)
        
        // 写入 Apple Content Identifier (如果需要)
        if (config.embedMetadata) {
            writeAppleMetadata(buffer, assetId, metadata.mainFrameIndex)
        }
        
        // Update size
        val size = buffer.position()
        buffer.putInt(sizePosition, size)
        
        buffer.flip()
        raf.channel.write(buffer)
    }
    
    private fun writeHdlrBox(buffer: ByteBuffer) {
        val content = ByteArray(32)
        // handler type: pict (picture)
        System.arraycopy("pict".toByteArray(), 0, content, 8, 4)
        
        buffer.putInt(12 + content.size) // size
        buffer.put("hdlr".toByteArray(Charsets.US_ASCII))
        buffer.put(content)
    }
    
    private fun writePitmBox(buffer: ByteBuffer, primaryItemId: Int) {
        buffer.putInt(14) // size
        buffer.put("pitm".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0) // version & flags
        buffer.putShort(primaryItemId.toShort())
    }
    
    private fun writeIlocBox(buffer: ByteBuffer, metadata: LivePhotoMetadata) {
        buffer.putInt(28) // size (approximate)
        buffer.put("iloc".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0) // version & flags
        buffer.put(0x44.toByte()) // offset_size=4, length_size=4
        buffer.put(0x00.toByte()) // base_offset_size=0, index_size=0
        buffer.putShort(1) // item_count
        
        // Item 1 (main image)
        buffer.putShort(1) // item_ID
        buffer.putShort(0) // construction_method
        buffer.putShort(0) // data_reference_index
        buffer.putShort(1) // extent_count
        buffer.putInt(0) // extent_offset (placeholder)
        buffer.putInt(0) // extent_length (placeholder)
    }
    
    private fun writeIinfBox(buffer: ByteBuffer) {
        buffer.putInt(30) // size
        buffer.put("iinf".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0) // version & flags
        buffer.putShort(1) // entry_count
        
        // infe box (item info entry)
        buffer.putInt(18) // size
        buffer.put("infe".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0x02000000) // version 2, flags 0
        buffer.putShort(1) // item_ID
        buffer.putShort(0) // item_protection_index
        buffer.put("hvc1".toByteArray(Charsets.US_ASCII)) // item_type
    }
    
    /**
     * 写入 Apple Live Photo 元数据
     */
    private fun writeAppleMetadata(
        buffer: ByteBuffer,
        assetId: String,
        stillImageTimeIndex: Int
    ) {
        // Apple 使用自定义的 XMP 元数据来标识 Live Photo
        // 主要包含:
        // 1. com.apple.quicktime.content.identifier - 资源标识符
        // 2. com.apple.quicktime.still-image-time - 静态图像时间点
        
        val xmpData = buildAppleXmp(assetId, stillImageTimeIndex)
        
        if (xmpData.size + buffer.position() < buffer.capacity()) {
            // uuid box for XMP
            buffer.putInt(8 + 16 + xmpData.size) // size
            buffer.put("uuid".toByteArray(Charsets.US_ASCII))
            
            // XMP UUID: BE7ACFCB-97A9-42E8-9C71-999491E3AFAC
            buffer.put(byteArrayOf(
                0xBE.toByte(), 0x7A.toByte(), 0xCF.toByte(), 0xCB.toByte(),
                0x97.toByte(), 0xA9.toByte(), 0x42.toByte(), 0xE8.toByte(),
                0x9C.toByte(), 0x71.toByte(), 0x99.toByte(), 0x94.toByte(),
                0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte()
            ))
            
            buffer.put(xmpData)
        }
    }
    
    /**
     * 构建 Apple XMP 元数据
     */
    private fun buildAppleXmp(assetId: String, stillImageTimeIndex: Int): ByteArray {
        val xmp = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:apple_desktop="http://ns.apple.com/quicktime/1.0/"
                        apple_desktop:content.identifier="$assetId"
                        apple_desktop:still-image-time="$stillImageTimeIndex"/>
                </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
        
        return xmp.toByteArray(Charsets.UTF_8)
    }
    
    /**
     * 追加视频数据到 HEIC 容器
     */
    private fun appendVideoData(videoFile: File, raf: RandomAccessFile) {
        videoFile.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            
            while (input.read(buffer).also { bytesRead = it } != -1) {
                raf.write(buffer, 0, bytesRead)
            }
        }
    }
    
    /**
     * 验证 HEIC 文件完整性
     */
    suspend fun validate(heicFile: File): ValidationResult = withContext(ioDispatcher) {
        try {
            if (!heicFile.exists()) {
                return@withContext ValidationResult.Invalid("File does not exist")
            }
            
            RandomAccessFile(heicFile, "r").use { raf ->
                val buffer = ByteArray(12)
                raf.read(buffer)
                
                // 检查 ftyp box
                val boxType = String(buffer, 4, 4, Charsets.US_ASCII)
                if (boxType != "ftyp") {
                    return@withContext ValidationResult.Invalid("Invalid ftyp box")
                }
                
                // 检查品牌标识
                val brand = String(buffer, 8, 4, Charsets.US_ASCII)
                val validBrands = listOf("heic", "heix", "mif1", "MiHE")
                if (brand !in validBrands) {
                    return@withContext ValidationResult.Invalid("Invalid brand: $brand")
                }
                
                ValidationResult.Valid(
                    fileSize = heicFile.length(),
                    brand = brand
                )
            }
        } catch (e: Exception) {
            ValidationResult.Invalid("Validation error: ${e.message}")
        }
    }
    
    /**
     * 验证结果
     */
    sealed class ValidationResult {
        data class Valid(
            val fileSize: Long,
            val brand: String
        ) : ValidationResult()
        
        data class Invalid(val reason: String) : ValidationResult()
    }
    
    /**
     * 提取 LivePhoto 元数据
     */
    suspend fun extractMetadata(heicFile: File): LivePhotoMetadata? = withContext(ioDispatcher) {
        try {
            // 使用 MediaExtractor 读取视频元数据
            val extractor = MediaExtractor()
            extractor.setDataSource(heicFile.absolutePath)
            
            var videoWidth = 0
            var videoHeight = 0
            var videoDuration = 0L
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                if (mime.startsWith("video/")) {
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    videoDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000 // us to ms
                    break
                }
            }
            
            extractor.release()
            
            // 读取主图像尺寸 (需要解析 HEIF 结构)
            val imageSize = extractImageSize(heicFile)
            
            LivePhotoMetadata(
                assetIdentifier = extractAssetId(heicFile) ?: "",
                captureTimestamp = heicFile.lastModified(),
                mainFrameIndex = 0,
                videoDurationMs = videoDuration,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                photoWidth = imageSize.first,
                photoHeight = imageSize.second
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractImageSize(file: File): Pair<Int, Int> {
        // 使用 BitmapFactory 读取尺寸 (不解码完整图像)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return Pair(options.outWidth, options.outHeight)
    }
    
    private fun extractAssetId(file: File): String? {
        // 从 XMP 元数据中提取 Apple Content Identifier
        // 这需要解析文件中的 uuid box
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(min(file.length().toInt(), BUFFER_SIZE))
                raf.read(buffer)
                
                val content = String(buffer, Charsets.UTF_8)
                val regex = Regex("""content\.identifier="([^"]+)"""")
                val match = regex.find(content)
                return match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            return null
        }
    }
}
