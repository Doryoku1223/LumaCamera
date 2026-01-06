package com.luma.camera.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.luma.camera.domain.model.AspectRatio
import com.luma.camera.domain.model.CameraSettings
import com.luma.camera.domain.model.GridType
import com.luma.camera.domain.model.OutputFormat
import com.luma.camera.domain.model.WatermarkPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置仓库
 *
 * 使用 DataStore 持久化相机设置
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // 图像设置
        private val KEY_OUTPUT_FORMAT = stringPreferencesKey("output_format")
        private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val KEY_LUMA_LOG_ENABLED = booleanPreferencesKey("luma_log_enabled")
        
        // 取景器设置
        private val KEY_GRID_ENABLED = booleanPreferencesKey("grid_enabled")
        private val KEY_GRID_TYPE = stringPreferencesKey("grid_type")
        private val KEY_LEVEL_ENABLED = booleanPreferencesKey("level_enabled")
        private val KEY_HISTOGRAM_ENABLED = booleanPreferencesKey("histogram_enabled")
        
        // 对焦辅助
        private val KEY_FOCUS_PEAKING_ENABLED = booleanPreferencesKey("focus_peaking_enabled")
        private val KEY_FOCUS_PEAKING_COLOR = stringPreferencesKey("focus_peaking_color")
        
        // 实况照片
        private val KEY_LIVE_PHOTO_ENABLED = booleanPreferencesKey("live_photo_enabled")
        private val KEY_LIVE_PHOTO_AUDIO_ENABLED = booleanPreferencesKey("live_photo_audio_enabled")
        
        // 水印设置
        private val KEY_WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        private val KEY_WATERMARK_POSITION = stringPreferencesKey("watermark_position")
        
        // 反馈
        private val KEY_HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        private val KEY_SHUTTER_SOUND_ENABLED = booleanPreferencesKey("shutter_sound_enabled")
        
        // 隐私
        private val KEY_GEOTAG_ENABLED = booleanPreferencesKey("geotag_enabled")
        
        // LUT 设置
        private val KEY_LAST_LUT_ID = stringPreferencesKey("last_lut_id")
        private val KEY_LUT_INTENSITY = floatPreferencesKey("lut_intensity")
        
        // 默认值
        const val DEFAULT_OUTPUT_FORMAT = "JPEG"
        const val DEFAULT_ASPECT_RATIO = "RATIO_16_9"
        const val DEFAULT_GRID_TYPE = "RULE_OF_THIRDS"
        const val DEFAULT_FOCUS_PEAKING_COLOR = "gold"
        const val DEFAULT_WATERMARK_POSITION = "BOTTOM_CENTER"
    }

    /**
     * 聚合的设置 Flow
     */
    val settings: Flow<CameraSettings> = dataStore.data.map { prefs ->
        CameraSettings(
            outputFormat = prefs[KEY_OUTPUT_FORMAT]?.let { 
                runCatching { OutputFormat.valueOf(it) }.getOrNull() 
            } ?: OutputFormat.JPEG,
            aspectRatio = prefs[KEY_ASPECT_RATIO]?.let { 
                runCatching { AspectRatio.valueOf(it) }.getOrNull() 
            } ?: AspectRatio.RATIO_16_9,
            lumaLogEnabled = prefs[KEY_LUMA_LOG_ENABLED] ?: false,
            watermarkEnabled = prefs[KEY_WATERMARK_ENABLED] ?: false,
            watermarkPosition = prefs[KEY_WATERMARK_POSITION]?.let {
                runCatching { WatermarkPosition.valueOf(it) }.getOrNull()
            } ?: WatermarkPosition.BOTTOM_CENTER,
            showGrid = prefs[KEY_GRID_ENABLED] ?: true,
            gridType = prefs[KEY_GRID_TYPE]?.let { 
                runCatching { GridType.valueOf(it) }.getOrNull() 
            } ?: GridType.RULE_OF_THIRDS,
            showLevel = prefs[KEY_LEVEL_ENABLED] ?: false,
            showHistogram = prefs[KEY_HISTOGRAM_ENABLED] ?: false,
            focusPeakingEnabled = prefs[KEY_FOCUS_PEAKING_ENABLED] ?: false,
            focusPeakingColor = prefs[KEY_FOCUS_PEAKING_COLOR] ?: DEFAULT_FOCUS_PEAKING_COLOR,
            livePhotoEnabled = prefs[KEY_LIVE_PHOTO_ENABLED] ?: false,
            livePhotoAudioEnabled = prefs[KEY_LIVE_PHOTO_AUDIO_ENABLED] ?: true,
            hapticEnabled = prefs[KEY_HAPTIC_ENABLED] ?: true,
            shutterSoundEnabled = prefs[KEY_SHUTTER_SOUND_ENABLED] ?: true,
            geotagEnabled = prefs[KEY_GEOTAG_ENABLED] ?: true
        )
    }

    // ==================== 图像设置 ====================
    
    fun getOutputFormat(): Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_OUTPUT_FORMAT] ?: DEFAULT_OUTPUT_FORMAT
    }
    
    suspend fun setOutputFormat(format: String) {
        dataStore.edit { prefs -> prefs[KEY_OUTPUT_FORMAT] = format }
    }
    
    fun getAspectRatio(): Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ASPECT_RATIO] ?: DEFAULT_ASPECT_RATIO
    }
    
    suspend fun setAspectRatio(ratio: String) {
        dataStore.edit { prefs -> prefs[KEY_ASPECT_RATIO] = ratio }
    }
    
    fun isLumaLogEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LUMA_LOG_ENABLED] ?: false
    }
    
    suspend fun setLumaLogEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_LUMA_LOG_ENABLED] = enabled }
    }
    
    // ==================== 取景器设置 ====================
    
    fun isGridEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_GRID_ENABLED] ?: true
    }
    
    suspend fun setGridEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_GRID_ENABLED] = enabled }
    }
    
    fun getGridType(): Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_GRID_TYPE] ?: DEFAULT_GRID_TYPE
    }
    
    suspend fun setGridType(type: String) {
        dataStore.edit { prefs -> prefs[KEY_GRID_TYPE] = type }
    }
    
    fun isLevelEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LEVEL_ENABLED] ?: false
    }
    
    suspend fun setLevelEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_LEVEL_ENABLED] = enabled }
    }
    
    fun isHistogramEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HISTOGRAM_ENABLED] ?: false
    }
    
    suspend fun setHistogramEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HISTOGRAM_ENABLED] = enabled }
    }
    
    // ==================== 对焦辅助 ====================
    
    fun isFocusPeakingEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FOCUS_PEAKING_ENABLED] ?: false
    }
    
    suspend fun setFocusPeakingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_FOCUS_PEAKING_ENABLED] = enabled }
    }
    
    fun getFocusPeakingColor(): Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_FOCUS_PEAKING_COLOR] ?: DEFAULT_FOCUS_PEAKING_COLOR
    }
    
    suspend fun setFocusPeakingColor(color: String) {
        dataStore.edit { prefs -> prefs[KEY_FOCUS_PEAKING_COLOR] = color }
    }
    
    // ==================== 实况照片 ====================
    
    fun isLivePhotoEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LIVE_PHOTO_ENABLED] ?: false
    }
    
    suspend fun setLivePhotoEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_LIVE_PHOTO_ENABLED] = enabled }
    }
    
    fun isLivePhotoAudioEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LIVE_PHOTO_AUDIO_ENABLED] ?: true
    }
    
    suspend fun setLivePhotoAudioEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_LIVE_PHOTO_AUDIO_ENABLED] = enabled }
    }
    
    // ==================== 反馈设置 ====================
    
    fun isHapticEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HAPTIC_ENABLED] ?: true
    }
    
    suspend fun setHapticEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HAPTIC_ENABLED] = enabled }
    }
    
    fun isShutterSoundEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHUTTER_SOUND_ENABLED] ?: true
    }
    
    suspend fun setShutterSoundEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SHUTTER_SOUND_ENABLED] = enabled }
    }
    
    // ==================== 隐私设置 ====================
    
    fun isGeotagEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_GEOTAG_ENABLED] ?: true
    }
    
    suspend fun setGeotagEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_GEOTAG_ENABLED] = enabled }
    }
    
    // ==================== 水印设置 ====================
    
    fun isWatermarkEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_WATERMARK_ENABLED] ?: false
    }
    
    suspend fun setWatermarkEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_WATERMARK_ENABLED] = enabled }
    }
    
    fun getWatermarkPosition(): Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_WATERMARK_POSITION] ?: DEFAULT_WATERMARK_POSITION
    }
    
    suspend fun setWatermarkPosition(position: String) {
        dataStore.edit { prefs -> prefs[KEY_WATERMARK_POSITION] = position }
    }
    
    // ==================== LUT 设置 ====================
    
    fun getLastLutId(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_LUT_ID]
    }
    
    suspend fun setLastLutId(lutId: String?) {
        dataStore.edit { prefs ->
            if (lutId != null) {
                prefs[KEY_LAST_LUT_ID] = lutId
            } else {
                prefs.remove(KEY_LAST_LUT_ID)
            }
        }
    }
    
    fun getLutIntensity(): Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_LUT_INTENSITY] ?: 1.0f
    }
    
    suspend fun setLutIntensity(intensity: Float) {
        dataStore.edit { prefs -> prefs[KEY_LUT_INTENSITY] = intensity }
    }
}
