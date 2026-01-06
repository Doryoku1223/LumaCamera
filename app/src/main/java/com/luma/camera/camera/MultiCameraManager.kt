package com.luma.camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.luma.camera.domain.model.FocalLength
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多摄像头管理器
 *
 * 职责：
 * - 枚举所有物理摄像头
 * - 焦段到摄像头的映射
 * - 摄像头能力查询
 */
@Singleton
class MultiCameraManager @Inject constructor(
    private val cameraManager: CameraManager
) {
    /**
     * 物理摄像头信息
     */
    data class PhysicalCamera(
        val id: String,
        val focalLength: FocalLength,
        val focalLengthMm: Float,
        val sensorSize: Float,
        val megaPixels: Float,
        val hasRawSupport: Boolean,
        val maxIso: Int,
        val minFocusDistance: Float
    )

    private val physicalCameras = mutableListOf<PhysicalCamera>()
    private val focalLengthToCameraId = mutableMapOf<FocalLength, String>()

    /**
     * 初始化，扫描所有可用摄像头
     */
    fun initialize() {
        physicalCameras.clear()
        focalLengthToCameraId.clear()

        val cameraIds = cameraManager.cameraIdList
        
        for (cameraId in cameraIds) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // 只处理后置摄像头
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            // 获取焦距
            val focalLengths = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: continue
            
            if (focalLengths.isEmpty()) continue
            val focalLengthMm = focalLengths[0]

            // 获取传感器尺寸
            val sensorSize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            val sensorDiagonal = if (sensorSize != null) {
                kotlin.math.sqrt(
                    sensorSize.width * sensorSize.width + 
                    sensorSize.height * sensorSize.height
                )
            } else 0f

            // 获取像素数
            val pixelArraySize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val megaPixels = if (pixelArraySize != null) {
                (pixelArraySize.width * pixelArraySize.height) / 1_000_000f
            } else 0f

            // 检查 RAW 支持
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()
            val hasRawSupport = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )

            // 获取最大 ISO
            val isoRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val maxIso = isoRange?.upper ?: 6400

            // 获取最小对焦距离
            val minFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            // 根据焦距推断焦段
            val focalLength = guessFocalLengthCategory(focalLengthMm, sensorDiagonal)

            val camera = PhysicalCamera(
                id = cameraId,
                focalLength = focalLength,
                focalLengthMm = focalLengthMm,
                sensorSize = sensorDiagonal,
                megaPixels = megaPixels,
                hasRawSupport = hasRawSupport,
                maxIso = maxIso,
                minFocusDistance = minFocusDistance
            )

            physicalCameras.add(camera)
            
            // 建立焦段映射（如果该焦段还没有映射，或者新摄像头更好）
            if (!focalLengthToCameraId.containsKey(focalLength)) {
                focalLengthToCameraId[focalLength] = cameraId
            }
        }
    }

    /**
     * 获取所有物理摄像头
     */
    fun getPhysicalCameras(): List<PhysicalCamera> = physicalCameras.toList()

    /**
     * 获取可用焦段
     */
    fun getAvailableFocalLengths(): List<FocalLength> = focalLengthToCameraId.keys.toList()

    /**
     * 获取焦段对应的摄像头 ID
     * 处理焦段别名：WIDE=MAIN, TELEPHOTO_3X=TELEPHOTO, TELEPHOTO_6X=PERISCOPE
     */
    fun getCameraIdForFocalLength(focalLength: FocalLength): String? {
        // 先直接查找
        focalLengthToCameraId[focalLength]?.let { return it }
        
        // 处理别名
        val aliasedFocalLength = when (focalLength) {
            FocalLength.WIDE -> FocalLength.MAIN
            FocalLength.MAIN -> FocalLength.WIDE
            FocalLength.TELEPHOTO_3X -> FocalLength.TELEPHOTO
            FocalLength.TELEPHOTO -> FocalLength.TELEPHOTO_3X
            FocalLength.TELEPHOTO_6X -> FocalLength.PERISCOPE
            FocalLength.PERISCOPE -> FocalLength.TELEPHOTO_6X
            else -> null
        }
        
        return aliasedFocalLength?.let { focalLengthToCameraId[it] }
    }

    /**
     * 根据物理焦距推断焦段分类
     */
    private fun guessFocalLengthCategory(focalLengthMm: Float, sensorDiagonal: Float): FocalLength {
        // 计算等效焦距 (相对于全画幅 43.3mm 对角线)
        val cropFactor = if (sensorDiagonal > 0) 43.3f / sensorDiagonal else 1f
        val equivalent35mm = focalLengthMm * cropFactor

        return when {
            equivalent35mm < 20 -> FocalLength.ULTRA_WIDE   // 超广角
            equivalent35mm < 40 -> FocalLength.MAIN         // 主摄
            equivalent35mm < 100 -> FocalLength.TELEPHOTO   // 长焦
            else -> FocalLength.PERISCOPE                    // 潜望
        }
    }
}
