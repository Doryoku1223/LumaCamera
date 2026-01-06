package com.luma.camera.data.repository

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.luma.camera.domain.model.ColorPalette
import com.luma.camera.domain.model.ColorPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 调色盘预设持久化仓库
 * 
 * 使用 SharedPreferences 存储用户自定义预设
 */
@Singleton
class ColorPaletteRepository @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val KEY_CUSTOM_PRESETS = "custom_presets"
    }
    
    /**
     * 获取所有自定义预设
     */
    suspend fun getCustomPresets(): List<ColorPreset> = withContext(Dispatchers.IO) {
        try {
            val json = sharedPreferences.getString(KEY_CUSTOM_PRESETS, null)
            if (json.isNullOrEmpty()) {
                return@withContext emptyList()
            }
            
            val jsonArray = JSONArray(json)
            val presets = mutableListOf<ColorPreset>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                presets.add(parsePreset(obj))
            }
            
            presets
        } catch (e: Exception) {
            Timber.e(e, "Failed to load custom presets")
            emptyList()
        }
    }
    
    /**
     * 保存自定义预设
     */
    suspend fun saveCustomPreset(preset: ColorPreset) = withContext(Dispatchers.IO) {
        try {
            val existingPresets = getCustomPresets().toMutableList()
            
            // 检查是否已存在同 ID 的预设
            val existingIndex = existingPresets.indexOfFirst { it.id == preset.id }
            if (existingIndex >= 0) {
                existingPresets[existingIndex] = preset
            } else {
                existingPresets.add(preset)
            }
            
            savePresetsToStorage(existingPresets)
            Timber.d("Custom preset saved: ${preset.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save custom preset")
        }
    }
    
    /**
     * 删除自定义预设
     */
    suspend fun deleteCustomPreset(presetId: String) = withContext(Dispatchers.IO) {
        try {
            val existingPresets = getCustomPresets().filter { it.id != presetId }
            savePresetsToStorage(existingPresets)
            Timber.d("Custom preset deleted: $presetId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete custom preset")
        }
    }
    
    private fun savePresetsToStorage(presets: List<ColorPreset>) {
        val jsonArray = JSONArray()
        presets.forEach { preset ->
            jsonArray.put(presetToJson(preset))
        }
        sharedPreferences.edit()
            .putString(KEY_CUSTOM_PRESETS, jsonArray.toString())
            .apply()
    }
    
    private fun presetToJson(preset: ColorPreset): JSONObject {
        return JSONObject().apply {
            put("id", preset.id)
            put("name", preset.name)
            // 新版 API：使用 UI 值
            put("tempUI", preset.palette.tempUI.toDouble())
            put("expUI", preset.palette.expUI.toDouble())
            put("satUI", preset.palette.satUI.toDouble())
            // 旧版 API 兼容字段（用于向前兼容）
            put("temperatureKelvin", preset.palette.temperatureKelvin.toDouble())
            put("saturation", preset.palette.saturation.toDouble())
            put("tone", preset.palette.tone.toDouble())
            put("isCustom", preset.isCustom)
            put("previewColor", preset.previewColor.toArgb())
        }
    }
    
    private fun parsePreset(obj: JSONObject): ColorPreset {
        // 优先使用新版 API
        val palette = if (obj.has("tempUI")) {
            ColorPalette(
                tempUI = obj.getDouble("tempUI").toFloat(),
                expUI = obj.getDouble("expUI").toFloat(),
                satUI = obj.getDouble("satUI").toFloat()
            )
        } else {
            // 回退到旧版 API 并转换
            val tempKelvin = obj.getDouble("temperatureKelvin").toFloat()
            val saturation = obj.getDouble("saturation").toFloat()
            val tone = obj.getDouble("tone").toFloat()
            ColorPalette.fromLegacy(tempKelvin, saturation, tone)
        }
        
        return ColorPreset(
            id = obj.getString("id"),
            name = obj.getString("name"),
            palette = palette,
            isCustom = obj.optBoolean("isCustom", true),
            previewColor = Color(obj.optInt("previewColor", 0xFFD4A574.toInt()))
        )
    }
}
