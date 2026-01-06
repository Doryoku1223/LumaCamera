package com.luma.camera.domain.model

/**
 * 相机设置
 *
 * 持久化到 DataStore
 */
data class CameraSettings(
    // 图像设置
    val outputFormat: OutputFormat = OutputFormat.JPEG,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    val lumaLogEnabled: Boolean = false,  // Luma Log 输出
    
    // 水印设置
    val watermarkEnabled: Boolean = false,
    val watermarkPosition: WatermarkPosition = WatermarkPosition.BOTTOM_CENTER,
    
    // 取景器设置
    val showGrid: Boolean = true,
    val gridType: GridType = GridType.RULE_OF_THIRDS,
    val showLevel: Boolean = false,
    val showHistogram: Boolean = false,
    
    // 对焦辅助
    val focusPeakingEnabled: Boolean = false,
    val focusPeakingColor: String = "gold",  // gold, red, green, blue, white
    
    // 实况照片
    val livePhotoEnabled: Boolean = false,
    val livePhotoAudioEnabled: Boolean = true,
    
    // 反馈设置
    val hapticEnabled: Boolean = true,
    val shutterSoundEnabled: Boolean = true,
    
    // 隐私设置
    val geotagEnabled: Boolean = true,
    
    // 存储路径 (null = 默认)
    val customStoragePath: String? = null,
    
    // 兼容旧设置
    val saveFlatImage: Boolean = false,
    val showEquivalentFocal: Boolean = true,
    val histogramEnabled: Boolean = false,
    val histogramPosition: HistogramPosition = HistogramPosition.TOP_RIGHT,
    val timerDuration: TimerDuration = TimerDuration.OFF,
    val levelEnabled: Boolean = false,
    val saveLocation: Boolean = true,
    val saveExifParams: Boolean = true,
    val saveExifDevice: Boolean = true,
    val saveExifFilter: Boolean = true
)
