package com.luma.camera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import android.util.SizeF
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * 传感器管理器
 *
 * 职责：
 * - 相机传感器信息获取
 * - 设备方向检测
 * - 光线传感器数据
 * - 陀螺仪稳定检测
 * - 多摄像头信息管理
 */
@Singleton
class SensorInfoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraManager: CameraManager
) : SensorEventListener {

    /**
     * 相机传感器信息
     */
    data class CameraSensorInfo(
        val cameraId: String,
        val facing: Int,                    // FRONT/BACK/EXTERNAL
        val sensorSize: SizeF,              // 传感器物理尺寸 (mm)
        val activeArraySize: Size,          // 有效像素阵列尺寸
        val pixelPitch: Float,              // 像素间距 (μm)
        val focalLengths: List<Float>,      // 可用焦距
        val apertures: List<Float>,         // 可用光圈
        val isoRange: IntRange,             // ISO 范围
        val exposureTimeRange: LongRange,   // 曝光时间范围 (ns)
        val opticalStabilization: Boolean,  // 光学防抖
        val hasFlash: Boolean,              // 是否有闪光灯
        val maxZoom: Float,                 // 最大数码变焦
        val hardwareLevel: Int,             // 硬件级别
        val rawSupported: Boolean,          // 支持 RAW
        val physicalCameraIds: List<String> // 物理摄像头 ID（用于多摄）
    ) {
        /**
         * 计算等效 35mm 焦距
         */
        fun get35mmEquivalent(focalLength: Float): Float {
            // 35mm 全画幅对角线 = 43.27mm
            val sensorDiagonal = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
            val cropFactor = 43.27f / sensorDiagonal
            return focalLength * cropFactor
        }

        /**
         * 计算视场角 (度)
         */
        fun getFieldOfView(focalLength: Float): Float {
            val diagonal = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
            return (2 * atan(diagonal / (2 * focalLength)) * 180 / Math.PI).toFloat()
        }

        val hardwareLevelString: String
            get() = when (hardwareLevel) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
    }

    /**
     * 设备方向
     */
    enum class DeviceOrientation {
        PORTRAIT,           // 竖屏
        LANDSCAPE_LEFT,     // 横屏（左转）
        LANDSCAPE_RIGHT,    // 横屏（右转）
        UPSIDE_DOWN         // 倒置
    }

    /**
     * 设备稳定性状态
     */
    data class StabilityState(
        val isStable: Boolean,
        val shakeMagnitude: Float,  // 0-1, 0=完全静止
        val pitch: Float,           // 俯仰角
        val roll: Float,            // 翻滚角
        val yaw: Float              // 偏航角
    )

    // 设备传感器管理器
    private var deviceSensorManager: AndroidSensorManager? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var lightSensor: Sensor? = null

    // 相机传感器信息缓存
    private val cameraSensorCache = mutableMapOf<String, CameraSensorInfo>()

    // 设备状态
    private val _orientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    val orientation: StateFlow<DeviceOrientation> = _orientation.asStateFlow()

    private val _stabilityState = MutableStateFlow(StabilityState(true, 0f, 0f, 0f, 0f))
    val stabilityState: StateFlow<StabilityState> = _stabilityState.asStateFlow()

    private val _ambientLight = MutableStateFlow(0f)
    val ambientLight: StateFlow<Float> = _ambientLight.asStateFlow()

    // 加速度数据（用于稳定性检测）
    private val accelerationHistory = FloatArray(3)
    private var lastUpdateTime = 0L

    /**
     * 初始化传感器
     */
    fun initialize() {
        deviceSensorManager = context.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager
        
        accelerometer = deviceSensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = deviceSensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        lightSensor = deviceSensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        // 注册传感器监听
        accelerometer?.let {
            deviceSensorManager?.registerListener(this, it, AndroidSensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            deviceSensorManager?.registerListener(this, it, AndroidSensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            deviceSensorManager?.registerListener(this, it, AndroidSensorManager.SENSOR_DELAY_NORMAL)
        }

        // 加载所有相机信息
        loadAllCameraInfo()

        Timber.d("SensorInfoManager initialized")
    }

    /**
     * 释放传感器
     */
    fun release() {
        deviceSensorManager?.unregisterListener(this)
        Timber.d("SensorInfoManager released")
    }

    /**
     * 加载所有相机传感器信息
     */
    private fun loadAllCameraInfo() {
        try {
            val cameraIds = cameraManager.cameraIdList
            for (cameraId in cameraIds) {
                val info = getCameraSensorInfo(cameraId)
                if (info != null) {
                    cameraSensorCache[cameraId] = info
                    Timber.d("Loaded camera $cameraId: ${info.hardwareLevelString}, ${info.activeArraySize}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load camera info")
        }
    }

    /**
     * 获取相机传感器信息
     */
    fun getCameraSensorInfo(cameraId: String): CameraSensorInfo? {
        // 先检查缓存
        cameraSensorCache[cameraId]?.let { return it }

        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            parseCameraCharacteristics(cameraId, characteristics)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get camera info for $cameraId")
            null
        }
    }

    /**
     * 解析相机特性
     */
    private fun parseCameraCharacteristics(
        cameraId: String,
        characteristics: CameraCharacteristics
    ): CameraSensorInfo {
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) 
            ?: CameraMetadata.LENS_FACING_BACK

        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: SizeF(6.17f, 4.55f)  // 默认 1/2.55" 传感器

        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeArraySize = Size(activeArray?.width() ?: 4000, activeArray?.height() ?: 3000)

        // 计算像素间距 (μm)
        val pixelPitch = (sensorSize.width * 1000f) / activeArraySize.width

        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.toList() ?: listOf(4.25f)

        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.toList() ?: listOf(1.8f)

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val isoRangeInt = IntRange(isoRange?.lower ?: 100, isoRange?.upper ?: 3200)

        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val exposureRangeLong = LongRange(
            exposureRange?.lower ?: 100_000L,
            exposureRange?.upper ?: 1_000_000_000L
        )

        val ois = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false

        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        // 检查 RAW 支持
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val rawSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        // 物理摄像头 ID（用于多摄逻辑相机）
        val physicalCameraIds = try {
            characteristics.physicalCameraIds.toList()
        } catch (e: Exception) {
            emptyList()
        }

        return CameraSensorInfo(
            cameraId = cameraId,
            facing = facing,
            sensorSize = sensorSize,
            activeArraySize = activeArraySize,
            pixelPitch = pixelPitch,
            focalLengths = focalLengths,
            apertures = apertures,
            isoRange = isoRangeInt,
            exposureTimeRange = exposureRangeLong,
            opticalStabilization = ois,
            hasFlash = hasFlash,
            maxZoom = maxZoom,
            hardwareLevel = hardwareLevel,
            rawSupported = rawSupported,
            physicalCameraIds = physicalCameraIds
        )
    }

    /**
     * 获取所有后置摄像头
     */
    fun getBackCameras(): List<CameraSensorInfo> {
        return cameraSensorCache.values.filter { 
            it.facing == CameraMetadata.LENS_FACING_BACK 
        }
    }

    /**
     * 获取所有前置摄像头
     */
    fun getFrontCameras(): List<CameraSensorInfo> {
        return cameraSensorCache.values.filter { 
            it.facing == CameraMetadata.LENS_FACING_FRONT 
        }
    }

    /**
     * 获取主摄像头（后置主摄）
     */
    fun getMainCamera(): CameraSensorInfo? {
        return getBackCameras().maxByOrNull { 
            it.activeArraySize.width * it.activeArraySize.height 
        }
    }

    /**
     * 根据焦距获取最佳摄像头
     */
    fun getCameraForFocalLength(targetFocalLength: Float): CameraSensorInfo? {
        val backCameras = getBackCameras()
        if (backCameras.isEmpty()) return null

        return backCameras.minByOrNull { camera ->
            val closestFocal = camera.focalLengths.minByOrNull { abs(it - targetFocalLength) }
            abs((closestFocal ?: 0f) - targetFocalLength)
        }
    }

    // ==================== SensorEventListener ====================

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_LIGHT -> handleLightSensor(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 忽略精度变化
    }

    /**
     * 处理加速度计数据
     */
    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 计算设备方向
        val orientation = when {
            abs(y) > abs(x) && y > 0 -> DeviceOrientation.PORTRAIT
            abs(y) > abs(x) && y < 0 -> DeviceOrientation.UPSIDE_DOWN
            abs(x) > abs(y) && x > 0 -> DeviceOrientation.LANDSCAPE_RIGHT
            else -> DeviceOrientation.LANDSCAPE_LEFT
        }
        _orientation.value = orientation

        // 计算稳定性
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime > 100) {  // 100ms 更新一次
            val dx = abs(x - accelerationHistory[0])
            val dy = abs(y - accelerationHistory[1])
            val dz = abs(z - accelerationHistory[2])
            
            val shakeMagnitude = (sqrt(dx * dx + dy * dy + dz * dz) / 10f).coerceIn(0f, 1f)
            val isStable = shakeMagnitude < 0.1f

            // 计算姿态角
            val pitch = atan(y / sqrt(x * x + z * z)) * 180 / Math.PI
            val roll = atan(x / sqrt(y * y + z * z)) * 180 / Math.PI

            _stabilityState.value = StabilityState(
                isStable = isStable,
                shakeMagnitude = shakeMagnitude,
                pitch = pitch.toFloat(),
                roll = roll.toFloat(),
                yaw = 0f  // 需要磁力计配合
            )

            accelerationHistory[0] = x
            accelerationHistory[1] = y
            accelerationHistory[2] = z
            lastUpdateTime = currentTime
        }
    }

    /**
     * 处理陀螺仪数据
     */
    private fun handleGyroscope(event: SensorEvent) {
        // 可用于更精确的稳定性检测和视频防抖
        // 当前仅记录，未使用
    }

    /**
     * 处理光线传感器数据
     */
    private fun handleLightSensor(event: SensorEvent) {
        _ambientLight.value = event.values[0]
    }

    /**
     * 判断是否低光环境
     */
    fun isLowLight(): Boolean {
        return _ambientLight.value < 50f  // 50 lux 以下视为低光
    }

    /**
     * 建议的 ISO 基于环境光
     */
    fun suggestIsoForAmbientLight(): Int {
        val lux = _ambientLight.value
        return when {
            lux > 10000 -> 100    // 户外强光
            lux > 1000 -> 200     // 户外阴天
            lux > 500 -> 400      // 室内明亮
            lux > 100 -> 800      // 室内一般
            lux > 50 -> 1600      // 室内昏暗
            else -> 3200          // 低光
        }
    }
}
