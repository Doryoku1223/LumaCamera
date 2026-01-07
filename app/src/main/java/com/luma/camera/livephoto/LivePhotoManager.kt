package com.luma.camera.livephoto

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.luma.camera.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motion photo (live photo) manager.
 *
 * - Keeps a rolling buffer of encoded frames.
 * - Captures 1s before + 1s after as an MP4.
 * - Creates a single JPEG container with Motion Photo XMP and the video appended.
 */
@Singleton
class LivePhotoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "LivePhotoManager"

        const val BUFFER_DURATION_MS = 2000L
        const val BUFFER_BEFORE_CAPTURE_MS = 1000L
        const val BUFFER_AFTER_CAPTURE_MS = 1000L

        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val VIDEO_BITRATE = 8_000_000
        const val VIDEO_FRAME_RATE = 30
        const val VIDEO_I_FRAME_INTERVAL = 1

        const val AUDIO_SAMPLE_RATE = 44100
        const val AUDIO_CHANNEL_COUNT = 2
        const val AUDIO_BITRATE = 128_000
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val frameBuffer = ConcurrentLinkedQueue<FrameData>()
    private val audioBuffer = ConcurrentLinkedQueue<AudioData>()

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoOutputFormat: MediaFormat? = null

    private var recordingScope: CoroutineScope? = null
    private var recordingJob: Job? = null

    private var captureTimestamp: Long = 0

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startBuffering(): Surface? {
        if (_recordingState.value is RecordingState.Buffering) {
            return inputSurface
        }

        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val supportsHevc = codecList.codecInfos.any { it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC) }
            val videoMime = if (supportsHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val videoFormat = MediaFormat.createVideoFormat(videoMime, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            }

            videoOutputFormat = null
            videoEncoder = MediaCodec.createEncoderByType(videoMime).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

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

    private suspend fun bufferEncodedFrames() = withContext(ioDispatcher) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isActive) {
            try {
                val encoder = videoEncoder ?: break
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoOutputFormat = encoder.outputFormat
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.get(data)

                                val frameData = FrameData(
                                    data = data,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags,
                                    timestamp = System.currentTimeMillis()
                                )

                                frameBuffer.add(frameData)

                                val cutoffTime = System.currentTimeMillis() - BUFFER_DURATION_MS
                                while (frameBuffer.peek()?.timestamp?.let { it < cutoffTime } == true) {
                                    frameBuffer.poll()
                                }
                            }
                        }

                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun captureLivePhoto(
        photo: Bitmap,
        outputDir: File,
        orientation: Int
    ): Result<LivePhotoResult> = withContext(ioDispatcher) {
        try {
            _recordingState.value = RecordingState.Capturing
            captureTimestamp = System.currentTimeMillis()

            delay(BUFFER_AFTER_CAPTURE_MS)

            val frames = frameBuffer.toList()
            val startTimeMs = captureTimestamp - BUFFER_BEFORE_CAPTURE_MS
            val endTimeMs = captureTimestamp + BUFFER_AFTER_CAPTURE_MS

            val selectedFrames = frames.filter { frame ->
                frame.timestamp in startTimeMs..endTimeMs
            }

            if (selectedFrames.isEmpty()) {
                return@withContext Result.failure(Exception("No frames in buffer"))
            }

            val timestamp = System.currentTimeMillis()
            val tempVideoFile = File(outputDir, "temp_${timestamp}.mp4")
            val tempPhotoFile = File(outputDir, "temp_${timestamp}.jpg")
            val motionPhotoFile = File(outputDir, "IMG_${timestamp}MP.jpg")

            writeVideoFile(tempVideoFile, selectedFrames, orientation)
            savePhoto(photo, tempPhotoFile)

            val presentationTimestampUs = ((captureTimestamp - selectedFrames.first().timestamp).coerceAtLeast(0) * 1000L)
            val motionPhotoCreated = createMotionPhoto(tempPhotoFile, tempVideoFile, motionPhotoFile, presentationTimestampUs)

            if (!motionPhotoCreated) {
                tempVideoFile.delete()
                tempPhotoFile.delete()
                return@withContext Result.failure(Exception("Failed to create Motion Photo"))
            }

            try {
                val exif = ExifInterface(motionPhotoFile)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                exif.saveAttributes()
            } catch (_: Exception) {
            }

            tempPhotoFile.delete()
            val motionVideoFile = File(outputDir, "VID_${timestamp}MP.mp4")
            val finalVideoFile = if (tempVideoFile.renameTo(motionVideoFile)) motionVideoFile else tempVideoFile

            val result = LivePhotoResult(
                photoFile = motionPhotoFile,
                videoFile = finalVideoFile,
                durationMs = selectedFrames.last().timestamp - selectedFrames.first().timestamp
            )

            _recordingState.value = RecordingState.Buffering
            Result.success(result)
        } catch (e: Exception) {
            _recordingState.value = RecordingState.Error(e.message ?: "Capture failed")
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeVideoFile(file: File, frames: List<FrameData>, orientation: Int) {
        val format = videoOutputFormat ?: throw IllegalStateException("Video format not ready")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val rotationDegrees = exifOrientationToDegrees(orientation)
        if (rotationDegrees != 0) {
            muxer.setOrientationHint(rotationDegrees)
        }

        try {
            val videoTrack = muxer.addTrack(format)
            muxer.start()

            val bufferInfo = MediaCodec.BufferInfo()
            for (frame in frames) {
                bufferInfo.presentationTimeUs = frame.presentationTimeUs
                bufferInfo.flags = frame.flags
                bufferInfo.offset = 0
                bufferInfo.size = frame.data.size

                val buffer = ByteBuffer.wrap(frame.data)
                muxer.writeSampleData(videoTrack, buffer, bufferInfo)
            }
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePhoto(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMotionPhoto(
        photoFile: File,
        videoFile: File,
        outputFile: File,
        presentationTimestampUs: Long
    ): Boolean {
        try {
            val photoBytes = photoFile.readBytes()
            val videoBytes = videoFile.readBytes()

            val videoLengthBytes = videoBytes.size
            val xmpMetadata = createMotionPhotoXmp(videoLengthBytes, presentationTimestampUs)
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
                val jpegContentEnd = if (jpegEndIndex > 0) jpegEndIndex else photoBytes.size

                if (photoBytes.size >= 2 && photoBytes[0] == 0xFF.toByte() && photoBytes[1] == 0xD8.toByte()) {
                    output.write(photoBytes, 0, 2)
                    output.write(app1Header)
                    output.write(xmpPayload)
                    if (jpegContentEnd > 2) {
                        output.write(photoBytes, 2, jpegContentEnd - 2)
                    }
                    output.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
                } else {
                    output.write(photoBytes)
                }

                output.write(videoBytes)
            }

            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to create Motion Photo")
            return false
        }
    }

    private fun findJpegEndMarker(data: ByteArray): Int {
        for (i in data.size - 2 downTo 0) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun createMotionPhotoXmp(videoLengthBytes: Int, presentationTimestampUs: Long): String {
        return """
            <x:xmpmeta xmlns:x=\"adobe:ns:meta/\">
                <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">
                    <rdf:Description
                        xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\"
                        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"
                        xmlns:OpCamera=\"http://ns.oppo.com/photo/1.0/camera/\"
                        Camera:MotionPhoto=\"1\"
                        Camera:MotionPhotoVersion=\"1\"
                        Camera:MotionPhotoPresentationTimestampUs=\"${presentationTimestampUs}\"
                        GCamera:MotionPhoto=\"1\"
                        GCamera:MotionPhotoVersion=\"1\"
                        GCamera:MotionPhotoPresentationTimestampUs=\"${presentationTimestampUs}\"
                        GCamera:MicroVideo=\"1\"
                        GCamera:MicroVideoVersion=\"1\"
                        GCamera:MicroVideoOffset=\"${videoLengthBytes}\"
                        OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"${presentationTimestampUs}\"
                        OpCamera:MotionPhotoOwner=\"LumaCamera\"
                        OpCamera:OLivePhotoVersion=\"1\"
                        OpCamera:VideoLength=\"${videoLengthBytes}\"/>
                    <rdf:Description
                        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"
                        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">
                        <Container:Directory>
                            <rdf:Seq>
                                <rdf:li rdf:parseType=\"Resource\"
                                    Item:Mime=\"image/jpeg\"
                                    Item:Semantic=\"Primary\"
                                    Item:Length=\"0\"
                                    Item:Padding=\"0\"/>
                                <rdf:li rdf:parseType=\"Resource\"
                                    Item:Mime=\"video/mp4\"
                                    Item:Semantic=\"MotionPhoto\"
                                    Item:Length=\"${videoLengthBytes}\"/>
                            </rdf:Seq>
                        </Container:Directory>
                    </rdf:Description>
                </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    fun stopBuffering() {
        recordingJob?.cancel()
        recordingScope?.cancel()

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (_: Exception) {
        }

        videoEncoder = null
        audioEncoder = null
        inputSurface = null

        frameBuffer.clear()
        audioBuffer.clear()

        _recordingState.value = RecordingState.Idle
    }

    fun release() {
        stopBuffering()
    }

    private fun exifOrientationToDegrees(orientation: Int): Int {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}

sealed class RecordingState {
    object Idle : RecordingState()
    object Buffering : RecordingState()
    object Capturing : RecordingState()
    data class Error(val message: String) : RecordingState()
}

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

data class LivePhotoResult(
    val photoFile: File,
    val videoFile: File,
    val durationMs: Long
)
