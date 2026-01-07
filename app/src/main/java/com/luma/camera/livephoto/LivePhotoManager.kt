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
 * 瀹炲喌鐓х墖绠＄悊鍣? * 
 * 鍔熻兘锛? * - 鎸佺画褰曞埗 3 绉掔幆褰㈢紦鍐插尯 (鍓嶅悗鍚?1.5 绉?
 * - 鎷嶇収鏃朵繚瀛樿棰戠墖娈? * - HEIC 瀹瑰櫒灏佽 (绫讳技 Apple Live Photo)
 * - 缁熶竴搴旂敤 LUT 婊ら暅
 */
@Singleton
class LivePhotoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "LivePhotoManager"
        
        // 缂撳啿鍖洪厤缃?
        const val BUFFER_DURATION_MS = 3000L // 3s
        const val BUFFER_BEFORE_CAPTURE_MS = 1500L // 1.5s before capture
        const val BUFFER_AFTER_CAPTURE_MS = 1500L // 1.5s after capture
        // 瑙嗛缂栫爜鍙傛暟
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val VIDEO_BITRATE = 8_000_000 // 8 Mbps
        const val VIDEO_FRAME_RATE = 30
        const val VIDEO_I_FRAME_INTERVAL = 1
        
        // 闊抽缂栫爜鍙傛暟
        const val AUDIO_SAMPLE_RATE = 44100
        const val AUDIO_CHANNEL_COUNT = 2
        const val AUDIO_BITRATE = 128_000
    }
    
    // 鐘舵€?    private
val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    // 鐜舰缂撳啿鍖?    private
val frameBuffer = ConcurrentLinkedQueue<FrameData>()
    private val audioBuffer = ConcurrentLinkedQueue<AudioData>()
    
    // 缂栫爜鍣?    private
var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    // 褰曞埗浣滅敤鍩?    private
var recordingScope: CoroutineScope? = null
    private var recordingJob: Job? = null
    
    // 鎹曡幏鏃堕棿鎴?    private
var captureTimestamp: Long = 0
    
    /**
     * 寮€濮嬬幆褰㈢紦鍐插綍鍒?     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startBuffering(): Surface? {
        if (_recordingState.value is RecordingState.Buffering) {
            return inputSurface
        }
        
        try {
            // 鍒涘缓瑙嗛缂栫爜鍣?
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
            
            // 鍒涘缓闊抽缂栫爜鍣?
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
            
            // 鍚姩缂撳啿绾跨▼
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
     * 缂撳啿缂栫爜甯?     */
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
                        
                        // 娣诲姞鍒扮幆褰㈢紦鍐插尯
                        frameBuffer.add(frameData)
                        
                        // 娓呯悊杩囨湡甯?
val cutoffTime = System.currentTimeMillis() - BUFFER_DURATION_MS
                        while (frameBuffer.peek()?.timestamp?.let { it < cutoffTime } == true) {
                            frameBuffer.poll()
                        }
                    }
                    
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 缁х画寰幆
            }
        }
    }
    
    /**
     * 瑙﹀彂瀹炲喌鐓х墖鎹曡幏
     * 
     * 淇濆瓨褰撳墠缂撳啿鍖?+ 缁х画褰曞埗 1.5 绉?     * 浣跨敤 Google Motion Photo 鏍煎紡锛堣棰戝祵鍏ュ埌 JPEG 鏈熬锛?     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun captureLivePhoto(
        photo: Bitmap,
        outputDir: File
    ): Result<LivePhotoResult> = withContext(ioDispatcher) {
        try {
            _recordingState.value = RecordingState.Capturing
            captureTimestamp = System.currentTimeMillis()
            
            // 绛夊緟鎷嶇収鍚庣殑 1.5 绉?            delay(BUFFER_AFTER_CAPTURE_MS)
            
            // 鑾峰彇缂撳啿鍖虹殑甯?
val frames = frameBuffer.toList()
            
            // 绛涢€夋椂闂寸獥鍙ｅ唴鐨勫抚
            val startTimeMs = captureTimestamp - BUFFER_BEFORE_CAPTURE_MS
            val endTimeMs = captureTimestamp + BUFFER_AFTER_CAPTURE_MS
            
            val selectedFrames = frames.filter { frame ->
                frame.timestamp in startTimeMs..endTimeMs
            }
            
            if (selectedFrames.isEmpty()) {
                return@withContext Result.failure(Exception("No frames in buffer"))
            }
            
            // 鐢熸垚鏂囦欢鍚嶏紙浣跨敤 .jpg 鎵╁睍鍚嶏紝鍥犱负 Motion Photo 鍩轰簬 JPEG锛?
val timestamp = System.currentTimeMillis()
            val tempVideoFile = File(outputDir, "temp_${timestamp}.mp4")
            val tempPhotoFile = File(outputDir, "temp_${timestamp}.jpg")
            val motionPhotoFile = File(outputDir, "MVIMG_${timestamp}.jpg")  // Motion Photo 鏍煎紡
            
            // 鍐欏叆涓存椂瑙嗛
            writeVideoFile(tempVideoFile, selectedFrames)
            
            // 淇濆瓨涓存椂鐓х墖
            savePhoto(photo, tempPhotoFile)
            
            // 鍒涘缓 Motion Photo锛堝皢瑙嗛宓屽叆鍒?JPEG锛?
val motionPhotoCreated = createMotionPhoto(tempPhotoFile, tempVideoFile, motionPhotoFile)
            
            // 娓呯悊涓存椂鏂囦欢
            tempVideoFile.delete()
            tempPhotoFile.delete()
            
            if (!motionPhotoCreated) {
                return@withContext Result.failure(Exception("Failed to create Motion Photo"))
            }
            
            val result = LivePhotoResult(
                photoFile = motionPhotoFile,
                videoFile = motionPhotoFile,  // Motion Photo 涓棰戝祵鍏ュ埌鐓х墖
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
     * 鍐欏叆瑙嗛鏂囦欢
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeVideoFile(file: File, frames: List<FrameData>) {
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        try {
            // 娣诲姞瑙嗛杞ㄩ亾
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
     * 淇濆瓨鐓х墖 - 浣跨敤 Google Motion Photo 鏍煎紡
     * 灏嗚棰戝祵鍏ュ埌 JPEG 鏈熬锛孫PPO/涓夋槦/Google 鐩稿唽閮芥敮鎸?     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePhoto(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            // 淇濆瓨涓?JPEG锛圡otion Photo 闇€瑕?JPEG 鏍煎紡锛?            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
    }
    
    /**
     * 鍒涘缓 Google Motion Photo 鏍煎紡鐨勭収鐗?     * 灏嗙収鐗囧拰瑙嗛鍚堝苟鍒板崟涓枃浠朵腑
     */
        @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMotionPhoto(photoFile: File, videoFile: File, outputFile: File): Boolean {
        try {
            val photoBytes = photoFile.readBytes()
            val videoBytes = videoFile.readBytes()

            val videoLengthBytes = videoBytes.size
            val xmpMetadata = createMotionPhotoXmp(videoLengthBytes)
            val xmpPayload = ("http://ns.adobe.com/xap/1.0/\u0000" + xmpMetadata)
                .toByteArray(Charsets.UTF_8)
            val app1Length = xmpPayload.size + 2
            val app1Header = byteArrayOf(
                0xFF.toByte(),
                0xE1.toByte(),
                ((app1Length shr 8) and 0xFF).toByte(),
                (app1Length and 0xFF).toByte()
            )

            outputFile.outputStream().use { output ->
                val jpegEndIndex = findJpegEndMarker(photoBytes)
                if (jpegEndIndex > 0) {
                    output.write(photoBytes, 0, jpegEndIndex)
                } else {
                    output.write(photoBytes)
                }

                if (jpegEndIndex > 0) {
                    output.write(app1Header)
                    output.write(xmpPayload)
                    output.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
                }

                output.write(videoBytes)
            }

            return true
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to create Motion Photo")
            return false
        }
    }/**
     * 鏌ユ壘 JPEG 缁撴潫鏍囪 (FFD9) 鐨勪綅缃?     */
    private fun findJpegEndMarker(data: ByteArray): Int {
        for (i in data.size - 2 downTo 0) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                return i
            }
        }
        return -1
    }
    
    /**
     * 鍒涘缓 Motion Photo XMP 鍏冩暟鎹?     */
    private fun createMotionPhotoXmp(videoLengthBytes: Int): String {
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
                        GCamera:MicroVideoOffset="\$videoLengthBytes"/>
                </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }
    
    /**
     * 鍋滄缂撳啿
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
     * 閲婃斁璧勬簮
     */
    fun release() {
        stopBuffering()
    }
}

/**
 * 褰曞埗鐘舵€? */
sealed class RecordingState {
    object Idle : RecordingState()
    object Buffering : RecordingState()
    object Capturing : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * 甯ф暟鎹? */
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
 * 闊抽鏁版嵁
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
 * 瀹炲喌鐓х墖缁撴灉
 */
data class LivePhotoResult(
    val photoFile: File,
    val videoFile: File,
    val durationMs: Long
)


