package com.luma.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.luma.camera.domain.model.AspectRatio
import com.luma.camera.domain.model.FlashMode
import com.luma.camera.domain.model.FocalLength
import com.luma.camera.domain.model.ManualParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Camera2 相机控制器
 *
 * 职责：
 * - 管理相机设备生命周期
 * - 处理预览和拍照
 * - 多摄像头切换
 * - 120fps 预览支持
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraManager: CameraManager
) {
    // 相机状态
    sealed class State {
        data object Closed : State()
        data object Opening : State()
        data class Opened(val cameraDevice: CameraDevice) : State()
        data class Previewing(val session: CameraCaptureSession) : State()
        data class Error(val exception: Exception) : State()
    }

    private val _state = MutableStateFlow<State>(State.Closed)
    val state: StateFlow<State> = _state.asStateFlow()

    // 相机线程
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // 当前相机
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    // ImageReader 用于拍照
    private var imageReader: ImageReader? = null

    // 预览 Surface
    private var previewSurface: Surface? = null

    // 当前摄像头 ID
    private var currentCameraId: String? = null

    // 相机特性缓存
    private val cameraCharacteristicsCache = mutableMapOf<String, CameraCharacteristics>()

    /**
     * 初始化相机线程
     */
    fun initialize() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    /**
     * 设置预览 Surface
     */
    fun setPreviewSurface(surface: Surface) {
        previewSurface = surface
        // 如果相机已打开，重新开始预览
        cameraDevice?.let { device ->
            // 这里可以触发重新创建预览会话
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    /**
     * 获取对应焦段的摄像头 ID
     */
    fun getCameraIdForFocalLength(focalLength: FocalLength): String? {
        // 简化实现：查找后置摄像头
        // 实际应该根据焦段查找对应的物理摄像头
        val cameraIds = cameraManager.cameraIdList
        for (id in cameraIds) {
            val characteristics = getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraIds.firstOrNull()
    }

    /**
     * 获取相机特性
     */
    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
        return cameraCharacteristicsCache.getOrPut(cameraId) {
            cameraManager.getCameraCharacteristics(cameraId)
        }
    }

    /**
     * 打开相机
     */
    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): CameraDevice = suspendCoroutine { continuation ->
        _state.value = State.Opening
        currentCameraId = cameraId

        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        _state.value = State.Opened(camera)
                        continuation.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        _state.value = State.Closed
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        val exception = CameraAccessException(error)
                        _state.value = State.Error(exception)
                        continuation.resumeWithException(exception)
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            _state.value = State.Error(e)
            continuation.resumeWithException(e)
        }
    }

    /**
     * 关闭相机
     */
    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        _state.value = State.Closed
    }

    /**
     * 创建预览会话
     */
    suspend fun startPreview(
        previewSurface: Surface,
        aspectRatio: AspectRatio = AspectRatio.RATIO_4_3
    ): CameraCaptureSession = suspendCoroutine { continuation ->
        val camera = cameraDevice ?: throw IllegalStateException("Camera not opened")
        val cameraId = currentCameraId ?: throw IllegalStateException("No camera selected")
        
        this.previewSurface = previewSurface

        // 获取最佳预览尺寸
        val characteristics = getCameraCharacteristics(cameraId)
        val previewSize = getOptimalPreviewSize(characteristics, aspectRatio)

        // 创建用于拍照的 ImageReader
        imageReader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.JPEG,
            2
        )

        val surfaces = listOf(previewSurface, imageReader!!.surface)

        try {
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        
                        // 创建预览请求
                        previewRequestBuilder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            addTarget(previewSurface)
                            // 设置自动对焦
                            set(CaptureRequest.CONTROL_AF_MODE, 
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            // 设置自动曝光
                            set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON)
                        }

                        // 开始预览
                        session.setRepeatingRequest(
                            previewRequestBuilder!!.build(),
                            null,
                            cameraHandler
                        )

                        _state.value = State.Previewing(session)
                        continuation.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val exception = RuntimeException("Camera session configuration failed")
                        _state.value = State.Error(exception)
                        continuation.resumeWithException(exception)
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            _state.value = State.Error(e)
            continuation.resumeWithException(e)
        }
    }

    /**
     * 拍照
     */
    suspend fun capturePhoto(
        flashMode: FlashMode = FlashMode.OFF,
        manualParameters: ManualParameters = ManualParameters()
    ): ByteArray = suspendCoroutine { continuation ->
        val camera = cameraDevice ?: throw IllegalStateException("Camera not opened")
        val session = captureSession ?: throw IllegalStateException("No capture session")
        val reader = imageReader ?: throw IllegalStateException("No image reader")

        // 设置图像可用回调
        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            continuation.resume(bytes)
        }, cameraHandler)

        // 创建拍照请求
        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            
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
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }

            // 应用手动参数
            applyManualParameters(this, manualParameters)
        }

        try {
            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // 拍照完成
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            continuation.resumeWithException(e)
        }
    }

    /**
     * 触摸对焦
     */
    fun triggerFocus(x: Float, y: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        // 触发自动对焦
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        
        try {
            session.capture(builder.build(), null, cameraHandler)
            
            // 恢复连续对焦
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        } catch (e: CameraAccessException) {
            // 忽略
        }
    }

    /**
     * 应用手动参数到 CaptureRequest
     */
    private fun applyManualParameters(
        builder: CaptureRequest.Builder,
        params: ManualParameters
    ) {
        // 曝光补偿
        if (params.exposureCompensation != 0f) {
            val cameraId = currentCameraId ?: return
            val characteristics = getCameraCharacteristics(cameraId)
            val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            if (range != null && step != null) {
                val compensation = (params.exposureCompensation / step.toFloat()).toInt()
                    .coerceIn(range.lower, range.upper)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
            }
        }

        // ISO
        params.iso?.let { iso ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        }

        // 快门速度
        params.shutterSpeed?.let { shutter ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (shutter * 1_000_000_000).toLong())
        }

        // 手动对焦
        params.focusDistance?.let { distance ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val cameraId = currentCameraId ?: return
            val characteristics = getCameraCharacteristics(cameraId)
            val minFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f
            val focusDist = minFocusDistance * (1f - distance)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
        }

        // AE/AF 锁定
        if (params.isAeLocked) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
    }

    /**
     * 获取最佳预览尺寸
     */
    private fun getOptimalPreviewSize(
        characteristics: CameraCharacteristics,
        aspectRatio: AspectRatio
    ): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1440)
        
        val sizes = map.getOutputSizes(ImageFormat.JPEG)
        
        // 按面积降序排列
        val sortedSizes = sizes.sortedByDescending { it.width * it.height }
        
        // 查找匹配比例的最大尺寸
        val targetRatio = if (aspectRatio == AspectRatio.FULL) {
            16f / 9f
        } else {
            aspectRatio.widthRatio.toFloat() / aspectRatio.heightRatio
        }
        
        for (size in sortedSizes) {
            val sizeRatio = size.width.toFloat() / size.height
            if (kotlin.math.abs(sizeRatio - targetRatio) < 0.1f) {
                return size
            }
        }
        
        return sortedSizes.firstOrNull() ?: Size(1920, 1440)
    }
}
