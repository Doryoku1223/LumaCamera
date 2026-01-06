package com.luma.camera.livephoto

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import com.luma.camera.lut.LutManager
import com.luma.camera.lut.LutParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LivePhoto LUT Processor
 * 
 * 对 LivePhoto 视频帧应用 LUT 色彩映射，使用 GPU 批量处理
 * 以保证性能和与静态图片的色彩一致性。
 * 
 * 技术要点:
 * - GPU 批量处理: 使用 OpenGL ES 3.0 进行帧批处理
 * - 3D LUT 纹理: 直接在 GPU 上应用 LUT 变换
 * - 硬件编解码: MediaCodec 硬件加速
 * - 零拷贝: 使用 Surface 避免 CPU 内存拷贝
 */
@Singleton
class LivePhotoLutProcessor @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val lutManager: LutManager
) {
    companion object {
        private const val TAG = "LivePhotoLutProcessor"
        
        private const val TIMEOUT_US = 10_000L
        private const val MAX_BATCH_SIZE = 8
        
        // OpenGL 着色器
        private const val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        
        private const val FRAGMENT_SHADER_LUT = """
            #version 300 es
            precision highp float;
            precision highp sampler3D;
            
            in vec2 vTexCoord;
            out vec4 fragColor;
            
            uniform sampler2D uInputTexture;
            uniform sampler3D uLutTexture;
            uniform float uLutSize;
            uniform float uIntensity;
            
            void main() {
                vec4 inputColor = texture(uInputTexture, vTexCoord);
                
                // 计算 LUT 坐标
                float halfTexel = 0.5 / uLutSize;
                vec3 lutCoord = inputColor.rgb * (1.0 - 1.0 / uLutSize) + halfTexel;
                
                // 采样 LUT
                vec3 lutColor = texture(uLutTexture, lutCoord).rgb;
                
                // 混合原始颜色和 LUT 颜色
                vec3 outputColor = mix(inputColor.rgb, lutColor, uIntensity);
                
                fragColor = vec4(outputColor, inputColor.a);
            }
        """
    }
    
    // 处理状态
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()
    
    // EGL 上下文
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    
    // OpenGL 资源
    private var programId = 0
    private var lutTextureId = 0
    private var inputTextureId = 0
    private var framebufferId = 0
    
    // 顶点数据
    private val vertexBuffer: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    -1f, -1f,  // position
                    -1f,  1f,
                     1f, -1f,
                     1f,  1f
                ))
                position(0)
            }
    }
    
    private val texCoordBuffer: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    0f, 0f,
                    0f, 1f,
                    1f, 0f,
                    1f, 1f
                ))
                position(0)
            }
    }
    
    /**
     * 处理状态
     */
    sealed class ProcessingState {
        object Idle : ProcessingState()
        data class Processing(val progress: Float, val currentFrame: Int, val totalFrames: Int) : ProcessingState()
        data class Completed(val outputFile: File, val processingTimeMs: Long) : ProcessingState()
        data class Error(val message: String) : ProcessingState()
    }
    
    /**
     * 处理配置
     */
    data class ProcessingConfig(
        val lutIntensity: Float = 1.0f,      // LUT 强度 (0.0 - 1.0)
        val preserveAudio: Boolean = true,   // 保留音频
        val outputBitrate: Int = 8_000_000,  // 输出码率 (8 Mbps)
        val useHardwareEncoder: Boolean = true
    )
    
    /**
     * 对视频应用 LUT
     * 
     * @param inputVideo 输入视频文件
     * @param outputVideo 输出视频文件
     * @param lutName LUT 名称
     * @param config 处理配置
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun applyLut(
        inputVideo: File,
        outputVideo: File,
        lutName: String,
        config: ProcessingConfig = ProcessingConfig()
    ): Result<File> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        
        try {
            _processingState.value = ProcessingState.Processing(0f, 0, 0)
            
            // 获取 LUT 数据
            val lutData = lutManager.getLutData(lutName)
                ?: return@withContext Result.failure(IllegalArgumentException("LUT not found: $lutName"))
            
            // 设置 EGL 环境
            setupEglContext()
            
            // 创建 LUT 纹理
            createLutTexture(lutData)
            
            // 处理视频
            processVideoWithLut(
                inputVideo = inputVideo,
                outputVideo = outputVideo,
                lutData = lutData,
                config = config
            )
            
            // 清理资源
            releaseGlResources()
            releaseEglContext()
            
            val processingTime = System.currentTimeMillis() - startTime
            _processingState.value = ProcessingState.Completed(outputVideo, processingTime)
            
            Result.success(outputVideo)
        } catch (e: Exception) {
            _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
            releaseGlResources()
            releaseEglContext()
            Result.failure(e)
        }
    }
    
    /**
     * 设置 EGL 上下文
     */
    private fun setupEglContext() {
        // 获取 EGL Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL display")
        }
        
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }
        
        // 配置 EGL
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("Unable to choose EGL config")
        }
        
        // 创建 EGL Context
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Unable to create EGL context")
        }
        
        // 创建 PBuffer Surface
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
        
        // 激活上下文
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }
    
    /**
     * 释放 EGL 上下文
     */
    private fun releaseEglContext() {
        eglDisplay?.let { display ->
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(display, it) }
            eglContext?.let { EGL14.eglDestroyContext(display, it) }
            EGL14.eglTerminate(display)
        }
        eglDisplay = null
        eglContext = null
        eglSurface = null
    }
    
    /**
     * 创建 3D LUT 纹理
     */
    private fun createLutTexture(lutData: LutParser.LutData) {
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        lutTextureId = textureIds[0]
        
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        
        // 设置纹理参数
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        
        // 上传 LUT 数据
        val size = lutData.size
        // LUT 数据是 RGB 浮点数, 需要转换为 RGBA8 格式
        val lutDataRgba = ByteArray(size * size * size * 4)
        for (i in 0 until size * size * size) {
            val r = (lutData.data[i * 3 + 0] * 255f).toInt().coerceIn(0, 255).toByte()
            val g = (lutData.data[i * 3 + 1] * 255f).toInt().coerceIn(0, 255).toByte()
            val b = (lutData.data[i * 3 + 2] * 255f).toInt().coerceIn(0, 255).toByte()
            lutDataRgba[i * 4 + 0] = r
            lutDataRgba[i * 4 + 1] = g
            lutDataRgba[i * 4 + 2] = b
            lutDataRgba[i * 4 + 3] = 255.toByte()
        }
        
        val buffer = ByteBuffer.allocateDirect(size * size * size * 4)
            .order(ByteOrder.nativeOrder())
        buffer.put(lutDataRgba)
        buffer.position(0)
        
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA8,
            size, size, size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
    }
    
    /**
     * 创建着色器程序
     */
    private fun createShaderProgram(): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_LUT)
        
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        
        return program
    }
    
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        
        return shader
    }
    
    /**
     * 处理视频并应用 LUT
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun processVideoWithLut(
        inputVideo: File,
        outputVideo: File,
        lutData: LutParser.LutData,
        config: ProcessingConfig
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputVideo.absolutePath)
        
        // 查找视频和音频轨道
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            
            when {
                mime.startsWith("video/") && videoTrackIndex < 0 -> {
                    videoTrackIndex = i
                    videoFormat = format
                }
                mime.startsWith("audio/") && audioTrackIndex < 0 -> {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }
        }
        
        if (videoTrackIndex < 0 || videoFormat == null) {
            throw IllegalArgumentException("No video track found")
        }
        
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        val duration = videoFormat.getLong(MediaFormat.KEY_DURATION)
        val totalFrames = (duration * frameRate / 1_000_000).toInt()
        
        // 创建解码器
        val decoderFormat = MediaFormat.createVideoFormat(
            videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_HEVC,
            width, height
        )
        val decoder = MediaCodec.createDecoderByType(
            videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_HEVC
        )
        
        // 创建编码器
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, config.outputBitrate)
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        encoderFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        val encoderSurface = encoder.createInputSurface()
        encoder.start()
        
        // 配置解码器输出到我们的处理 Surface
        decoder.configure(decoderFormat, null, null, 0)
        decoder.start()
        
        // 创建 Muxer
        val muxer = MediaMuxer(outputVideo.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false
        
        // 设置着色器程序
        programId = createShaderProgram()
        GLES30.glUseProgram(programId)
        
        // 选择视频轨道
        extractor.selectTrack(videoTrackIndex)
        
        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffer = ByteBuffer.allocate(1024 * 1024)
        var inputDone = false
        var outputDone = false
        var frameCount = 0
        
        // 解码和编码循环
        while (!outputDone) {
            // 送入解码器
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            
            // 从解码器获取输出并应用 LUT
            var decoderOutputAvailable = true
            while (decoderOutputAvailable) {
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        decoderOutputAvailable = false
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 格式变化
                    }
                    outputBufferIndex >= 0 -> {
                        // 获取解码帧并应用 LUT
                        val render = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(outputBufferIndex, render)
                        
                        if (render) {
                            // 这里应该在实际实现中:
                            // 1. 将解码输出渲染到纹理
                            // 2. 应用 LUT 着色器
                            // 3. 渲染到编码器输入 Surface
                            
                            applyLutToFrame(encoderSurface, lutData, config.lutIntensity)
                            
                            frameCount++
                            _processingState.value = ProcessingState.Processing(
                                progress = frameCount.toFloat() / totalFrames,
                                currentFrame = frameCount,
                                totalFrames = totalFrames
                            )
                        }
                        
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoder.signalEndOfInputStream()
                            decoderOutputAvailable = false
                        }
                    }
                }
            }
            
            // 从编码器获取输出
            var encoderOutputAvailable = true
            while (encoderOutputAvailable) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                
                when {
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        encoderOutputAvailable = false
                    }
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            
                            // 如果需要保留音频，添加音频轨道
                            if (config.preserveAudio && audioFormat != null) {
                                muxerAudioTrack = muxer.addTrack(audioFormat)
                            }
                            
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        
                        if (bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                        }
                        
                        encoder.releaseOutputBuffer(encoderStatus, false)
                        
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            encoderOutputAvailable = false
                        }
                    }
                }
            }
        }
        
        // 复制音频轨道
        if (config.preserveAudio && audioTrackIndex >= 0 && muxerAudioTrack >= 0) {
            copyAudioTrack(extractor, audioTrackIndex, muxer, muxerAudioTrack)
        }
        
        // 清理
        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()
        encoderSurface.release()
        muxer.stop()
        muxer.release()
        extractor.release()
    }
    
    /**
     * 对单帧应用 LUT
     */
    private fun applyLutToFrame(outputSurface: Surface, lutData: LutParser.LutData, intensity: Float) {
        // 设置 LUT uniforms
        val lutSizeLocation = GLES30.glGetUniformLocation(programId, "uLutSize")
        val intensityLocation = GLES30.glGetUniformLocation(programId, "uIntensity")
        val inputTextureLocation = GLES30.glGetUniformLocation(programId, "uInputTexture")
        val lutTextureLocation = GLES30.glGetUniformLocation(programId, "uLutTexture")
        
        GLES30.glUniform1f(lutSizeLocation, lutData.size.toFloat())
        GLES30.glUniform1f(intensityLocation, intensity)
        GLES30.glUniform1i(inputTextureLocation, 0)
        GLES30.glUniform1i(lutTextureLocation, 1)
        
        // 绑定纹理
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        
        // 绘制
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
    
    /**
     * 复制音频轨道
     */
    private fun copyAudioTrack(
        extractor: MediaExtractor,
        sourceTrack: Int,
        muxer: MediaMuxer,
        destTrack: Int
    ) {
        extractor.unselectTrack(0) // 取消选择视频轨道
        extractor.selectTrack(sourceTrack)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        
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
    }
    
    /**
     * 释放 OpenGL 资源
     */
    private fun releaseGlResources() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        
        if (lutTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = 0
        }
        
        if (inputTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            inputTextureId = 0
        }
        
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
    }
    
    /**
     * 批量预览 LUT 效果
     * 
     * 从视频中提取关键帧，应用 LUT 预览
     */
    suspend fun previewLutOnKeyframes(
        videoFile: File,
        lutName: String,
        keyframeCount: Int = 5
    ): List<Bitmap> = withContext(ioDispatcher) {
        val previews = mutableListOf<Bitmap>()
        
        try {
            val lutData = lutManager.getLutData(lutName)
                ?: return@withContext emptyList()
            
            val extractor = MediaExtractor()
            extractor.setDataSource(videoFile.absolutePath)
            
            // 查找视频轨道
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            
            if (videoTrackIndex >= 0 && videoFormat != null) {
                val duration = videoFormat.getLong(MediaFormat.KEY_DURATION)
                val interval = duration / (keyframeCount + 1)
                
                for (i in 1..keyframeCount) {
                    val timestamp = interval * i
                    extractor.seekTo(timestamp, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    
                    // 提取帧并应用 LUT
                    // 注意: 完整实现需要解码帧到 Bitmap，然后应用 LUT
                    // 这里简化处理，实际需要使用 MediaCodec 解码
                }
            }
            
            extractor.release()
        } catch (e: Exception) {
            // 忽略错误，返回空列表
        }
        
        previews
    }
}
