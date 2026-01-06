package com.luma.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.luma.camera.di.IoDispatcher
import com.luma.camera.domain.model.OutputFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 拍照管理器
 * 
 * 管理照片捕获，支持：
 * - RAW + JPEG 同时捕获
 * - 连拍模式 (≥15张/秒)
 * - 闪光灯同步
 */
@Singleton
class CaptureManager @Inject constructor(
    private val cameraManager: CameraManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "CaptureManager"
        
        // 连拍配置
        const val BURST_MAX_COUNT = 100
        const val BURST_FPS_TARGET = 15
    }
    
    // 捕获状态
    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()
    
    // 捕获结果
    private val _captureResult = MutableSharedFlow<CaptureResult>(replay = 0)
    val captureResult: SharedFlow<CaptureResult> = _captureResult.asSharedFlow()
    
    // 连拍计数
    private val _burstCount = MutableStateFlow(0)
    val burstCount: StateFlow<Int> = _burstCount.asStateFlow()
    
    // 处理线程
    private val captureThread = HandlerThread("CaptureThread").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    
    // 捕获范围
    private var captureScope: CoroutineScope? = null
    
    // 连拍 Job
    private var burstJob: Job? = null
    
    /**
     * 拍摄单张照片
     */
    suspend fun capturePhoto(
        session: CameraCaptureSession,
        device: CameraDevice,
        cameraId: String,
        outputFormat: OutputFormat,
        rawSurface: Surface?,
        jpegSurface: Surface?
    ): Result<CaptureResult> = withContext(ioDispatcher) {
        try {
            _captureState.value = CaptureState.Capturing
            
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // 创建捕获请求
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                // 添加输出目标
                when (outputFormat) {
                    OutputFormat.RAW_DNG -> {
                        rawSurface?.let { addTarget(it) }
                    }
                    OutputFormat.JPEG, OutputFormat.HEIF -> {
                        jpegSurface?.let { addTarget(it) }
                    }
                    OutputFormat.RAW_JPEG -> {
                        rawSurface?.let { addTarget(it) }
                        jpegSurface?.let { addTarget(it) }
                    }
                }
                
                // 图像质量最大化
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                
                // 边缘增强
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                
                // 降噪
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                
                // 镜头阴影校正
                set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
                
                // 热像素校正
                set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
            
            // 执行捕获
            val result = executeCaptureAsync(session, captureBuilder.build())
            
            _captureState.value = CaptureState.Idle
            _captureResult.emit(result)
            
            Result.success(result)
        } catch (e: Exception) {
            _captureState.value = CaptureState.Error(e.message ?: "Capture failed")
            Result.failure(e)
        }
    }
    
    /**
     * 开始连拍
     */
    fun startBurstCapture(
        session: CameraCaptureSession,
        device: CameraDevice,
        cameraId: String,
        jpegSurface: Surface,
        onImage: (Image) -> Unit
    ) {
        if (burstJob?.isActive == true) return
        
        _captureState.value = CaptureState.BurstCapturing
        _burstCount.value = 0
        
        captureScope = CoroutineScope(ioDispatcher + SupervisorJob())
        
        burstJob = captureScope?.launch {
            try {
                // 创建连拍请求
                val burstBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpegSurface)
                    
                    // 连拍优化
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    
                    // 快速处理
                    set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    
                    // 保持对焦锁定
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                
                val burstRequest = burstBuilder.build()
                
                // 创建连拍请求列表
                val burstRequests = List(BURST_MAX_COUNT) { burstRequest }
                
                // 执行连拍
                session.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        _burstCount.value++
                    }
                    
                    override fun onCaptureSequenceCompleted(
                        session: CameraCaptureSession,
                        sequenceId: Int,
                        frameNumber: Long
                    ) {
                        _captureState.value = CaptureState.Idle
                    }
                }, captureHandler)
                
            } catch (e: Exception) {
                _captureState.value = CaptureState.Error(e.message ?: "Burst capture failed")
            }
        }
    }
    
    /**
     * 停止连拍
     */
    fun stopBurstCapture(session: CameraCaptureSession) {
        burstJob?.cancel()
        burstJob = null
        
        try {
            session.stopRepeating()
        } catch (e: Exception) {
            // Ignore
        }
        
        _captureState.value = CaptureState.Idle
    }
    
    /**
     * 执行异步捕获
     */
    private suspend fun executeCaptureAsync(
        session: CameraCaptureSession,
        request: CaptureRequest
    ): CaptureResult = suspendCoroutine { continuation ->
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val captureResult = CaptureResult(
                    timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                    iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    exposureTime = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                    focusDistance = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                    aperture = result.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: 0f
                )
                continuation.resume(captureResult)
            }
            
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                continuation.resume(
                    CaptureResult(
                        timestamp = 0,
                        iso = 0,
                        exposureTime = 0,
                        focusDistance = 0f,
                        aperture = 0f,
                        error = "Capture failed: ${failure.reason}"
                    )
                )
            }
        }, captureHandler)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        burstJob?.cancel()
        captureScope?.cancel()
        captureThread.quitSafely()
    }
}

/**
 * 捕获状态
 */
sealed class CaptureState {
    object Idle : CaptureState()
    object Capturing : CaptureState()
    object BurstCapturing : CaptureState()
    data class Error(val message: String) : CaptureState()
}

/**
 * 捕获结果
 */
data class CaptureResult(
    val timestamp: Long,
    val iso: Int,
    val exposureTime: Long,
    val focusDistance: Float,
    val aperture: Float,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
    
    // 快门速度 (分数形式)
    val shutterSpeedFraction: String
        get() {
            if (exposureTime <= 0) return "N/A"
            val denominator = (1_000_000_000.0 / exposureTime).toInt()
            return if (denominator >= 1) "1/$denominator" else "${exposureTime / 1_000_000_000.0}s"
        }
}
