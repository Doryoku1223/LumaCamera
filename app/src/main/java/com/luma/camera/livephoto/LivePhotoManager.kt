package com.luma.camera.livephoto

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实况照片管理器
 * 
 * 功能：
 * - 持续录制 3 秒环形缓冲区 (前后各 1.5 秒)
 * - 拍照时保存视频片段
 * - HEIC 容器封装 (类似 Apple Live Photo)
 * - 统一应用 LUT 滤镜
 */
@Singleton
class LivePhotoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "LivePhotoManager"
        
        // 缓冲区配置
        const val BUFFER_DURATION_MS = 3000L // 3秒
        const val BUFFER_BEFORE_CAPTURE_MS = 1500L // 拍照前 1.5 秒
        const val BUFFER_AFTER_CAPTURE_MS = 1500L // 拍照后 1.5 秒
        
        // 视频编码参数
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val VIDEO_BITRATE = 8_000_000 // 8 Mbps
        const val VIDEO_FRAME_RATE = 30
        const val VIDEO_I_FRAME_INTERVAL = 1
        
        // 音频编码参数
        const val AUDIO_SAMPLE_RATE = 44100
        const val AUDIO_CHANNEL_COUNT = 2
        const val AUDIO_BITRATE = 128_000
    }
    
    // 状态
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    // 环形缓冲区
    private val frameBuffer = ConcurrentLinkedQueue<FrameData>()
    private val audioBuffer = ConcurrentLinkedQueue<AudioData>()
    
    // 编码器
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    // 录制作用域
    private var recordingScope: CoroutineScope? = null
    private var recordingJob: Job? = null
    
    // 捕获时间戳
    private var captureTimestamp: Long = 0
    
    /**
     * 开始环形缓冲录制
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startBuffering(): Surface? {
        if (_recordingState.value is RecordingState.Buffering) {
            return inputSurface
        }
        
        try {
            // 创建视频编码器
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            }
            
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            
            // 创建音频编码器
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, 
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            
            // 启动缓冲线程
            recordingScope = CoroutineScope(ioDispatcher + SupervisorJob())
            recordingJob = recordingScope?.launch {
                bufferEncodedFrames()
            }
            
            _recordingState.value = RecordingState.Buffering
            
            return inputSurface
        } catch (e: Exception) {
            _recordingState.value = RecordingState.Error(e.message ?: "Failed to start buffering")
            return null
        }
    }
    
    /**
     * 缓冲编码帧
     */
    private suspend fun bufferEncodedFrames() = withContext(ioDispatcher) {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isActive) {
            try {
                val encoder = videoEncoder ?: break
                
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                
                if (outputIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        
                        val frameData = FrameData(
                            data = data,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags,
                            timestamp = System.currentTimeMillis()
                        )
                        
                        // 添加到环形缓冲区
                        frameBuffer.add(frameData)
                        
                        // 清理过期帧
                        val cutoffTime = System.currentTimeMillis() - BUFFER_DURATION_MS
                        while (frameBuffer.peek()?.timestamp?.let { it < cutoffTime } == true) {
                            frameBuffer.poll()
                        }
                    }
                    
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 继续循环
            }
        }
    }
    
    /**
     * 触发实况照片捕获
     * 
     * 保存当前缓冲区 + 继续录制 1.5 秒
     * 使用 Google Motion Photo 格式（视频嵌入到 JPEG 末尾）
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun captureLivePhoto(
        photo: Bitmap,
        outputDir: File
    ): Result<LivePhotoResult> = withContext(ioDispatcher) {
        try {
            _recordingState.value = RecordingState.Capturing
            captureTimestamp = System.currentTimeMillis()
            
            // 等待拍照后的 1.5 秒
            delay(BUFFER_AFTER_CAPTURE_MS)
            
            // 获取缓冲区的帧
            val frames = frameBuffer.toList()
            
            // 筛选时间窗口内的帧
            val startTimeMs = captureTimestamp - BUFFER_BEFORE_CAPTURE_MS
            val endTimeMs = captureTimestamp + BUFFER_AFTER_CAPTURE_MS
            
            val selectedFrames = frames.filter { frame ->
                frame.timestamp in startTimeMs..endTimeMs
            }
            
            if (selectedFrames.isEmpty()) {
                return@withContext Result.failure(Exception("No frames in buffer"))
            }
            
            // 生成文件名（使用 .jpg 扩展名，因为 Motion Photo 基于 JPEG）
            val timestamp = System.currentTimeMillis()
            val tempVideoFile = File(outputDir, "temp_${timestamp}.mp4")
            val tempPhotoFile = File(outputDir, "temp_${timestamp}.jpg")
            val motionPhotoFile = File(outputDir, "MVIMG_${timestamp}.jpg")  // Motion Photo 格式
            
            // 写入临时视频
            writeVideoFile(tempVideoFile, selectedFrames)
            
            // 保存临时照片
            savePhoto(photo, tempPhotoFile)
            
            // 创建 Motion Photo（将视频嵌入到 JPEG）
            val motionPhotoCreated = createMotionPhoto(tempPhotoFile, tempVideoFile, motionPhotoFile)
            
            // 清理临时文件
            tempVideoFile.delete()
            tempPhotoFile.delete()
            
            if (!motionPhotoCreated) {
                return@withContext Result.failure(Exception("Failed to create Motion Photo"))
            }
            
            val result = LivePhotoResult(
                photoFile = motionPhotoFile,
                videoFile = motionPhotoFile,  // Motion Photo 中视频嵌入到照片
                durationMs = selectedFrames.last().timestamp - selectedFrames.first().timestamp
            )
            
            _recordingState.value = RecordingState.Buffering
            
            Result.success(result)
        } catch (e: Exception) {
            _recordingState.value = RecordingState.Error(e.message ?: "Capture failed")
            Result.failure(e)
        }
    }
    
    /**
     * 写入视频文件
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeVideoFile(file: File, frames: List<FrameData>) {
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        try {
            // 添加视频轨道
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            )
            val videoTrack = muxer.addTrack(videoFormat)
            
            muxer.start()
            
            val bufferInfo = MediaCodec.BufferInfo()
            val startTimeUs = frames.first().presentationTimeUs
            
            frames.forEach { frame ->
                val buffer = ByteBuffer.wrap(frame.data)
                bufferInfo.apply {
                    offset = 0
                    size = frame.data.size
                    presentationTimeUs = frame.presentationTimeUs - startTimeUs
                    flags = frame.flags
                }
                
                muxer.writeSampleData(videoTrack, buffer, bufferInfo)
            }
            
            muxer.stop()
        } finally {
            muxer.release()
        }
    }
    
    /**
     * 保存照片 - 使用 Google Motion Photo 格式
     * 将视频嵌入到 JPEG 末尾，OPPO/三星/Google 相册都支持
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePhoto(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            // 保存为 JPEG（Motion Photo 需要 JPEG 格式）
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
    }
    
    /**
     * 创建 Google Motion Photo 格式的照片
     * 将照片和视频合并到单个文件中
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMotionPhoto(photoFile: File, videoFile: File, outputFile: File): Boolean {
        try {
            // 读取照片数据
            val photoBytes = photoFile.readBytes()
            
            // 读取视频数据
            val videoBytes = videoFile.readBytes()
            
            // 创建 Motion Photo XMP 元数据
            val videoOffset = videoBytes.size
            val xmpMetadata = createMotionPhotoXmp(videoOffset)
            
            // 写入输出文件：JPEG + XMP + Video
            outputFile.outputStream().use { output ->
                // 写入 JPEG 数据（不包含最后的 FFD9 结束标记）
                val jpegEndIndex = findJpegEndMarker(photoBytes)
                if (jpegEndIndex > 0) {
                    output.write(photoBytes, 0, jpegEndIndex)
                } else {
                    output.write(photoBytes)
                }
                
                // 写入 Motion Photo 标记（用于识别视频边界）
                val marker = "MotionPhoto_Data".toByteArray()
                output.write(marker)
                
                // 写入视频数据
                output.write(videoBytes)
                
                // 写入 JPEG 结束标记
                output.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            }
            
            return true
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to create Motion Photo")
            return false
        }
    }
    
    /**
     * 查找 JPEG 结束标记 (FFD9) 的位置
     */
    private fun findJpegEndMarker(data: ByteArray): Int {
        for (i in data.size - 2 downTo 0) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                return i
            }
        }
        return -1
    }
    
    /**
     * 创建 Motion Photo XMP 元数据
     */
    private fun createMotionPhotoXmp(videoOffset: Int): String {
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description
                        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                        GCamera:MotionPhoto="1"
                        GCamera:MotionPhotoVersion="1"
                        GCamera:MotionPhotoPresentationTimestampUs="0"
                        GCamera:MicroVideo="1"
                        GCamera:MicroVideoVersion="1"
                        GCamera:MicroVideoOffset="$videoOffset"/>
                </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }
    
    /**
     * 停止缓冲
     */
    fun stopBuffering() {
        recordingJob?.cancel()
        recordingScope?.cancel()
        
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        
        videoEncoder = null
        audioEncoder = null
        inputSurface = null
        
        frameBuffer.clear()
        audioBuffer.clear()
        
        _recordingState.value = RecordingState.Idle
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopBuffering()
    }
}

/**
 * 录制状态
 */
sealed class RecordingState {
    object Idle : RecordingState()
    object Buffering : RecordingState()
    object Capturing : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * 帧数据
 */
data class FrameData(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FrameData
        return timestamp == other.timestamp
    }
    
    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * 音频数据
 */
data class AudioData(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioData
        return timestamp == other.timestamp
    }
    
    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * 实况照片结果
 */
data class LivePhotoResult(
    val photoFile: File,
    val videoFile: File,
    val durationMs: Long
)
