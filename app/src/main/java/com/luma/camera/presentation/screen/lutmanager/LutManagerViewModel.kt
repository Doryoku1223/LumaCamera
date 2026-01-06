package com.luma.camera.presentation.screen.lutmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.lut.LutManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * LUT 管理页面 ViewModel
 */
@HiltViewModel
class LutManagerViewModel @Inject constructor(
    private val lutManager: LutManager
) : ViewModel() {
    
    // LUT 列表
    val lutFilters: StateFlow<List<LutFilter>> = lutManager.lutFilters
    
    // LUT 预览图缓存
    private val _lutPreviews = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val lutPreviews: StateFlow<Map<String, Bitmap>> = _lutPreviews.asStateFlow()
    
    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 待导出的 LUT（用于文件选择器回调）
    var pendingExportLut: LutFilter? by mutableStateOf(null)
    
    init {
        loadLutPreviews()
    }
    
    /**
     * 加载 LUT 预览图
     */
    private fun loadLutPreviews() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 创建示例图片用于生成预览
                val sampleBitmap = createSampleBitmap()
                
                val previews = mutableMapOf<String, Bitmap>()
                lutFilters.value.forEach { lut ->
                    try {
                        val preview = lutManager.getLutPreview(lut.id, sampleBitmap)
                        if (preview != null) {
                            previews[lut.id] = preview
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to generate preview for ${lut.name}")
                    }
                }
                _lutPreviews.value = previews
                
                sampleBitmap.recycle()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load LUT previews")
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 创建示例图片用于 LUT 预览
     */
    private fun createSampleBitmap(): Bitmap {
        // 创建一个渐变色的示例图
        val width = 128
        val height = 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x * 255 / width)
                val g = (y * 255 / height)
                val b = ((x + y) * 255 / (width + height))
                val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bitmap.setPixel(x, y, color)
            }
        }
        
        return bitmap
    }
    
    /**
     * 导入 LUT
     */
    suspend fun importLut(uri: Uri) {
        _isLoading.value = true
        try {
            val result = lutManager.importLutFromUri(uri)
            if (result != null) {
                Timber.d("LUT imported: ${result.name}")
                // 刷新预览
                loadLutPreviews()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to import LUT")
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 删除 LUT
     */
    suspend fun deleteLut(lutId: String) {
        try {
            val success = lutManager.deleteLut(lutId)
            if (success) {
                // 从预览缓存中移除
                _lutPreviews.value = _lutPreviews.value.toMutableMap().apply {
                    remove(lutId)
                }
                Timber.d("LUT deleted: $lutId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete LUT")
        }
    }
    
    /**
     * 重命名 LUT
     */
    suspend fun renameLut(lutId: String, newName: String) {
        try {
            val success = lutManager.renameLut(lutId, newName)
            if (success) {
                Timber.d("LUT renamed: $lutId -> $newName")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to rename LUT")
        }
    }
    
    /**
     * 重排序 LUT
     */
    fun reorderLuts(fromIndex: Int, toIndex: Int) {
        lutManager.reorderLuts(fromIndex, toIndex)
    }
    
    /**
     * 导出 LUT
     */
    suspend fun exportLut(lutId: String, destUri: Uri) {
        try {
            val success = lutManager.exportLut(lutId, destUri)
            if (success) {
                Timber.d("LUT exported: $lutId")
            }
            pendingExportLut = null
        } catch (e: Exception) {
            Timber.e(e, "Failed to export LUT")
        }
    }
}
