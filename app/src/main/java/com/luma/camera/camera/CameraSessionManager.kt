package com.luma.camera.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import com.luma.camera.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation

/**
 * Camera2 Session Manager
 * 
 * 管理 Camera2 会话，支持 120fps 预览
 */
@Singleton
class CameraSessionManager @Inject constructor(
    private val cameraManager: CameraManager,
    private val multiCameraManager: MultiCameraManager,
    private val sensorInfoManager: SensorInfoManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "CameraSessionManager"
        
        // 120fps 预览配置
        const val TARGET_PREVIEW_FPS = 120
        private const val MIN_PREVIEW_FPS = 60
        
        // 预览尺寸 (16:9)
        val PREVIEW_SIZE_4K = Size(3840, 2160)
        val PREVIEW_SIZE_1080P = Size(1920, 1080)
        val PREVIEW_SIZE_720P = Size(1280, 720)
    }
    
    // 会话状态
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Closed)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    
    // 当前相机设备
    private var currentCamera: CameraDevice? = null
    private var currentSession: CameraCaptureSession? = null
    private var currentCameraId: String? = null
    
    // 预览配置
    private var previewSurface: Surface? = null
    private var previewSize: Size = PREVIEW_SIZE_1080P
    
    // RAW 捕获
    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    
    // 线程和 Handler
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    // 预览请求
    private var previewRequest: CaptureRequest? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    
    // 手动参数
    private var manualParameters = ManualParameters()
    
    /**
     * 打开相机并创建会话
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun openCamera(
        focalLength: FocalLength,
        previewSurface: Surface,
        previewSize: Size = PREVIEW_SIZE_1080P
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            _sessionState.value = SessionState.Opening
            
            this@CameraSessionManager.previewSurface = previewSurface
            this@CameraSessionManager.previewSize = previewSize
            
            // 获取对应焦段的相机ID
            val cameraId = multiCameraManager.getCameraIdForFocalLength(focalLength)
                ?: throw CameraException("No camera found for focal length: $focalLength")
            
            currentCameraId = cameraId
            
            // 打开相机设备
            val device = openCameraDevice(cameraId)
            currentCamera = device
            
            // 创建图像读取器
            setupImageReaders(cameraId)
            
            // 创建高帧率会话
            createHighFpsSession(device, previewSurface)
            
            _sessionState.value = SessionState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            _sessionState.value = SessionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * 打开相机设备
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun openCameraDevice(cameraId: String): CameraDevice = 
        suspendCancellableCoroutine { continuation ->
            try {
                cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) {
                            continuation.resume(camera)
                        }
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        _sessionState.value = SessionState.Closed
                        if (continuation.isActive) {
                            continuation.resumeWithException(CameraException("Camera disconnected"))
                        }
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        val errorMsg = getCameraErrorMessage(error)
                        if (continuation.isActive) {
                            continuation.resumeWithException(CameraException(errorMsg))
                        }
                    }
                })
            } catch (e: SecurityException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    
    /**
     * 创建高帧率预览会话 (120fps)
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun createHighFpsSession(
        device: CameraDevice,
        previewSurface: Surface
    ) = suspendCancellableCoroutine { continuation ->
        val cameraId = currentCameraId ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("No camera ID"))
            }
            return@suspendCancellableCoroutine
        }
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        // 获取支持的帧率范围
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val targetFpsRange = findBestFpsRange(fpsRanges ?: arrayOf())
        
        // 配置输出
        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(OutputConfiguration(previewSurface))
        
        rawImageReader?.surface?.let { surface ->
            outputs.add(OutputConfiguration(surface))
        }
        
        jpegImageReader?.surface?.let { surface ->
            outputs.add(OutputConfiguration(surface))
        }
        
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    currentSession = session
                    
                    // 创建预览请求
                    try {
                        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            
                            // 设置目标帧率
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
                            
                            // 优化预览延迟
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            
                            // 开启 HDR+ (如果支持)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                // Android 14+ HDR 支持
                            }
                        }
                        
                        previewRequest = previewRequestBuilder?.build()
                        
                        // 开始预览
                        previewRequest?.let { request ->
                            session.setRepeatingRequest(request, captureCallback, cameraHandler)
                        }
                        
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            CameraException("Failed to configure camera session")
                        )
                    }
                }
            }
        )
        
        device.createCaptureSession(sessionConfig)
    }
    
    /**
     * 设置图像读取器
     */
    private fun setupImageReaders(cameraId: String) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        // RAW 捕获
        if (streamMap?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true) {
            val rawSizes = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawSize = rawSizes?.maxByOrNull { it.width * it.height }
            
            rawSize?.let {
                rawImageReader = ImageReader.newInstance(
                    it.width,
                    it.height,
                    ImageFormat.RAW_SENSOR,
                    2
                ).apply {
                    setOnImageAvailableListener(rawImageListener, cameraHandler)
                }
            }
        }
        
        // JPEG 捕获
        val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
        val jpegSize = jpegSizes?.maxByOrNull { it.width * it.height }
        
        jpegSize?.let {
            jpegImageReader = ImageReader.newInstance(
                it.width,
                it.height,
                ImageFormat.JPEG,
                2
            ).apply {
                setOnImageAvailableListener(jpegImageListener, cameraHandler)
            }
        }
    }
    
    /**
     * 查找最佳帧率范围
     */
    private fun findBestFpsRange(ranges: Array<Range<Int>>): Range<Int> {
        // 优先选择 120fps
        ranges.find { it.upper == TARGET_PREVIEW_FPS }?.let { return it }
        
        // 其次 60fps
        ranges.find { it.upper >= MIN_PREVIEW_FPS }?.let { return it }
        
        // 默认最高帧率
        return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
    }
    
    /**
     * 更新手动参数
     */
    suspend fun updateManualParameters(params: ManualParameters) {
        this.manualParameters = params
        
        previewRequestBuilder?.let { builder ->
            applyManualParameters(builder, params)
            
            previewRequest = builder.build()
            currentSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
        }
    }
    
    /**
     * 应用手动参数
     */
    private fun applyManualParameters(builder: CaptureRequest.Builder, params: ManualParameters) {
        // ISO
        params.iso?.let { iso ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }
        
        // 快门速度
        params.shutterSpeed?.let { speed ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            // 转换为纳秒
            val exposureNs = (1_000_000_000L / speed).toLong()
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        }
        
        // 手动对焦距离
        params.focusDistance?.let { distance ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        }
        
        // 白平衡
        when (params.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
            WhiteBalanceMode.MANUAL -> {
                // 手动色温需要关闭 AWB
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                
                // 根据色温计算 RGB 增益
                val gains = calculateColorGainsFromKelvin(params.whiteBalanceKelvin)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                Timber.d("Applied manual white balance: ${params.whiteBalanceKelvin}K -> gains=${gains}")
            }
            else -> {
                // 预设白平衡
                val awbMode = when (params.whiteBalanceMode) {
                    WhiteBalanceMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                    WhiteBalanceMode.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                    WhiteBalanceMode.TUNGSTEN -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                    WhiteBalanceMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                    WhiteBalanceMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
                    else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                }
                builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            }
        }
        
        // 曝光补偿 (Auto 模式下)
        if (params.iso == null && params.shutterSpeed == null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            
            // 应用曝光补偿（如果不为 0）
            if (params.exposureCompensation != 0f) {
                val cameraId = currentCameraId
                if (cameraId != null) {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    
                    if (range != null && step != null) {
                        // EV 值除以步长，得到补偿步数
                        val compensation = (params.exposureCompensation / step.toFloat()).toInt()
                            .coerceIn(range.lower, range.upper)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
                        Timber.d("Applied EV compensation: ${params.exposureCompensation} -> $compensation steps (step=$step, range=$range)")
                    }
                }
            } else {
                // 重置为 0
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            }
        }
    }
    
    /**
     * 触摸对焦
     */
    suspend fun triggerTouchFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val builder = previewRequestBuilder ?: return
        val cameraId = currentCameraId ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        // 计算对焦区域
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        
        val focusSize = 150
        val centerX = (x / viewWidth * sensorRect.width()).toInt()
        val centerY = (y / viewHeight * sensorRect.height()).toInt()
        
        val left = (centerX - focusSize / 2).coerceIn(0, sensorRect.width() - focusSize)
        val top = (centerY - focusSize / 2).coerceIn(0, sensorRect.height() - focusSize)
        val right = left + focusSize
        val bottom = top + focusSize
        
        val focusRegion = android.hardware.camera2.params.MeteringRectangle(
            left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX
        )
        
        // 设置对焦和测光区域
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRegion))
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        
        // 发送单次请求触发对焦
        val focusRequest = builder.build()
        currentSession?.capture(focusRequest, captureCallback, cameraHandler)
        
        // 重置 trigger
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        previewRequest = builder.build()
        currentSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
    }
    
    /**
     * 切换焦段
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun switchFocalLength(focalLength: FocalLength): Result<Unit> {
        val surface = previewSurface ?: return Result.failure(CameraException("No preview surface"))
        
        // 关闭当前相机
        closeCamera()
        
        // 打开新焦段相机
        return openCamera(focalLength, surface, previewSize)
    }
    
    /**
     * 设置闪光灯模式
     */
    fun setFlashMode(mode: FlashMode) {
        previewRequestBuilder?.let { builder ->
            when (mode) {
                FlashMode.OFF -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                FlashMode.AUTO -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                FlashMode.ON -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                }
                FlashMode.TORCH -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                }
            }
            
            Timber.d("Flash mode set to: $mode")
            previewRequest = builder.build()
            currentSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
        } ?: Timber.w("Cannot set flash mode: previewRequestBuilder is null")
    }
    
    /**
     * 拍照
     * @param flashMode 闪光灯模式
     * @return JPEG 图像数据
     */
    suspend fun capturePhoto(flashMode: FlashMode = FlashMode.OFF): ByteArray = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        
        val camera = currentCamera ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("Camera not opened"))
            }
            return@suspendCancellableCoroutine
        }
        val session = currentSession ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("No capture session"))
            }
            return@suspendCancellableCoroutine
        }
        val reader = jpegImageReader ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("No JPEG image reader"))
            }
            return@suspendCancellableCoroutine
        }

        // 设置图像可用回调
        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(bytes)
                    }
                } finally {
                    image.close()
                }
            }
        }, cameraHandler)

        // 创建拍照请求
        try {
            // 获取相机传感器方向
            val cameraId = currentCameraId ?: "0"
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            
            // 获取设备当前物理方向
            val deviceOrientation = sensorInfoManager.orientation.value
            val deviceRotation = when (deviceOrientation) {
                SensorInfoManager.DeviceOrientation.PORTRAIT -> 0
                SensorInfoManager.DeviceOrientation.LANDSCAPE_LEFT -> 90
                SensorInfoManager.DeviceOrientation.UPSIDE_DOWN -> 180
                SensorInfoManager.DeviceOrientation.LANDSCAPE_RIGHT -> 270
            }
            
            // 计算 JPEG 方向
            // 公式：(sensorOrientation + deviceRotation) % 360 对于后置相机
            // 对于前置相机需要镜像处理
            val jpegOrientation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation - deviceRotation + 360) % 360
            } else {
                (sensorOrientation + deviceRotation) % 360
            }
            
            Timber.d("Orientation calculation: sensor=$sensorOrientation, device=$deviceRotation, facing=$facing, jpeg=$jpegOrientation")
            
            // 检查是否需要 AE precapture (用于 AUTO 和 ON 闪光灯模式)
            val needsPrecapture = (flashMode == FlashMode.AUTO || flashMode == FlashMode.ON) &&
                    manualParameters.iso == null && manualParameters.shutterSpeed == null
            
            if (needsPrecapture) {
                // 触发 AE precapture 序列
                runAePrecaptureSequence(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            } else {
                // 直接拍照
                captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            }
        } catch (e: CameraAccessException) {
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 运行 AE 预捕获序列（用于闪光灯测光）
     */
    private fun runAePrecaptureSequence(
        session: CameraCaptureSession,
        camera: CameraDevice,
        reader: ImageReader,
        flashMode: FlashMode,
        jpegOrientation: Int,
        resumed: AtomicBoolean,
        continuation: CancellableContinuation<ByteArray>
    ) {
        try {
            previewRequestBuilder?.let { builder ->
                // 设置闪光灯模式
                when (flashMode) {
                    FlashMode.AUTO -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    }
                    FlashMode.ON -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    }
                    else -> {}
                }
                
                // 触发 AE precapture
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Timber.d("AE precapture started")
                        // 重置 precapture trigger
                        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                        
                        // 等待 AE 收敛后拍照
                        waitForAeConvergenceAndCapture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
                    }
                    
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Timber.w("AE precapture failed, proceeding with capture anyway")
                        captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
                    }
                }, cameraHandler)
            } ?: run {
                // 如果没有 previewRequestBuilder，直接拍照
                captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            }
        } catch (e: CameraAccessException) {
            Timber.e(e, "AE precapture failed")
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 等待 AE 收敛后拍照
     */
    private fun waitForAeConvergenceAndCapture(
        session: CameraCaptureSession,
        camera: CameraDevice,
        reader: ImageReader,
        flashMode: FlashMode,
        jpegOrientation: Int,
        resumed: AtomicBoolean,
        continuation: CancellableContinuation<ByteArray>
    ) {
        var waitCount = 0
        val maxWaitCount = 5 // 最多等待5帧
        
        previewRequestBuilder?.let { builder ->
            try {
                session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val aeState: Int? = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                        waitCount++
                        
                        Timber.d("Waiting for AE convergence: state=$aeState, count=$waitCount")
                        
                        // 检查 AE 是否收敛或已准备好闪光
                        val isAeReady = aeState == null ||
                                aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                                aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                waitCount >= maxWaitCount
                        
                        if (isAeReady) {
                            // 停止重复请求的回调监听，恢复正常预览
                            try {
                                session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                            } catch (e: CameraAccessException) {
                                Timber.e(e, "Failed to restore preview")
                            }
                            
                            // 执行拍照
                            captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
                        }
                    }
                }, cameraHandler)
            } catch (e: CameraAccessException) {
                Timber.e(e, "Failed to wait for AE convergence")
                // 即使失败也尝试拍照
                captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            }
        } ?: run {
            captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
        }
    }
    
    /**
     * 执行实际的静态图片捕获
     */
    private fun captureStillPicture(
        session: CameraCaptureSession,
        camera: CameraDevice,
        reader: ImageReader,
        flashMode: FlashMode,
        jpegOrientation: Int,
        resumed: AtomicBoolean,
        continuation: CancellableContinuation<ByteArray>
    ) {
        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                
                // 设置 JPEG 方向 - 修复照片旋转问题
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                
                // 闪光灯设置
                when (flashMode) {
                    FlashMode.OFF -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                    FlashMode.AUTO -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    }
                    FlashMode.ON -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    }
                    FlashMode.TORCH -> {
                        // TORCH 模式：保持手电筒常亮
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                }
                
                // 应用手动参数
                if (manualParameters.iso != null || manualParameters.shutterSpeed != null) {
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    
                    // ISO
                    manualParameters.iso?.let { iso ->
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                    
                    // 快门速度 (转换为纳秒)
                    manualParameters.shutterSpeed?.let { speed ->
                        val exposureTimeNs = (1_000_000_000L / speed).toLong()
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
                    }
                }
                
                // 设置 JPEG 质量
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                
                // 设置自动对焦
                if (manualParameters.focusDistance == null) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, manualParameters.focusDistance)
                }
            }
            
            Timber.d("Capturing still picture with flash mode: $flashMode, orientation: $jpegOrientation")

            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Timber.d("Photo capture completed")
                    }
                    
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resumeWithException(CameraException("Capture failed: ${failure.reason}"))
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * RAW 捕获结果
     */
    data class RawCaptureResult(
        val rawImage: android.media.Image,
        val characteristics: CameraCharacteristics,
        val captureResult: TotalCaptureResult,
        val jpegOrientation: Int
    )
    
    /**
     * 检查当前相机是否支持 RAW 捕获
     */
    fun isRawCaptureSupported(): Boolean {
        val cameraId = currentCameraId ?: return false
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: return false
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        } catch (e: Exception) {
            Timber.e(e, "Failed to check RAW support")
            false
        }
    }
    
    /**
     * 捕获 RAW 照片（用于 LumaLog 真 RAW 格式）
     * 
     * @param flashMode 闪光灯模式
     * @return RawCaptureResult 包含 RAW Image、相机特性和捕获结果
     */
    suspend fun captureRawPhoto(flashMode: FlashMode = FlashMode.OFF): RawCaptureResult = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        
        val camera = currentCamera ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("Camera not opened"))
            }
            return@suspendCancellableCoroutine
        }
        val session = currentSession ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("No capture session"))
            }
            return@suspendCancellableCoroutine
        }
        val rawReader = rawImageReader ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("RAW capture not supported on this device"))
            }
            return@suspendCancellableCoroutine
        }
        
        val cameraId = currentCameraId ?: "0"
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        try {
            // 计算图像方向
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val deviceOrientation = sensorInfoManager.orientation.value
            val deviceRotation = when (deviceOrientation) {
                SensorInfoManager.DeviceOrientation.PORTRAIT -> 0
                SensorInfoManager.DeviceOrientation.LANDSCAPE_LEFT -> 90
                SensorInfoManager.DeviceOrientation.UPSIDE_DOWN -> 180
                SensorInfoManager.DeviceOrientation.LANDSCAPE_RIGHT -> 270
            }
            
            val jpegOrientation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation - deviceRotation + 360) % 360
            } else {
                (sensorOrientation + deviceRotation) % 360
            }
            
            // 创建 RAW 捕获请求
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawReader.surface)
                
                // 应用当前手动参数
                applyManualParameters(this, manualParameters)
                
                // 设置闪光灯
                when (flashMode) {
                    FlashMode.OFF -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    }
                    FlashMode.ON -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    }
                    FlashMode.AUTO -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    }
                    FlashMode.TORCH -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    }
                }
            }
            
            // 设置 RAW 图像监听器
            rawReader.setOnImageAvailableListener({ imageReader ->
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    // 注意：调用者需要负责关闭 Image
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        // 需要获取对应的 captureResult
                        // 由于 RAW 捕获的结果在 onCaptureCompleted 中，
                        // 这里暂时返回 null 的 captureResult，后面会在 callback 中更新
                        Timber.w("RAW image available, but waiting for capture result")
                    }
                }
            }, cameraHandler)
            
            // 执行捕获
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Timber.d("RAW capture completed")
                    
                    // 获取 RAW 图像
                    val rawImage = rawReader.acquireLatestImage()
                    if (rawImage != null) {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(RawCaptureResult(
                                rawImage = rawImage,
                                characteristics = characteristics,
                                captureResult = result,
                                jpegOrientation = jpegOrientation
                            ))
                        } else {
                            rawImage.close()
                        }
                    } else {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resumeWithException(CameraException("Failed to acquire RAW image"))
                        }
                    }
                }
                
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Timber.e("RAW capture failed: reason=${failure.reason}")
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resumeWithException(CameraException("RAW capture failed: ${failure.reason}"))
                    }
                }
            }, cameraHandler)
            
        } catch (e: CameraAccessException) {
            Timber.e(e, "RAW capture failed")
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * 关闭相机
     */
    fun closeCamera() {
        Timber.d("Closing camera session...")
        
        // 安全地停止重复请求（忽略错误，因为会话可能已失效）
        try {
            currentSession?.stopRepeating()
        } catch (e: Exception) {
            // 忽略 stopRepeating 错误 - 会话可能已经失效
            Timber.d("stopRepeating skipped (session may be invalid): ${e.message}")
        }
        
        // 安全地中止所有请求
        try {
            currentSession?.abortCaptures()
        } catch (e: Exception) {
            Timber.d("abortCaptures skipped: ${e.message}")
        }
        
        // 关闭会话和设备
        try {
            currentSession?.close()
        } catch (e: Exception) {
            Timber.d("Session close error: ${e.message}")
        }
        currentSession = null
        
        try {
            currentCamera?.close()
        } catch (e: Exception) {
            Timber.d("Camera close error: ${e.message}")
        }
        currentCamera = null
        
        rawImageReader?.close()
        rawImageReader = null
        
        jpegImageReader?.close()
        jpegImageReader = null
        
        // 清理预览 Surface 引用（不要释放它，它由 TextureView 管理）
        previewSurface = null
        
        previewRequest = null
        previewRequestBuilder = null
        
        _sessionState.value = SessionState.Closed
        Timber.d("Camera session closed")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        closeCamera()
        cameraThread.quitSafely()
        cameraExecutor.shutdown()
    }
    
    // 捕获回调
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // 可以在这里获取 AF/AE 状态
            val afState: Int? = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            val aeState: Int? = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
        }
    }
    
    // RAW 图像监听器
    private val rawImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        image?.let {
            // 发送到 Luma Imaging Engine 处理
            // TODO: 传递给 captureCallback
            it.close()
        }
    }
    
    // JPEG 图像监听器
    private val jpegImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        image?.let {
            // 保存 JPEG
            // TODO: 传递给存储模块
            it.close()
        }
    }
    
    private fun getCameraErrorMessage(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera is already in use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera is disabled"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service error"
        else -> "Unknown camera error: $error"
    }
    
    /**
     * 根据色温（开尔文）计算 RGB 颜色增益
     * 使用 Tanner Helland 的算法将色温转换为 RGB
     * 参考: https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html
     */
    private fun calculateColorGainsFromKelvin(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
        val temp = (kelvin / 100.0).coerceIn(10.0, 400.0)
        
        val r: Double
        val g: Double
        val b: Double
        
        // 计算红色
        r = if (temp <= 66) {
            1.0
        } else {
            val x = temp - 60
            1.292936186 * Math.pow(x, -0.1332047592)
        }.coerceIn(0.0, 1.0)
        
        // 计算绿色
        g = if (temp <= 66) {
            val x = temp
            0.39008157876902 * Math.log(x) - 0.63184144378622
        } else {
            val x = temp - 60
            1.129890861 * Math.pow(x, -0.0755148492)
        }.coerceIn(0.0, 1.0)
        
        // 计算蓝色
        b = if (temp >= 66) {
            1.0
        } else if (temp <= 19) {
            0.0
        } else {
            val x = temp - 10
            0.543206789 * Math.log(x) - 1.19625408914
        }.coerceIn(0.0, 1.0)
        
        // 将 RGB 转换为相机增益
        // 增益越高 = 颜色越强
        // 我们需要反转：低色温（暖色）应该减少蓝色、增加红色
        val baseGain = 1.0f
        val redGain = (baseGain / r).toFloat().coerceIn(0.5f, 4.0f)
        val blueGain = (baseGain / b).toFloat().coerceIn(0.5f, 4.0f)
        val greenGain = baseGain / g.toFloat().coerceIn(0.5f, 4.0f)
        
        // RGGB 格式：Red, Green(even), Green(odd), Blue
        return android.hardware.camera2.params.RggbChannelVector(
            redGain,
            greenGain,
            greenGain,
            blueGain
        )
    }
}

/**
 * 会话状态
 */
sealed class SessionState {
    object Closed : SessionState()
    object Opening : SessionState()
    object Ready : SessionState()
    data class Error(val message: String) : SessionState()
}

/**
 * 相机异常
 */
class CameraException(message: String) : Exception(message)

/**
 * 测光矩形导入
 */
typealias MeteringRectangle = android.hardware.camera2.params.MeteringRectangle
