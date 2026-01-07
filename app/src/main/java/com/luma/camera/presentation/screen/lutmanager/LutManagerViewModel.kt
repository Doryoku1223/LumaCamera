package com.luma.camera.presentation.screen.lutmanager

import android.graphics.Bitmap
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

@HiltViewModel
class LutManagerViewModel @Inject constructor(
    private val lutManager: LutManager
) : ViewModel() {

    val lutFilters: StateFlow<List<LutFilter>> = lutManager.lutFilters

    private val _lutPreviews = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val lutPreviews: StateFlow<Map<String, Bitmap>> = _lutPreviews.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var pendingExportLut: LutFilter? by mutableStateOf(null)

    init {
        loadLutPreviews()
        viewModelScope.launch {
            lutFilters.collect {
                loadLutPreviews()
            }
        }
    }

    private fun loadLutPreviews() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val sampleBitmap = createSampleBitmap()
                val previews = mutableMapOf<String, Bitmap>()
                lutFilters.value.forEach { lut ->
                    try {
                        val preview = lutManager.getLutPreview(lut.id, sampleBitmap)
                        previews[lut.id] = preview
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

    private fun createSampleBitmap(): Bitmap {
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

    suspend fun importLut(uri: Uri) {
        _isLoading.value = true
        try {
            val result = lutManager.importLutFromUri(uri)
            if (result != null) {
                Timber.d("LUT imported: ${result.name}")
                loadLutPreviews()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to import LUT")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun deleteLut(lutId: String) {
        try {
            val success = lutManager.deleteLut(lutId)
            if (success) {
                _lutPreviews.value = _lutPreviews.value.toMutableMap().apply {
                    remove(lutId)
                }
                Timber.d("LUT deleted: $lutId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete LUT")
        }
    }

    suspend fun deleteLuts(lutIds: List<String>) {
        try {
            val deleted = lutManager.deleteLuts(lutIds)
            if (deleted > 0) {
                _lutPreviews.value = _lutPreviews.value.toMutableMap().apply {
                    lutIds.forEach { remove(it) }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete LUTs")
        }
    }

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

    suspend fun updateLutThumbnail(lutId: String, uri: Uri) {
        _isLoading.value = true
        try {
            val success = lutManager.updateLutThumbnail(lutId, uri)
            if (!success) {
                Timber.w("Failed to update LUT thumbnail: $lutId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update LUT thumbnail")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun updateLutThumbnailBitmap(lutId: String, bitmap: Bitmap) {
        _isLoading.value = true
        try {
            val success = lutManager.updateLutThumbnailBitmap(lutId, bitmap)
            if (!success) {
                Timber.w("Failed to update LUT thumbnail bitmap: $lutId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update LUT thumbnail bitmap")
        } finally {
            _isLoading.value = false
        }
    }

    fun reorderLuts(fromIndex: Int, toIndex: Int) {
        lutManager.reorderLuts(fromIndex, toIndex)
    }

    fun pinLut(lutId: String) {
        lutManager.pinLut(lutId)
    }

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
