package com.luma.camera.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luma.camera.data.repository.SettingsRepository
import com.luma.camera.domain.model.*
import com.luma.camera.domain.model.WatermarkPosition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置 ViewModel
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    // 设置状态
    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()
    
    init {
        loadSettings()
    }
    
    /**
     * 加载设置
     */
    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsRepository.getOutputFormat(),
                settingsRepository.getAspectRatio(),
                settingsRepository.getGridType(),
                settingsRepository.isGridEnabled(),
                settingsRepository.isLevelEnabled(),
                settingsRepository.isHistogramEnabled(),
                settingsRepository.isFocusPeakingEnabled(),
                settingsRepository.getFocusPeakingColor(),
                settingsRepository.isLumaLogEnabled()
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                CameraSettings(
                    outputFormat = OutputFormat.entries.find { it.name == values[0] } ?: OutputFormat.JPEG,
                    aspectRatio = AspectRatio.entries.find { it.name == values[1] } ?: AspectRatio.RATIO_16_9,
                    gridType = GridType.entries.find { it.name == values[2] } ?: GridType.RULE_OF_THIRDS,
                    showGrid = values[3] as Boolean,
                    showLevel = values[4] as Boolean,
                    showHistogram = values[5] as Boolean,
                    focusPeakingEnabled = values[6] as Boolean,
                    focusPeakingColor = values[7] as String,
                    lumaLogEnabled = values[8] as Boolean
                )
            }.collect { settings ->
                _settings.value = settings
            }
        }
        
        // 加载其他设置
        viewModelScope.launch {
            settingsRepository.isLivePhotoEnabled().collect { enabled ->
                _settings.update { it.copy(livePhotoEnabled = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.isLivePhotoAudioEnabled().collect { enabled ->
                _settings.update { it.copy(livePhotoAudioEnabled = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.isHapticEnabled().collect { enabled ->
                _settings.update { it.copy(hapticEnabled = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.isShutterSoundEnabled().collect { enabled ->
                _settings.update { it.copy(shutterSoundEnabled = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.isGeotagEnabled().collect { enabled ->
                _settings.update { it.copy(geotagEnabled = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.isWatermarkEnabled().collect { enabled ->
                _settings.update { it.copy(watermarkEnabled = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getWatermarkPosition().collect { position ->
                val watermarkPosition = runCatching { 
                    WatermarkPosition.valueOf(position) 
                }.getOrDefault(WatermarkPosition.BOTTOM_CENTER)
                _settings.update { it.copy(watermarkPosition = watermarkPosition) }
            }
        }
    }
    
    /**
     * 更新设置
     */
    fun updateSettings(newSettings: CameraSettings) {
        viewModelScope.launch {
            // 比较并保存变化的设置
            val current = _settings.value
            
            if (newSettings.outputFormat != current.outputFormat) {
                settingsRepository.setOutputFormat(newSettings.outputFormat.name)
            }
            
            if (newSettings.aspectRatio != current.aspectRatio) {
                settingsRepository.setAspectRatio(newSettings.aspectRatio.name)
            }
            
            if (newSettings.gridType != current.gridType) {
                settingsRepository.setGridType(newSettings.gridType.name)
            }
            
            if (newSettings.showGrid != current.showGrid) {
                settingsRepository.setGridEnabled(newSettings.showGrid)
            }
            
            if (newSettings.showLevel != current.showLevel) {
                settingsRepository.setLevelEnabled(newSettings.showLevel)
            }
            
            if (newSettings.showHistogram != current.showHistogram) {
                settingsRepository.setHistogramEnabled(newSettings.showHistogram)
            }
            
            if (newSettings.focusPeakingEnabled != current.focusPeakingEnabled) {
                settingsRepository.setFocusPeakingEnabled(newSettings.focusPeakingEnabled)
            }
            
            if (newSettings.focusPeakingColor != current.focusPeakingColor) {
                settingsRepository.setFocusPeakingColor(newSettings.focusPeakingColor)
            }
            
            if (newSettings.lumaLogEnabled != current.lumaLogEnabled) {
                settingsRepository.setLumaLogEnabled(newSettings.lumaLogEnabled)
            }
            
            if (newSettings.livePhotoEnabled != current.livePhotoEnabled) {
                settingsRepository.setLivePhotoEnabled(newSettings.livePhotoEnabled)
            }
            
            if (newSettings.livePhotoAudioEnabled != current.livePhotoAudioEnabled) {
                settingsRepository.setLivePhotoAudioEnabled(newSettings.livePhotoAudioEnabled)
            }
            
            if (newSettings.hapticEnabled != current.hapticEnabled) {
                settingsRepository.setHapticEnabled(newSettings.hapticEnabled)
            }
            
            if (newSettings.shutterSoundEnabled != current.shutterSoundEnabled) {
                settingsRepository.setShutterSoundEnabled(newSettings.shutterSoundEnabled)
            }
            
            if (newSettings.geotagEnabled != current.geotagEnabled) {
                settingsRepository.setGeotagEnabled(newSettings.geotagEnabled)
            }
            
            if (newSettings.watermarkEnabled != current.watermarkEnabled) {
                settingsRepository.setWatermarkEnabled(newSettings.watermarkEnabled)
            }
            
            if (newSettings.watermarkPosition != current.watermarkPosition) {
                settingsRepository.setWatermarkPosition(newSettings.watermarkPosition.name)
            }
            
            _settings.value = newSettings
        }
    }
}
