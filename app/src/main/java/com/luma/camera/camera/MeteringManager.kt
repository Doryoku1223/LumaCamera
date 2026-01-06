package com.luma.camera.camera

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 测光管理器
 *
 * 职责：
 * - 多模式测光（点测光、中央重点、矩阵测光）
 * - 曝光计算（光圈、快门、ISO 三角）
 * - 曝光补偿
 * - AE 锁定
 *
 * 算法精确度目标：< 0.3 EV 误差
 */
@Singleton
class MeteringManager @Inject constructor() {

    companion object {
        private const val TAG = "MeteringManager"
        
        // 测光权重
        private const val CENTER_WEIGHT = 0.6f
        private const val SPOT_WEIGHT = 1.0f
        
        // 曝光计算常量
        private const val CALIBRATION_CONSTANT = 12.5f  // 标准测光校准常量
        private const val ISO_BASE = 100
    }

    /**
     * 测光模式
     */
    enum class MeteringMode {
        MATRIX,         // 矩阵测光（评价测光）
        CENTER_WEIGHTED, // 中央重点测光
        SPOT,           // 点测光
        HIGHLIGHT       // 高光优先测光
    }

    /**
     * 曝光三角参数
     */
    data class ExposureTriangle(
        val iso: Int,
        val shutterSpeed: Long,      // 纳秒
        val aperture: Float,         // f-stop
        val ev: Float                // 曝光值
    ) {
        val shutterSpeedFraction: String
            get() {
                val seconds = shutterSpeed / 1_000_000_000.0
                return if (seconds >= 1) {
                    "${seconds.roundToInt()}s"
                } else {
                    "1/${(1.0 / seconds).roundToInt()}"
                }
            }
    }

    /**
     * 测光结果
     */
    data class MeteringResult(
        val luminance: Float,        // 场景亮度 (cd/m²)
        val ev: Float,               // 曝光值
        val suggestedExposure: ExposureTriangle,
        val meteringRegions: List<MeteringRectangle>,
        val isHighlightClipping: Boolean,
        val isShadowClipping: Boolean
    )

    // 当前测光模式
    private var currentMode = MeteringMode.MATRIX
    
    // AE 锁定状态
    private var aeLocked = false
    private var lockedExposure: ExposureTriangle? = null

    // 曝光补偿 (EV)
    private var exposureCompensation = 0f

    // 传感器特性
    private var sensorArraySize: Rect? = null
    private var isoRange: android.util.Range<Int>? = null
    private var exposureTimeRange: android.util.Range<Long>? = null
    private var maxAperture: Float = 1.8f

    /**
     * 初始化传感器特性
     */
    fun initializeSensorCharacteristics(characteristics: CameraCharacteristics) {
        sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        
        // 获取镜头光圈
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { apertures ->
            if (apertures.isNotEmpty()) {
                maxAperture = apertures.minOrNull() ?: 1.8f
            }
        }

        Timber.d("Sensor initialized: ISO range=$isoRange, exposure range=$exposureTimeRange, aperture=$maxAperture")
    }

    /**
     * 设置测光模式
     */
    fun setMeteringMode(mode: MeteringMode) {
        currentMode = mode
        Timber.d("Metering mode changed to: $mode")
    }

    /**
     * 获取当前测光模式
     */
    fun getMeteringMode(): MeteringMode = currentMode

    /**
     * 设置曝光补偿
     */
    fun setExposureCompensation(ev: Float) {
        exposureCompensation = ev.coerceIn(-5f, 5f)
    }

    /**
     * 锁定 AE
     */
    fun lockAe(currentExposure: ExposureTriangle) {
        aeLocked = true
        lockedExposure = currentExposure
        Timber.d("AE locked at: $currentExposure")
    }

    /**
     * 解锁 AE
     */
    fun unlockAe() {
        aeLocked = false
        lockedExposure = null
        Timber.d("AE unlocked")
    }

    /**
     * 检查 AE 是否锁定
     */
    fun isAeLocked(): Boolean = aeLocked

    /**
     * 计算测光区域
     *
     * @param touchPoint 触摸点（归一化坐标 0-1）
     * @param previewWidth 预览宽度
     * @param previewHeight 预览高度
     * @return 测光区域列表
     */
    fun calculateMeteringRegions(
        touchPoint: PointF? = null,
        previewWidth: Int,
        previewHeight: Int
    ): List<MeteringRectangle> {
        val sensorRect = sensorArraySize ?: return emptyList()
        
        return when (currentMode) {
            MeteringMode.MATRIX -> createMatrixMeteringRegions(sensorRect)
            MeteringMode.CENTER_WEIGHTED -> createCenterWeightedRegion(sensorRect)
            MeteringMode.SPOT -> {
                val point = touchPoint ?: PointF(0.5f, 0.5f)
                createSpotMeteringRegion(point, sensorRect, previewWidth, previewHeight)
            }
            MeteringMode.HIGHLIGHT -> createHighlightMeteringRegions(sensorRect)
        }
    }

    /**
     * 矩阵测光：使用多个区域平均测光
     */
    private fun createMatrixMeteringRegions(sensorRect: Rect): List<MeteringRectangle> {
        val regions = mutableListOf<MeteringRectangle>()
        val gridSize = 3
        val cellWidth = sensorRect.width() / gridSize
        val cellHeight = sensorRect.height() / gridSize

        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val left = sensorRect.left + col * cellWidth
                val top = sensorRect.top + row * cellHeight
                val rect = Rect(left, top, left + cellWidth, top + cellHeight)
                
                // 中心区域权重更高
                val weight = if (row == 1 && col == 1) 1000 else 500
                regions.add(MeteringRectangle(rect, weight))
            }
        }
        return regions
    }

    /**
     * 中央重点测光：中心区域权重高
     */
    private fun createCenterWeightedRegion(sensorRect: Rect): List<MeteringRectangle> {
        val centerWidth = (sensorRect.width() * 0.3f).toInt()
        val centerHeight = (sensorRect.height() * 0.3f).toInt()
        val centerX = sensorRect.centerX()
        val centerY = sensorRect.centerY()

        val centerRect = Rect(
            centerX - centerWidth / 2,
            centerY - centerHeight / 2,
            centerX + centerWidth / 2,
            centerY + centerHeight / 2
        )

        return listOf(
            MeteringRectangle(centerRect, 1000),
            MeteringRectangle(sensorRect, 200)  // 整个画面低权重
        )
    }

    /**
     * 点测光：在指定位置的小区域测光
     */
    private fun createSpotMeteringRegion(
        point: PointF,
        sensorRect: Rect,
        previewWidth: Int,
        previewHeight: Int
    ): List<MeteringRectangle> {
        // 将触摸点转换为传感器坐标
        val sensorX = (point.x * sensorRect.width() + sensorRect.left).toInt()
        val sensorY = (point.y * sensorRect.height() + sensorRect.top).toInt()

        // 点测光区域大小（传感器的 3%）
        val spotSize = (min(sensorRect.width(), sensorRect.height()) * 0.03f).toInt()

        val spotRect = Rect(
            (sensorX - spotSize / 2).coerceIn(sensorRect.left, sensorRect.right - spotSize),
            (sensorY - spotSize / 2).coerceIn(sensorRect.top, sensorRect.bottom - spotSize),
            (sensorX + spotSize / 2).coerceIn(sensorRect.left + spotSize, sensorRect.right),
            (sensorY + spotSize / 2).coerceIn(sensorRect.top + spotSize, sensorRect.bottom)
        )

        return listOf(MeteringRectangle(spotRect, 1000))
    }

    /**
     * 高光优先测光：针对高光区域测光，防止过曝
     */
    private fun createHighlightMeteringRegions(sensorRect: Rect): List<MeteringRectangle> {
        // 高光优先主要通过降低曝光实现，测光区域使用中央重点
        return createCenterWeightedRegion(sensorRect)
    }

    /**
     * 根据场景亮度计算曝光参数
     *
     * @param luminance 场景平均亮度
     * @param preferredIso 首选 ISO（用户设置或自动）
     * @param minShutterSpeed 最小快门速度（用于手持拍摄）
     */
    fun calculateExposure(
        luminance: Float,
        preferredIso: Int? = null,
        minShutterSpeed: Long = 1_000_000_000L / 60  // 默认 1/60s
    ): ExposureTriangle {
        // 如果 AE 锁定，返回锁定的值
        if (aeLocked && lockedExposure != null) {
            return lockedExposure!!
        }

        // 计算 EV（基于亮度和校准常量）
        // EV = log2(luminance * S / K)，其中 S=ISO, K=校准常量
        // 简化：EV = log2(luminance / 0.3) + 调整
        val baseEv = (ln(luminance.coerceAtLeast(0.001f) / 0.3f) / ln(2f)) + 3f
        
        // 应用曝光补偿
        val targetEv = baseEv + exposureCompensation

        // 高光优先模式额外减少 1 EV
        val finalEv = if (currentMode == MeteringMode.HIGHLIGHT) {
            targetEv + 1f
        } else {
            targetEv
        }

        // 选择 ISO
        val iso = preferredIso ?: selectAutoIso(finalEv, minShutterSpeed)
        
        // 计算快门速度
        // EV = log2(N² / t) - log2(ISO / 100)
        // t = N² / (2^EV * ISO / 100)
        val n2 = maxAperture * maxAperture
        val shutterSeconds = n2 / (2f.pow(finalEv) * iso / 100f)
        val shutterNanos = (shutterSeconds * 1_000_000_000L).toLong()
            .coerceIn(exposureTimeRange?.lower ?: 100_000L, exposureTimeRange?.upper ?: 1_000_000_000L)

        return ExposureTriangle(
            iso = iso,
            shutterSpeed = shutterNanos,
            aperture = maxAperture,
            ev = finalEv
        )
    }

    /**
     * 自动选择 ISO
     */
    private fun selectAutoIso(ev: Float, minShutterSpeed: Long): Int {
        val isoMin = isoRange?.lower ?: 100
        val isoMax = isoRange?.upper ?: 6400

        // 根据目标 EV 和最小快门速度计算所需 ISO
        // 从低 ISO 开始，如果快门太慢则提高 ISO
        val n2 = maxAperture * maxAperture
        val shutterSeconds = minShutterSpeed / 1_000_000_000.0
        val requiredIso = (n2 / (2f.pow(ev) * shutterSeconds) * 100f).toInt()

        return requiredIso.coerceIn(isoMin, isoMax)
    }

    /**
     * 从捕获结果分析测光数据
     */
    fun analyzeCaptureResult(result: CaptureResult): MeteringResult? {
        // 获取曝光信息
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: maxAperture

        // 估算场景亮度
        val ev = calculateEvFromExposure(exposureTime, iso, aperture)
        val luminance = estimateLuminance(ev)

        // 创建当前曝光三角
        val currentExposure = ExposureTriangle(
            iso = iso,
            shutterSpeed = exposureTime,
            aperture = aperture,
            ev = ev
        )

        // 分析高光和暗部裁切（从 AE 状态）
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val isHighlightClipping = aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        val isShadowClipping = false  // 需要直方图分析

        return MeteringResult(
            luminance = luminance,
            ev = ev,
            suggestedExposure = currentExposure,
            meteringRegions = emptyList(),
            isHighlightClipping = isHighlightClipping,
            isShadowClipping = isShadowClipping
        )
    }

    /**
     * 从曝光参数计算 EV
     */
    private fun calculateEvFromExposure(shutterNanos: Long, iso: Int, aperture: Float): Float {
        val shutterSeconds = shutterNanos / 1_000_000_000.0
        val n2 = aperture * aperture
        // EV = log2(N² / t) - log2(ISO / 100)
        return (ln(n2 / shutterSeconds) / ln(2.0) - ln(iso / 100.0) / ln(2.0)).toFloat()
    }

    /**
     * 从 EV 估算场景亮度
     */
    private fun estimateLuminance(ev: Float): Float {
        // 反向计算：luminance = 0.3 * 2^(EV - 3)
        return 0.3f * 2f.pow(ev - 3f)
    }

    /**
     * 应用测光设置到 CaptureRequest
     */
    fun applyToRequest(
        builder: CaptureRequest.Builder,
        meteringRegions: List<MeteringRectangle>
    ) {
        if (meteringRegions.isNotEmpty()) {
            val regions = meteringRegions.toTypedArray()
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        }

        // AE 锁定
        builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)

        // 手动曝光设置（如果 AE 锁定）
        if (aeLocked && lockedExposure != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExposure!!.shutterSpeed)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, lockedExposure!!.iso)
        }
    }

    /**
     * 计算斑马纹阈值区域
     * 用于显示过曝警告
     */
    fun calculateZebraThreshold(ev: Float): Float {
        // 根据当前 EV 计算斑马纹显示阈值
        // EV 越高（更亮），阈值越高
        return (0.95f - (ev - 10f) * 0.01f).coerceIn(0.85f, 0.98f)
    }
}
