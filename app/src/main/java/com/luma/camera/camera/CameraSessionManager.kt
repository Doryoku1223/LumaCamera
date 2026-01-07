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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 绠＄悊 Camera2 浼氳瘽锛屾敮鎸?120fps 棰勮
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
        
        // 120fps 棰勮閰嶇疆
        const val TARGET_PREVIEW_FPS = 120
        private const val MIN_PREVIEW_FPS = 60
        
        // 棰勮灏哄 (16:9)
        val PREVIEW_SIZE_4K = Size(3840, 2160)
        val PREVIEW_SIZE_1080P = Size(1920, 1080)
        val PREVIEW_SIZE_720P = Size(1280, 720)
    }
    
    // 浼氳瘽鐘舵€?
private val _sessionState = MutableStateFlow<SessionState>(SessionState.Closed)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    
    // 褰撳墠鐩告満璁惧
    private var currentCamera: CameraDevice? = null
    private var currentSession: CameraCaptureSession? = null
    private var currentCameraId: String? = null
    
    // 棰勮閰嶇疆
    private var previewSurface: Surface? = null
    private var previewSize: Size = PREVIEW_SIZE_1080P
    private var recordingSurface: Surface? = null
    
    // RAW 鎹曡幏
    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    
    // 绾跨▼鍜?Handler
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    // 棰勮璇锋眰
    private var previewRequest: CaptureRequest? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    
    // 鎵嬪姩鍙傛暟
    private var manualParameters = ManualParameters()
    private val sessionMutex = Mutex()
    
    /**
     * 鎵撳紑鐩告満骞跺垱寤轰細璇?     */
        @RequiresApi(Build.VERSION_CODES.P)
    suspend fun openCamera(
        focalLength: FocalLength,
        previewSurface: Surface,
        previewSize: Size = PREVIEW_SIZE_1080P,
        recordingSurface: Surface? = null
    ): Result<Unit> = withContext(ioDispatcher) {
        sessionMutex.withLock {
            try {
                _sessionState.value = SessionState.Opening
                if (currentSession != null || currentCamera != null) {
                    closeCamera()
                }

                this@CameraSessionManager.previewSurface = previewSurface
                this@CameraSessionManager.previewSize = previewSize
                this@CameraSessionManager.recordingSurface = recordingSurface
                Timber.d("openCamera bind: recordingSurfaceValid=${recordingSurface?.isValid == true}")

                val cameraId = multiCameraManager.getCameraIdForFocalLength(focalLength)
                    ?: throw CameraException("No camera found for focal length: $focalLength")

                currentCameraId = cameraId

                val device = openCameraDevice(cameraId)
                currentCamera = device

                setupImageReaders(cameraId)

                createHighFpsSession(device, previewSurface, recordingSurface)

                _sessionState.value = SessionState.Ready
                Result.success(Unit)
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }
    }
    /**
     * 鎵撳紑鐩告満璁惧
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
     * 鍒涘缓楂樺抚鐜囬瑙堜細璇?(120fps)
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun createHighFpsSession(
        device: CameraDevice,
        previewSurface: Surface,
        recordingSurface: Surface? = null
    ) = suspendCancellableCoroutine { continuation ->
        val cameraId = currentCameraId ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(CameraException("No camera ID"))
            }
            return@suspendCancellableCoroutine
        }
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        // 鑾峰彇鏀寔鐨勫抚鐜囪寖鍥?
val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val targetFpsRange = findBestFpsRange(fpsRanges ?: arrayOf())
        
        // 閰嶇疆杈撳嚭
        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(OutputConfiguration(previewSurface))
        recordingSurface?.let { surface ->
            outputs.add(OutputConfiguration(surface))
        }
        
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
                    
                    // 鍒涘缓棰勮璇锋眰
                    try {
                        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            recordingSurface?.let { surface ->
                                addTarget(surface)
                            }
                            
                            // 璁剧疆鐩爣甯х巼
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
                            
                            // 浼樺寲棰勮寤惰繜
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            
                            // 寮€鍚?HDR+ (濡傛灉鏀寔)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                // Android 14+ HDR 鏀寔
                            }
                        }
                        
                        previewRequest = previewRequestBuilder?.build()
                        
                        // 寮€濮嬮瑙?
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
     * 璁剧疆鍥惧儚璇诲彇鍣?     */
    private fun setupImageReaders(cameraId: String) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        // RAW 鎹曡幏
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
        
        // JPEG 鎹曡幏
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
     * 鏌ユ壘鏈€浣冲抚鐜囪寖鍥?     */
    private fun findBestFpsRange(ranges: Array<Range<Int>>): Range<Int> {
        // 浼樺厛閫夋嫨 120fps
        ranges.find { it.upper == TARGET_PREVIEW_FPS }?.let { return it }
        
        // 鍏舵 60fps
        ranges.find { it.upper >= MIN_PREVIEW_FPS }?.let { return it }
        
        // 榛樿鏈€楂樺抚鐜?
return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
    }
    
    /**
     * 鏇存柊鎵嬪姩鍙傛暟
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
     * 搴旂敤鎵嬪姩鍙傛暟
     */
    private fun applyManualParameters(builder: CaptureRequest.Builder, params: ManualParameters) {
        // ISO
        params.iso?.let { iso ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }
        
        // 蹇棬閫熷害
        params.shutterSpeed?.let { speed ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            // 杞崲涓虹撼绉?
val exposureNs = (1_000_000_000L / speed).toLong()
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        }
        
        // 鎵嬪姩瀵圭劍璺濈
        params.focusDistance?.let { distance ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        }
        
        // 鐧藉钩琛?
when (params.whiteBalanceMode) {
            WhiteBalanceMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
            WhiteBalanceMode.MANUAL -> {
                // 鎵嬪姩鑹叉俯闇€瑕佸叧闂?AWB
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                
                // 鏍规嵁鑹叉俯璁＄畻 RGB 澧炵泭
                val gains = calculateColorGainsFromKelvin(params.whiteBalanceKelvin)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                Timber.d("Applied manual white balance: ${params.whiteBalanceKelvin}K -> gains=${gains}")
            }
            else -> {
                // 棰勮鐧藉钩琛?
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
        
        // 鏇濆厜琛ュ伩 (Auto 妯″紡涓?
        if (params.iso == null && params.shutterSpeed == null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            
            // 搴旂敤鏇濆厜琛ュ伩锛堝鏋滀笉涓?0锛?
if (params.exposureCompensation != 0f) {
                val cameraId = currentCameraId
                if (cameraId != null) {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    
                    if (range != null && step != null) {
                        // EV 鍊奸櫎浠ユ闀匡紝寰楀埌琛ュ伩姝ユ暟
                        val compensation = (params.exposureCompensation / step.toFloat()).toInt()
                            .coerceIn(range.lower, range.upper)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
                        Timber.d("Applied EV compensation: ${params.exposureCompensation} -> $compensation steps (step=$step, range=$range)")
                    }
                }
            } else {
                // 閲嶇疆涓?0
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            }
        }
    }
    
    /**
     * 瑙︽懜瀵圭劍
     */
    suspend fun triggerTouchFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val builder = previewRequestBuilder ?: return
        val cameraId = currentCameraId ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        // 璁＄畻瀵圭劍鍖哄煙
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
        
        // 璁剧疆瀵圭劍鍜屾祴鍏夊尯鍩?
builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRegion))
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        
        // 鍙戦€佸崟娆¤姹傝Е鍙戝鐒?
val focusRequest = builder.build()
        currentSession?.capture(focusRequest, captureCallback, cameraHandler)
        
        // 閲嶇疆 trigger
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        previewRequest = builder.build()
        currentSession?.setRepeatingRequest(previewRequest!!, captureCallback, cameraHandler)
    }
    
    /**
     * 鍒囨崲鐒︽
     */
    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun switchFocalLength(focalLength: FocalLength): Result<Unit> {
        val surface = previewSurface ?: return Result.failure(CameraException("No preview surface"))
        
        // 鍏抽棴褰撳墠鐩告満
        closeCamera()
        
        // 鎵撳紑鏂扮劍娈电浉鏈?
return openCamera(focalLength, surface, previewSize, recordingSurface)
    }
    
    /**
     * 璁剧疆闂厜鐏ā寮?     */
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
     * 鎷嶇収
     * @param flashMode 闂厜鐏ā寮?     * @return JPEG 鍥惧儚鏁版嵁
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

        // 璁剧疆鍥惧儚鍙敤鍥炶皟
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

        // 鍒涘缓鎷嶇収璇锋眰
        try {
            // 鑾峰彇鐩告満浼犳劅鍣ㄦ柟鍚?
val cameraId = currentCameraId ?: "0"
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            
            // 鑾峰彇璁惧褰撳墠鐗╃悊鏂瑰悜
            val deviceOrientation = sensorInfoManager.orientation.value
            val deviceRotation = when (deviceOrientation) {
                SensorInfoManager.DeviceOrientation.PORTRAIT -> 0
                SensorInfoManager.DeviceOrientation.LANDSCAPE_LEFT -> 90
                SensorInfoManager.DeviceOrientation.UPSIDE_DOWN -> 180
                SensorInfoManager.DeviceOrientation.LANDSCAPE_RIGHT -> 270
            }
            
            // 璁＄畻 JPEG 鏂瑰悜
            // 鍏紡锛?sensorOrientation + deviceRotation) % 360 瀵逛簬鍚庣疆鐩告満
            // 瀵逛簬鍓嶇疆鐩告満闇€瑕侀暅鍍忓鐞?
val jpegOrientation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation - deviceRotation + 360) % 360
            } else {
                (sensorOrientation + deviceRotation) % 360
            }
            
            Timber.d("Orientation calculation: sensor=$sensorOrientation, device=$deviceRotation, facing=$facing, jpeg=$jpegOrientation")
            
            // 妫€鏌ユ槸鍚﹂渶瑕?AE precapture (鐢ㄤ簬 AUTO 鍜?ON 闂厜鐏ā寮?
            val needsPrecapture = (flashMode == FlashMode.AUTO || flashMode == FlashMode.ON) &&
                    manualParameters.iso == null && manualParameters.shutterSpeed == null
            
            if (needsPrecapture) {
                // 瑙﹀彂 AE precapture 搴忓垪
                runAePrecaptureSequence(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            } else {
                // 鐩存帴鎷嶇収
                captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            }
        } catch (e: CameraAccessException) {
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * 杩愯 AE 棰勬崟鑾峰簭鍒楋紙鐢ㄤ簬闂厜鐏祴鍏夛級
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
                // 璁剧疆闂厜鐏ā寮?
when (flashMode) {
                    FlashMode.AUTO -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    }
                    FlashMode.ON -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    }
                    else -> {}
                }
                
                // 瑙﹀彂 AE precapture
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Timber.d("AE precapture started")
                        // 閲嶇疆 precapture trigger
                        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                        
                        // 绛夊緟 AE 鏀舵暃鍚庢媿鐓?
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
                // 濡傛灉娌℃湁
// No previewRequestBuilder, capture directly
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
     * 绛夊緟 AE 鏀舵暃鍚庢媿鐓?     */
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
        val maxWaitCount = 5 // 鏈€澶氱瓑寰?甯?        
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
                        
                        // 妫€鏌?AE 鏄惁鏀舵暃鎴栧凡鍑嗗濂介棯鍏?
val isAeReady = aeState == null ||
                                aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                                aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                waitCount >= maxWaitCount
                        
                        if (isAeReady) {
                            // 鍋滄閲嶅璇锋眰鐨勫洖璋冪洃鍚紝鎭㈠姝ｅ父棰勮
                            try {
                                session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                            } catch (e: CameraAccessException) {
                                Timber.e(e, "Failed to restore preview")
                            }
                            
                            // 鎵ц鎷嶇収
                            captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
                        }
                    }
                }, cameraHandler)
            } catch (e: CameraAccessException) {
                Timber.e(e, "Failed to wait for AE convergence")
                // 鍗充娇澶辫触涔熷皾璇曟媿鐓?
captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
            }
        } ?: run {
            captureStillPicture(session, camera, reader, flashMode, jpegOrientation, resumed, continuation)
        }
    }
    
    /**
     * 鎵ц瀹為檯鐨勯潤鎬佸浘鐗囨崟鑾?     */
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
                
                // 璁剧疆 JPEG 鏂瑰悜 - 淇鐓х墖鏃嬭浆闂
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                
                // 闂厜鐏缃?
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
                        // TORCH 妯″紡锛氫繚鎸佹墜鐢电瓛甯镐寒
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                }
                
                // 搴旂敤鎵嬪姩鍙傛暟
                if (manualParameters.iso != null || manualParameters.shutterSpeed != null) {
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    
                    // ISO
                    manualParameters.iso?.let { iso ->
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                    
                    // 蹇棬閫熷害 (杞崲涓虹撼绉?
                    manualParameters.shutterSpeed?.let { speed ->
                        val exposureTimeNs = (1_000_000_000L / speed).toLong()
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
                    }
                }
                
                // 璁剧疆 JPEG 璐ㄩ噺
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                
                // 璁剧疆鑷姩瀵圭劍
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
     * RAW 鎹曡幏缁撴灉
     */
    data class RawCaptureResult(
        val rawImage: android.media.Image,
        val characteristics: CameraCharacteristics,
        val captureResult: TotalCaptureResult,
        val jpegOrientation: Int
    )
    
    /**
     * 妫€鏌ュ綋鍓嶇浉鏈烘槸鍚︽敮鎸?RAW 鎹曡幏
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
     * 鎹曡幏 RAW 鐓х墖锛堢敤浜?LumaLog 鐪?RAW 鏍煎紡锛?     * 
     * @param flashMode 闂厜鐏ā寮?     * @return RawCaptureResult 鍖呭惈 RAW Image銆佺浉鏈虹壒鎬у拰鎹曡幏缁撴灉
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
            // 璁＄畻鍥惧儚鏂瑰悜
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
            
            // 鍒涘缓 RAW 鎹曡幏璇锋眰
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawReader.surface)
                
                // 搴旂敤褰撳墠鎵嬪姩鍙傛暟
                applyManualParameters(this, manualParameters)
                
                // 璁剧疆闂厜鐏?
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
            
            // 璁剧疆 RAW 鍥惧儚鐩戝惉鍣?
rawReader.setOnImageAvailableListener({ imageReader ->
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    // 娉ㄦ剰锛氳皟鐢ㄨ€呴渶瑕佽礋璐ｅ叧闂?Image
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        // 闇€瑕佽幏鍙栧搴旂殑 captureResult
                        // 鐢变簬 RAW 鎹曡幏鐨勭粨鏋滃湪 onCaptureCompleted 涓紝
                        // 杩欓噷鏆傛椂杩斿洖 null 鐨?captureResult锛屽悗闈細鍦?callback 涓洿鏂?
Timber.w("RAW image available, but waiting for capture result")
                    }
                }
            }, cameraHandler)
            
            // 鎵ц鎹曡幏
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Timber.d("RAW capture completed")
                    
                    // 鑾峰彇 RAW 鍥惧儚
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
     * 鍏抽棴鐩告満
     */
    fun closeCamera() {
        Timber.d("Closing camera session...")
        
        // 瀹夊叏鍦板仠姝㈤噸澶嶈姹傦紙蹇界暐閿欒锛屽洜涓轰細璇濆彲鑳藉凡澶辨晥锛?
try {
            currentSession?.stopRepeating()
        } catch (e: Exception) {
            // 蹇界暐 stopRepeating 閿欒 - 浼氳瘽鍙兘宸茬粡澶辨晥
            Timber.d("stopRepeating skipped (session may be invalid): ${e.message}")
        }
        
        // 瀹夊叏鍦颁腑姝㈡墍鏈夎姹?
try {
            currentSession?.abortCaptures()
        } catch (e: Exception) {
            Timber.d("abortCaptures skipped: ${e.message}")
        }
        
        // 鍏抽棴浼氳瘽鍜岃澶?
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
        
        // 娓呯悊棰勮 Surface 寮曠敤锛堜笉瑕侀噴鏀惧畠锛屽畠鐢?TextureView 绠＄悊锛?        previewSurface = null
        recordingSurface = null
        
        previewRequest = null
        previewRequestBuilder = null
        
        _sessionState.value = SessionState.Closed
        Timber.d("Camera session closed")
    }
    
    /**
     * 閲婃斁璧勬簮
     */
    fun release() {
        closeCamera()
        cameraThread.quitSafely()
        cameraExecutor.shutdown()
    }
    
    // 鎹曡幏鍥炶皟
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // 鍙互鍦ㄨ繖閲岃幏鍙?AF/AE 鐘舵€?
val afState: Int? = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            val aeState: Int? = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
        }
    }
    
    // RAW 鍥惧儚鐩戝惉鍣?
private val rawImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        image?.let {
            // 鍙戦€佸埌 Luma Imaging Engine 澶勭悊
            // TODO: 浼犻€掔粰 captureCallback
            it.close()
        }
    }
    
    // JPEG 鍥惧儚鐩戝惉鍣?
private val jpegImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        image?.let {
            // 淇濆瓨 JPEG
            // TODO: 浼犻€掔粰瀛樺偍妯″潡
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
     * 鏍规嵁鑹叉俯锛堝紑灏旀枃锛夎绠?RGB 棰滆壊澧炵泭
     * 浣跨敤 Tanner Helland 鐨勭畻娉曞皢鑹叉俯杞崲涓?RGB
     * 鍙傝€? https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html
     */
    private fun calculateColorGainsFromKelvin(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
        val temp = (kelvin / 100.0).coerceIn(10.0, 400.0)
        
        val r: Double
        val g: Double
        val b: Double
        
        // 璁＄畻绾㈣壊
        r = if (temp <= 66) {
            1.0
        } else {
            val x = temp - 60
            1.292936186 * Math.pow(x, -0.1332047592)
        }.coerceIn(0.0, 1.0)
        
        // 璁＄畻缁胯壊
        g = if (temp <= 66) {
            val x = temp
            0.39008157876902 * Math.log(x) - 0.63184144378622
        } else {
            val x = temp - 60
            1.129890861 * Math.pow(x, -0.0755148492)
        }.coerceIn(0.0, 1.0)
        
        // 璁＄畻钃濊壊
        b = if (temp >= 66) {
            1.0
        } else if (temp <= 19) {
            0.0
        } else {
            val x = temp - 10
            0.543206789 * Math.log(x) - 1.19625408914
        }.coerceIn(0.0, 1.0)
        
        // 灏?RGB 杞崲涓虹浉鏈哄鐩?        // 澧炵泭瓒婇珮 = 棰滆壊瓒婂己
        // 鎴戜滑闇€瑕佸弽杞細浣庤壊娓╋紙鏆栬壊锛夊簲璇ュ噺灏戣摑鑹层€佸鍔犵孩鑹?
val baseGain = 1.0f
        val redGain = (baseGain / r).toFloat().coerceIn(0.5f, 4.0f)
        val blueGain = (baseGain / b).toFloat().coerceIn(0.5f, 4.0f)
        val greenGain = baseGain / g.toFloat().coerceIn(0.5f, 4.0f)
        
        // RGGB 鏍煎紡锛歊ed, Green(even), Green(odd), Blue
        return android.hardware.camera2.params.RggbChannelVector(
            redGain,
            greenGain,
            greenGain,
            blueGain
        )
    }
}

/**
 * 浼氳瘽鐘舵€? */
sealed class SessionState {
    object Closed : SessionState()
    object Opening : SessionState()
    object Ready : SessionState()
    data class Error(val message: String) : SessionState()
}

/**
 * 鐩告満寮傚父
 */
class CameraException(message: String) : Exception(message)

/**
 * 娴嬪厜鐭╁舰瀵煎叆
 */
typealias MeteringRectangle = android.hardware.camera2.params.MeteringRectangle


