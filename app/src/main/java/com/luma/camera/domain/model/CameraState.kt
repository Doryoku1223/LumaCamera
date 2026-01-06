package com.luma.camera.domain.model

/**
 * 手动控制参数
 *
 * Pro 模式下的相机参数设置
 */
data class ManualParameters(
    // 曝光补偿 -3.0 ~ +3.0
    val exposureCompensation: Float = 0f,
    
    // ISO 感光度 50 ~ 6400
    val iso: Int? = null, // null 表示自动
    
    // 快门速度 (秒) 1/8000 ~ 30
    val shutterSpeed: Double? = null, // null 表示自动
    
    // 白平衡模式
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    
    // 手动白平衡色温 2000K ~ 10000K
    val whiteBalanceKelvin: Int = 5500,
    
    // 白平衡色调 -100 ~ +100
    val whiteBalanceTint: Int = 0,
    
    // 手动对焦距离 0.0 ~ 1.0 (0=最近, 1=无穷远)
    val focusDistance: Float? = null, // null 表示自动对焦
    
    // AE 锁定
    val isAeLocked: Boolean = false,
    
    // AF 锁定
    val isAfLocked: Boolean = false
) {
    companion object {
        // 曝光补偿范围
        const val EV_MIN = -3.0f
        const val EV_MAX = 3.0f
        const val EV_STEP = 0.1f
        
        // ISO 范围
        const val ISO_MIN = 50
        const val ISO_MAX = 6400
        
        // 快门速度范围 (秒)
        const val SHUTTER_MIN = 1.0 / 8000.0  // 1/8000s
        const val SHUTTER_MAX = 30.0           // 30s
        
        // 白平衡色温范围
        const val WB_KELVIN_MIN = 2000
        const val WB_KELVIN_MAX = 10000
        
        // 色调范围
        const val TINT_MIN = -100
        const val TINT_MAX = 100
    }
}

/**
 * 相机状态
 */
data class CameraState(
    // 当前模式
    val mode: CameraMode = CameraMode.AUTO,
    
    // 当前模式 (兼容别名)
    val currentMode: CameraMode = mode,
    
    // 当前焦段
    val focalLength: FocalLength = FocalLength.MAIN,
    
    // 当前焦段 (兼容别名)
    val currentFocalLength: FocalLength = focalLength,
    
    // 闪光灯模式
    val flashMode: FlashMode = FlashMode.OFF,
    
    // 画面比例
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    
    // Live Photo 开关
    val isLivePhotoEnabled: Boolean = false,
    
    // 手动参数
    val manualParameters: ManualParameters = ManualParameters(),
    
    // 当前滤镜 ID (null 表示原图)
    val currentLutId: String? = null,
    
    // 当前选中的 LUT 滤镜
    val selectedLut: LutFilter? = null,
    
    // 滤镜强度 0-100
    val lutIntensity: Int = 100,
    
    // 是否正在拍摄
    val isCapturing: Boolean = false,
    
    // 是否正在处理
    val isProcessing: Boolean = false,
    
    // 连拍模式
    val isBurstMode: Boolean = false,
    
    // 显示网格线
    val showGrid: Boolean = true,
    
    // 显示水平仪
    val showLevel: Boolean = false,
    
    // 显示直方图
    val showHistogram: Boolean = false,
    
    // 峰值对焦开关
    val focusPeakingEnabled: Boolean = false,
    
    // 峰值对焦颜色
    val focusPeakingColor: String = "gold",
    
    // 相机是否已就绪
    val isCameraReady: Boolean = false,
    
    // 直方图数据
    val histogramRed: FloatArray = FloatArray(256),
    val histogramGreen: FloatArray = FloatArray(256),
    val histogramBlue: FloatArray = FloatArray(256),
    val histogramLuminance: FloatArray = FloatArray(256),
    
    // 调色盘参数
    val colorPalette: ColorPalette = ColorPalette.DEFAULT,
    
    // 当前选中的预设 ID
    val selectedPresetId: String? = ColorPreset.PRESET_ORIGINAL.id,
    
    // 调色盘面板是否打开
    val isColorPalettePanelOpen: Boolean = false
)
