package com.luma.camera.presentation.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luma.camera.camera.CameraController
import com.luma.camera.camera.CameraSessionManager
import com.luma.camera.camera.MultiCameraManager
import com.luma.camera.camera.SensorInfoManager
import com.luma.camera.data.repository.SettingsRepository
import com.luma.camera.data.repository.ColorPaletteRepository
import com.luma.camera.domain.model.AspectRatio
import com.luma.camera.domain.model.CameraMode
import com.luma.camera.domain.model.CameraSettings
import com.luma.camera.domain.model.CameraState
import com.luma.camera.domain.model.ColorPalette
import com.luma.camera.domain.model.ColorPreset
import com.luma.camera.domain.model.FlashMode
import com.luma.camera.domain.model.FocalLength
import com.luma.camera.domain.model.ManualParameters
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.domain.model.OutputFormat
import com.luma.camera.domain.model.WatermarkPosition
import com.luma.camera.imaging.HistogramAnalyzer
import com.luma.camera.imaging.WatermarkRenderer
import com.luma.camera.imaging.FlatProfileGenerator
import com.luma.camera.imaging.PhotoProcessingQueue
import com.luma.camera.imaging.ProcessedPhotoResult
import com.luma.camera.livephoto.LivePhotoManager
import com.luma.camera.lut.GpuLutRenderer
import com.luma.camera.lut.LutManager
import com.luma.camera.render.GLPreviewRenderer
import com.luma.camera.storage.MediaStoreHelper
import com.luma.camera.storage.DngWriter
import com.luma.camera.utils.HapticFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.cbrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * 相机主界面 ViewModel
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraController: CameraController,
    private val cameraSessionManager: CameraSessionManager,
    private val multiCameraManager: MultiCameraManager,
    private val settingsRepository: SettingsRepository,
    private val colorPaletteRepository: ColorPaletteRepository,
    private val lutManager: LutManager,
    private val gpuLutRenderer: GpuLutRenderer,
    private val mediaStoreHelper: MediaStoreHelper,
    private val hapticFeedback: HapticFeedback,
    private val sensorInfoManager: SensorInfoManager,
    private val histogramAnalyzer: HistogramAnalyzer,
    private val livePhotoManager: LivePhotoManager,
    private val flatProfileGenerator: FlatProfileGenerator,
    private val dngWriter: com.luma.camera.storage.DngWriter,
    private val watermarkRenderer: WatermarkRenderer,
    private val photoProcessingQueue: PhotoProcessingQueue,
    val glPreviewRenderer: GLPreviewRenderer
) : ViewModel() {

    // 相机状态
    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    // 水平仪角度（从传感器获取）
    val levelAngle: StateFlow<Float> = sensorInfoManager.stabilityState
        .map { it.roll }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    // 设置
    val settings: StateFlow<CameraSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CameraSettings()
        )

    // 可用焦段
    private val _availableFocalLengths = MutableStateFlow<List<FocalLength>>(emptyList())
    val availableFocalLengths: StateFlow<List<FocalLength>> = _availableFocalLengths.asStateFlow()

    // LUT 列表
    val lutFilters = lutManager.lutFilters

    // 当前选中的 LUT
    val currentLut = lutManager.currentLut

    // 滤镜面板是否打开
    private val _isFilterPanelOpen = MutableStateFlow(false)
    val isFilterPanelOpen: StateFlow<Boolean> = _isFilterPanelOpen.asStateFlow()
    
    // 调色盘预设列表
    private val _colorPresets = MutableStateFlow<List<ColorPreset>>(ColorPreset.defaultPresets())
    val colorPresets: StateFlow<List<ColorPreset>> = _colorPresets.asStateFlow()
    
    // 标志：是否需要在 Surface 就绪后自动恢复相机
    private var shouldResumeCamera = false

    init {
        initializeCamera()
        initializeSensors()
        initializeColorPalette()
        initializeHistogramAnalysis()
        setupRendererReinitCallback()
    }
    
    /**
     * 设置渲染器重新初始化回调
     * 当渲染器需要重新初始化时（例如 EGL Surface 失效），会自动重新连接相机
     */
    private fun setupRendererReinitCallback() {
        glPreviewRenderer.setReinitializeCallback { cameraSurface ->
            Timber.d("Renderer reinitialized, reopening camera with new surface")
            viewModelScope.launch {
                try {
                    glPreviewRenderer.startRendering()
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val currentFocalLength = _cameraState.value.currentFocalLength
                        val result = cameraSessionManager.openCamera(
                            focalLength = currentFocalLength,
                            previewSurface = cameraSurface
                        )
                        result.onSuccess {
                            Timber.d("Camera reopened after renderer reinitialization")
                            _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                            
                            // 如果实况功能已启用，恢复缓冲录制
                            if (_cameraState.value.isLivePhotoEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLivePhotoBuffering()
                            }
                        }.onFailure { error ->
                            Timber.e(error, "Failed to reopen camera after renderer reinitialization")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during renderer reinitialization callback")
                }
            }
        }
    }

    private fun initializeCamera() {
        viewModelScope.launch {
            // 初始化相机控制器
            cameraController.initialize()

            // 初始化多摄管理
            multiCameraManager.initialize()
            _availableFocalLengths.value = multiCameraManager.getAvailableFocalLengths()

            // 初始化 LUT 管理器
            lutManager.initialize()
        }
    }
    
    private fun initializeSensors() {
        // 初始化传感器（用于水平仪）
        sensorInfoManager.initialize()
    }
    
    /**
     * 初始化调色盘
     */
    private fun initializeColorPalette() {
        viewModelScope.launch {
            // 从持久化加载自定义预设
            val customPresets = colorPaletteRepository.getCustomPresets()
            _colorPresets.value = ColorPreset.defaultPresets() + customPresets
        }
    }
    
    /**
     * 初始化直方图分析
     */
    private fun initializeHistogramAnalysis() {
        // 设置帧数据回调用于直方图分析
        glPreviewRenderer.setFrameDataCallback { frameData, width, height ->
            viewModelScope.launch {
                analyzeFrameForHistogram(frameData, width, height)
            }
        }
    }
    
    /**
     * 分析帧数据用于直方图
     */
    private suspend fun analyzeFrameForHistogram(frameData: ByteArray, width: Int, height: Int) {
        // 仅在直方图开启时分析
        if (!settings.value.showHistogram) return
        
        try {
            // 计算直方图数据
            val red = FloatArray(256)
            val green = FloatArray(256)
            val blue = FloatArray(256)
            val luminance = FloatArray(256)
            
            var maxValue = 0f
            
            // 解析 RGBA 数据
            for (i in 0 until frameData.size step 16) {  // 采样以提高速度
                if (i + 3 >= frameData.size) break
                
                val r = frameData[i].toInt() and 0xFF
                val g = frameData[i + 1].toInt() and 0xFF
                val b = frameData[i + 2].toInt() and 0xFF
                
                red[r]++
                green[g]++
                blue[b]++
                
                // BT.709 亮度
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                luminance[luma]++
            }
            
            // 归一化
            maxValue = maxOf(
                red.maxOrNull() ?: 0f,
                green.maxOrNull() ?: 0f,
                blue.maxOrNull() ?: 0f,
                luminance.maxOrNull() ?: 0f
            )
            
            if (maxValue > 0) {
                for (i in 0 until 256) {
                    red[i] = red[i] / maxValue
                    green[i] = green[i] / maxValue
                    blue[i] = blue[i] / maxValue
                    luminance[i] = luminance[i] / maxValue
                }
            }
            
            // 更新状态
            _cameraState.value = _cameraState.value.copy(
                histogramRed = red,
                histogramGreen = green,
                histogramBlue = blue,
                histogramLuminance = luminance
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to analyze histogram")
        }
    }

    // ==================== 相机模式 ====================

    fun setMode(mode: CameraMode) {
        hapticFeedback.click()
        _cameraState.value = _cameraState.value.copy(mode = mode, currentMode = mode)
    }

    fun switchMode(mode: CameraMode) {
        setMode(mode)
    }

    fun toggleMode() {
        val newMode = when (_cameraState.value.mode) {
            CameraMode.AUTO -> CameraMode.PRO
            CameraMode.PRO -> CameraMode.AUTO
            CameraMode.PHOTO -> CameraMode.PRO
            CameraMode.VIDEO -> CameraMode.PHOTO
        }
        setMode(newMode)
    }

    // ==================== 焦段切换 ====================

    fun setFocalLength(focalLength: FocalLength) {
        // 如果是相同焦段，不需要切换
        if (focalLength == _cameraState.value.currentFocalLength) {
            return
        }
        
        hapticFeedback.click()
        _cameraState.value = _cameraState.value.copy(
            focalLength = focalLength,
            currentFocalLength = focalLength
        )

        viewModelScope.launch {
            try {
                Timber.d("Switching focal length to: $focalLength")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val result = cameraSessionManager.switchFocalLength(focalLength)
                    result.onSuccess {
                        Timber.d("Successfully switched to focal length: $focalLength")
                    }.onFailure { error ->
                        Timber.e(error, "Failed to switch focal length")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error switching focal length")
            }
        }
    }

    fun switchFocalLength(focalLength: FocalLength) {
        setFocalLength(focalLength)
    }

    // ==================== 预览 Surface ====================

    fun onPreviewSurfaceReady(surface: Surface) {
        viewModelScope.launch {
            try {
                Timber.d("Preview surface ready, opening camera...")
                savedPreviewSurface = surface  // 保存 Surface 用于后续恢复
                cameraController.setPreviewSurface(surface)
                
                // 使用 CameraSessionManager 打开相机并启动预览
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val currentFocalLength = _cameraState.value.currentFocalLength
                    val result = cameraSessionManager.openCamera(
                        focalLength = currentFocalLength,
                        previewSurface = surface
                    )
                    
                    result.onSuccess {
                        Timber.d("Camera opened successfully with focal length: $currentFocalLength")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                    }.onFailure { error ->
                        Timber.e(error, "Failed to open camera")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = false)
                    }
                } else {
                    Timber.w("Android version below P, using legacy camera opening")
                    // Fallback for older Android versions if needed
                }
            } catch (e: Exception) {
                Timber.e(e, "Error opening camera")
            }
        }
    }
    
    /**
     * GL 预览 Surface 准备就绪（相机应输出到此 Surface）
     * 
     * 当使用 GLPreviewRenderer 时，相机输出到 GL 渲染器的 SurfaceTexture，
     * 然后渲染器再输出到屏幕。这样可以实现实时 LUT 预览。
     */
    fun onGLPreviewSurfaceReady(surface: Surface) {
        viewModelScope.launch {
            try {
                Timber.d("GL Preview surface ready, opening camera to GL renderer...")
                savedPreviewSurface = surface  // 保存 Surface 用于后续恢复
                cameraController.setPreviewSurface(surface)
                
                // 设置峰值对焦状态
                glPreviewRenderer.setFocusPeakingEnabled(settings.value.focusPeakingEnabled)
                
                // 使用 CameraSessionManager 打开相机并启动预览
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val currentFocalLength = _cameraState.value.currentFocalLength
                    val result = cameraSessionManager.openCamera(
                        focalLength = currentFocalLength,
                        previewSurface = surface
                    )
                    
                    result.onSuccess {
                        Timber.d("Camera opened to GL renderer with focal length: $currentFocalLength")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                        
                        // 如果有选中的 LUT，更新预览
                        _cameraState.value.selectedLut?.let { lut ->
                            updatePreviewLut(lut)
                        }
                    }.onFailure { error ->
                        Timber.e(error, "Failed to open camera")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error opening camera to GL renderer")
            }
        }
    }

    // ==================== 闪光灯 ====================

    fun setFlashMode(mode: FlashMode) {
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(flashMode = mode)
        
        // 应用闪光灯设置到相机会话
        cameraSessionManager.setFlashMode(mode)
        Timber.d("Flash mode set to: $mode")
    }

    fun cycleFlashMode() {
        val modes = FlashMode.entries
        val currentIndex = modes.indexOf(_cameraState.value.flashMode)
        val nextIndex = (currentIndex + 1) % modes.size
        setFlashMode(modes[nextIndex])
    }

    // ==================== 画面比例 ====================

    fun setAspectRatio(ratio: AspectRatio) {
        // 如果是相同比例，不需要切换
        if (ratio == _cameraState.value.aspectRatio) {
            return
        }
        
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(aspectRatio = ratio)
        Timber.d("Aspect ratio changed to: $ratio")
        
        // 注意：画面比例的改变会通过 CameraViewfinder 的 aspectRatio 参数自动更新 UI
        // 相机预览本身不需要重新配置，只需要改变取景器的显示裁切区域
    }

    // ==================== Live Photo ====================

    fun toggleLivePhoto() {
        hapticFeedback.click()
        val current = _cameraState.value.isLivePhotoEnabled
        val newState = !current
        _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = newState)
        
        // 在支持的 Android 版本上启动/停止缓冲录制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (newState) {
                // 启动实况照片缓冲录制
                startLivePhotoBuffering()
            } else {
                // 停止缓冲录制
                livePhotoManager.stopBuffering()
            }
        }
    }
    
    /**
     * 启动实况照片缓冲录制
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startLivePhotoBuffering() {
        viewModelScope.launch {
            try {
                Timber.d("Starting live photo buffering...")
                val inputSurface = livePhotoManager.startBuffering()
                if (inputSurface != null) {
                    Timber.d("Live photo buffering started successfully")
                } else {
                    Timber.w("Failed to start live photo buffering - inputSurface is null")
                    // 如果启动失败，禁用实况功能
                    _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting live photo buffering")
                _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = false)
            }
        }
    }

    // ==================== 滤镜 ====================

    fun selectLut(lutId: String?) {
        hapticFeedback.tick()
        lutManager.selectLut(lutId)
        
        // 如果 lutId 不为 null，尝试从 lutFilters 中获取完整的 LutFilter 对象
        val lut = if (lutId != null) {
            lutManager.lutFilters.value.find { it.id == lutId }
        } else null
        
        _cameraState.value = _cameraState.value.copy(
            currentLutId = lutId,
            selectedLut = lut
        )
        
        // 更新 GL 预览渲染器的 LUT
        updatePreviewLut(lut)
    }

    fun selectLut(lut: LutFilter?) {
        hapticFeedback.tick()
        lutManager.selectLut(lut?.id)
        _cameraState.value = _cameraState.value.copy(
            currentLutId = lut?.id,
            selectedLut = lut
        )
        
        // 更新 GL 预览渲染器的 LUT
        updatePreviewLut(lut)
    }
    
    /**
     * 更新预览 LUT
     */
    private fun updatePreviewLut(lut: LutFilter?) {
        if (lut == null) {
            // 清除 LUT
            glPreviewRenderer.setLutData(null, 0, null)
        } else {
            // 获取 LUT 数据，在 GL 线程上创建纹理
            val lutData = lutManager.getLutData(lut.id)
            if (lutData != null) {
                glPreviewRenderer.setLutData(lut.id, lutData.size, lutData.data)
                Timber.d("Preview LUT updated with data: ${lut.name}, size=${lutData.size}")
            } else {
                // LUT 数据尚未加载，尝试加载
                viewModelScope.launch {
                    lutManager.ensureLutLoaded(lut.id)
                    val loadedData = lutManager.getLutData(lut.id)
                    if (loadedData != null) {
                        glPreviewRenderer.setLutData(lut.id, loadedData.size, loadedData.data)
                        Timber.d("Preview LUT loaded and updated: ${lut.name}, size=${loadedData.size}")
                    } else {
                        Timber.w("Failed to load LUT data: ${lut.name}")
                    }
                }
            }
        }
    }

    fun setLutIntensity(intensity: Int) {
        val clampedIntensity = intensity.coerceIn(0, 100)
        _cameraState.value = _cameraState.value.copy(lutIntensity = clampedIntensity)
        // 更新预览渲染器的 LUT 强度 (0-1)
        glPreviewRenderer.setLutIntensity(clampedIntensity / 100f)
    }

    fun setLutIntensity(intensity: Float) {
        // 假设输入是 0-1 范围的 Float，转换为 0-100 的 Int
        val intIntensity = (intensity * 100).toInt().coerceIn(0, 100)
        _cameraState.value = _cameraState.value.copy(lutIntensity = intIntensity)
        // 更新预览渲染器的 LUT 强度 (0-1)
        glPreviewRenderer.setLutIntensity(intIntensity / 100f)
    }

    fun importLut(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                Timber.d("Importing LUT from: $uri")
                val importedLut = lutManager.importLutFromUri(uri)
                if (importedLut != null) {
                    Timber.d("LUT imported successfully: ${importedLut.name}")
                    // 自动选中导入的 LUT
                    selectLut(importedLut)
                    hapticFeedback.success()
                } else {
                    Timber.e("Failed to import LUT")
                    hapticFeedback.error()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error importing LUT")
                hapticFeedback.error()
            }
        }
    }

    fun openFilterPanel() {
        _isFilterPanelOpen.value = true
    }

    fun closeFilterPanel() {
        _isFilterPanelOpen.value = false
    }

    // ==================== 调色盘 ====================

    /**
     * 打开调色盘面板
     */
    fun openColorPalettePanel() {
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(isColorPalettePanelOpen = true)
    }

    /**
     * 关闭调色盘面板
     */
    fun closeColorPalettePanel() {
        _cameraState.value = _cameraState.value.copy(isColorPalettePanelOpen = false)
    }

    /**
     * 更新调色参数
     */
    fun updateColorPalette(palette: ColorPalette) {
        _cameraState.value = _cameraState.value.copy(
            colorPalette = palette,
            selectedPresetId = null // 用户手动调整后取消预设选择
        )
        
        // 实时更新 GL 渲染器
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = palette.temperatureKelvin,
            saturation = palette.saturation,
            tone = palette.tone
        )
    }

    /**
     * 选择预设
     */
    fun selectColorPreset(preset: ColorPreset) {
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(
            colorPalette = preset.palette,
            selectedPresetId = preset.id
        )
        
        // 实时更新 GL 渲染器
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = preset.palette.temperatureKelvin,
            saturation = preset.palette.saturation,
            tone = preset.palette.tone
        )
    }

    /**
     * 重置所有调色参数
     */
    fun resetColorPalette() {
        hapticFeedback.click()
        val defaultPalette = ColorPalette.DEFAULT
        _cameraState.value = _cameraState.value.copy(
            colorPalette = defaultPalette,
            selectedPresetId = ColorPreset.PRESET_ORIGINAL.id
        )
        
        // 实时更新 GL 渲染器
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = defaultPalette.temperatureKelvin,
            saturation = defaultPalette.saturation,
            tone = defaultPalette.tone
        )
    }

    /**
     * 保存当前参数为新预设
     */
    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            try {
                val newPreset = ColorPreset(
                    id = "custom_${System.currentTimeMillis()}",
                    name = name,
                    palette = _cameraState.value.colorPalette,
                    isCustom = true
                )
                
                colorPaletteRepository.saveCustomPreset(newPreset)
                
                // 更新预设列表
                _colorPresets.value = ColorPreset.defaultPresets() + colorPaletteRepository.getCustomPresets()
                
                // 选中新预设
                _cameraState.value = _cameraState.value.copy(selectedPresetId = newPreset.id)
                
                hapticFeedback.success()
                Timber.d("Preset saved: $name")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save preset")
                hapticFeedback.error()
            }
        }
    }

    // ==================== Pro 模式参数 ====================

    fun updateManualParameters(update: (ManualParameters) -> ManualParameters) {
        val current = _cameraState.value.manualParameters
        val updated = update(current)
        _cameraState.value = _cameraState.value.copy(manualParameters = updated)
        
        // 将参数应用到相机会话
        viewModelScope.launch {
            try {
                cameraSessionManager.updateManualParameters(updated)
                Timber.d("Manual parameters applied: $updated")
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply manual parameters")
            }
        }
    }

    fun setExposureCompensation(ev: Float) {
        updateManualParameters { it.copy(exposureCompensation = ev) }
    }

    fun setIso(iso: Int?) {
        updateManualParameters { it.copy(iso = iso) }
    }

    fun setShutterSpeed(speed: Double?) {
        updateManualParameters { it.copy(shutterSpeed = speed) }
    }

    fun setFocusDistance(distance: Float?) {
        updateManualParameters { it.copy(focusDistance = distance) }
    }

    fun toggleAeLock() {
        hapticFeedback.doubleClick()
        updateManualParameters { it.copy(isAeLocked = !it.isAeLocked) }
    }

    fun toggleAfLock() {
        hapticFeedback.doubleClick()
        updateManualParameters { it.copy(isAfLocked = !it.isAfLocked) }
    }

    // ==================== 拍照 ====================
    
    /**
     * 处理后的照片数据（包含方向信息）
     */
    private data class ProcessedPhoto(
        val data: ByteArray,
        val orientation: Int = ExifInterface.ORIENTATION_NORMAL
    )

    /**
     * 拍照 - 使用异步后台处理
     * 
     * 流程：
     * 1. 立即捕获照片数据
     * 2. 将处理任务提交到后台队列
     * 3. 立即返回让用户可以继续拍照
     * 4. 后台完成处理后自动保存到相册
     */
    fun capturePhoto() {
        hapticFeedback.heavyClick()
        _cameraState.value = _cameraState.value.copy(isCapturing = true)

        viewModelScope.launch {
            try {
                // 使用 CameraSessionManager 拍照（它已经打开了相机）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val captureStart = System.currentTimeMillis()
                    val photoData = cameraSessionManager.capturePhoto(
                        flashMode = _cameraState.value.flashMode
                    )
                    Timber.d("Photo captured in ${System.currentTimeMillis() - captureStart}ms, size: ${photoData.size} bytes")
                    
                    // 收集当前的处理参数（在主线程捕获，避免竞态条件）
                    val selectedLut = _cameraState.value.selectedLut ?: run {
                        val lutId = _cameraState.value.currentLutId
                        if (lutId != null) lutManager.lutFilters.value.find { it.id == lutId } else null
                    }
                    val lutIntensity = _cameraState.value.lutIntensity / 100f
                    val colorPalette = _cameraState.value.colorPalette
                    val watermarkEnabled = settings.value.watermarkEnabled
                    val watermarkPosition = settings.value.watermarkPosition
                    val isLivePhoto = _cameraState.value.isLivePhotoEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    
                    // 提交到后台处理队列 - 立即返回
                    photoProcessingQueue.submit(
                        photoData = photoData,
                        selectedLut = selectedLut,
                        lutIntensity = lutIntensity,
                        colorPalette = colorPalette,
                        watermarkEnabled = watermarkEnabled,
                        watermarkPosition = watermarkPosition,
                        isLivePhoto = isLivePhoto
                    ) { result ->
                        // 处理完成回调（在后台线程执行）
                        handleProcessedPhoto(result, isLivePhoto)
                    }
                    
                    // 快速反馈 - 拍照完成（处理在后台继续）
                    hapticFeedback.success()
                    Timber.d("Photo submitted for background processing")
                    
                } else {
                    Timber.w("Camera capture requires Android P or higher")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to capture photo")
                hapticFeedback.error()
            } finally {
                // 立即重置拍照状态，让用户可以快速连续拍照
                _cameraState.value = _cameraState.value.copy(isCapturing = false)
            }
        }
    }
    
    /**
     * 处理完成的照片（在后台队列中调用）
     */
    private suspend fun handleProcessedPhoto(result: ProcessedPhotoResult, isLivePhoto: Boolean) {
        if (!result.isSuccess) {
            Timber.e("Photo processing failed: ${result.error}")
            return
        }
        
        val processedPhoto = ProcessedPhoto(result.data, result.orientation)
        
        if (isLivePhoto && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            captureLivePhotoWithData(processedPhoto.data, processedPhoto.orientation)
        } else {
            savePhotoToGallery(processedPhoto)
        }
        
        Timber.d("Photo #${result.id} saved successfully")
    }

    /**
     * 应用 LUT 滤镜和调色盘参数到照片，同时保留 EXIF 方向信息
     * 使用后台线程处理以避免 ANR
     */
    private suspend fun applyLutToPhoto(photoData: ByteArray): ProcessedPhoto = withContext(Dispatchers.Default) {
        // 先从原始 JPEG 读取方向信息
        val originalOrientation = try {
            val inputStream = java.io.ByteArrayInputStream(photoData)
            val exif = ExifInterface(inputStream)
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to read EXIF orientation, using default")
            ExifInterface.ORIENTATION_NORMAL
        }
        
        Timber.d("Original photo orientation: $originalOrientation")
        
        // 优先使用 selectedLut，如果为 null 则尝试通过 currentLutId 查找
        var selectedLut = _cameraState.value.selectedLut
        if (selectedLut == null) {
            val lutId = _cameraState.value.currentLutId
            if (lutId != null) {
                selectedLut = lutManager.lutFilters.value.find { it.id == lutId }
            }
        }
        
        val lutIntensity = _cameraState.value.lutIntensity / 100f  // 转换为 0-1 范围
        val colorPalette = _cameraState.value.colorPalette
        val hasColorAdjustments = !colorPalette.isDefault()
        
        // 如果没有选中 LUT 且没有调色参数，直接返回原图
        if ((selectedLut == null || lutIntensity <= 0f) && !hasColorAdjustments) {
            Timber.d("No LUT selected and no color adjustments, returning original photo")
            return@withContext ProcessedPhoto(photoData, originalOrientation)
        }
        
        try {
            Timber.d("Applying effects - LUT: ${selectedLut?.name}, ColorPalette: temp=${colorPalette.temperatureKelvin}K, sat=${colorPalette.saturation}, tone=${colorPalette.tone}")
            
            // 解码 JPEG 为 Bitmap
            val startDecode = System.currentTimeMillis()
            var bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size)
            if (bitmap == null) {
                Timber.e("Failed to decode photo data to bitmap")
                return@withContext ProcessedPhoto(photoData, originalOrientation)
            }
            Timber.d("Decoded bitmap: ${bitmap.width}x${bitmap.height} in ${System.currentTimeMillis() - startDecode}ms")
            
            // 应用调色盘参数
            if (hasColorAdjustments) {
                val startColor = System.currentTimeMillis()
                Timber.d("Starting color palette processing...")
                bitmap = applyColorPaletteToBitmap(bitmap, colorPalette)
                Timber.d("Color palette applied in ${System.currentTimeMillis() - startColor}ms")
            }
            
            // 应用 LUT
            if (selectedLut != null && lutIntensity > 0f) {
                val startLut = System.currentTimeMillis()
                Timber.d("Starting LUT processing...")
                bitmap = lutManager.applyLut(
                    input = bitmap,
                    lutId = selectedLut.id,
                    intensity = lutIntensity
                )
                Timber.d("LUT applied in ${System.currentTimeMillis() - startLut}ms")
            }
            
            // 应用水印（如果启用）- 传递 EXIF 方向以确保水印位置正确
            val watermarkEnabled = settings.value.watermarkEnabled
            if (watermarkEnabled) {
                val startWatermark = System.currentTimeMillis()
                val watermarkPosition = settings.value.watermarkPosition
                bitmap = watermarkRenderer.applyWatermark(bitmap, watermarkPosition, originalOrientation)
                Timber.d("Watermark applied in ${System.currentTimeMillis() - startWatermark}ms")
            }
            
            // 编码回 JPEG
            val startEncode = System.currentTimeMillis()
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            Timber.d("Encoded JPEG in ${System.currentTimeMillis() - startEncode}ms")
            
            Timber.d("Effects applied successfully, output size: ${outputStream.size()} bytes")
            ProcessedPhoto(outputStream.toByteArray(), originalOrientation)
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply effects, returning original photo")
            ProcessedPhoto(photoData, originalOrientation)
        }
    }
    
    /**
     * 应用调色盘参数到 Bitmap（CPU 处理，用于拍照）
     * 
     * 与 GPU Shader (ColorPaletteShader) 算法保持一致：
     * 1. 色温：使用 RGB 乘数
     * 2. 饱和度：使用 HSL 颜色空间调整
     * 3. 光影：使用 S 曲线调整对比度
     * 
     * 优化：使用预计算的查找表加速 sRGB <-> Linear 转换
     */
    private fun applyColorPaletteToBitmap(input: Bitmap, palette: ColorPalette): Bitmap {
        val width = input.width
        val height = input.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 获取参数（与 GPU Shader 完全一致）
        val targetKelvin = palette.targetKelvin
        val exposureGain = palette.exposureGain
        val saturationAdjust = palette.saturationAdjust
        
        // 计算白平衡 RGB 乘数
        val wbMult = calculateWhiteBalanceMultipliers(targetKelvin)
        val wbR = wbMult[0]
        val wbG = wbMult[1]
        val wbB = wbMult[2]
        
        // 预计算 sRGB -> Linear 查找表 (256 个值)
        val srgbToLinearLut = FloatArray(256) { i ->
            val x = i / 255f
            if (x <= 0.04045f) x / 12.92f
            else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        
        // 预计算 Linear -> sRGB 查找表 (4097 个值，覆盖 0-1 范围)
        val linearToSrgbLut = FloatArray(4097) { i ->
            val x = i / 4096f
            if (x <= 0.0031308f) x * 12.92f
            else (1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f)
        }
        
        // 使用分块处理优化性能（Android 上 Java Stream 效率低）
        val numCores = Runtime.getRuntime().availableProcessors()
        val chunkSize = (totalPixels + numCores - 1) / numCores
        val threads = Array(numCores) { threadIdx ->
            Thread {
                val startIdx = threadIdx * chunkSize
                val endIdx = minOf(startIdx + chunkSize, totalPixels)
                for (i in startIdx until endIdx) {
            val pixel = pixels[i]
            val a = pixel and 0xFF000000.toInt()
            val ri = (pixel shr 16) and 0xFF
            val gi = (pixel shr 8) and 0xFF
            val bi = pixel and 0xFF
            
            // 1. sRGB → Linear（使用查找表）
            var r = srgbToLinearLut[ri]
            var g = srgbToLinearLut[gi]
            var b = srgbToLinearLut[bi]
            
            // 2. 白平衡（在 Linear 空间）
            r *= wbR
            g *= wbG
            b *= wbB
            
            // 3. 曝光增益（在 Linear 空间）
            r *= exposureGain
            g *= exposureGain
            b *= exposureGain
            
            // 4. 饱和度调整（OKLab 空间，内联计算）
            // === 内联 linearRgbToOklab ===
            val lms_l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
            val lms_m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
            val lms_s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
            
            val lCbrt = kotlin.math.cbrt(lms_l.toDouble()).toFloat()
            val mCbrt = kotlin.math.cbrt(lms_m.toDouble()).toFloat()
            val sCbrt = kotlin.math.cbrt(lms_s.toDouble()).toFloat()
            
            val okL = 0.2104542553f * lCbrt + 0.7936177850f * mCbrt - 0.0040720468f * sCbrt
            val okA = 1.9779984951f * lCbrt - 2.4285922050f * mCbrt + 0.4505937099f * sCbrt
            val okB = 0.0259040371f * lCbrt + 0.7827717662f * mCbrt - 0.8086757660f * sCbrt
            
            // === 内联 adjustSaturationOklab ===
            val C = kotlin.math.sqrt((okA * okA + okB * okB).toDouble()).toFloat()
            var adjA = okA
            var adjB = okB
            
            if (C >= 0.0001f) {
                // 内联 smoothstep 用于高光保护
                var t = (okL - 0.7f) / 0.3f
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val highlightProtection = 1f - t * t * (3f - 2f * t)
                
                // 内联 smoothstep 用于阴影保护
                t = okL / 0.2f
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val shadowProtection = t * t * (3f - 2f * t)
                
                val effectiveSatAdjust = saturationAdjust * highlightProtection * shadowProtection
                var newC = C * (1f + effectiveSatAdjust)
                
                // 软裁剪
                if (newC > 0.4f) {
                    newC = 0.4f + (newC - 0.4f) / (1f + (newC - 0.4f))
                }
                if (newC < 0f) newC = 0f else if (newC > 0.5f) newC = 0.5f
                
                val scale = newC / C
                adjA = okA * scale
                adjB = okB * scale
            }
            
            // === 内联 oklabToLinearRgb ===
            val lCbrt2 = okL + 0.3963377774f * adjA + 0.2158037573f * adjB
            val mCbrt2 = okL - 0.1055613458f * adjA - 0.0638541728f * adjB
            val sCbrt2 = okL - 0.0894841775f * adjA - 1.2914855480f * adjB
            
            val ll = lCbrt2 * lCbrt2 * lCbrt2
            val mm = mCbrt2 * mCbrt2 * mCbrt2
            val ss = sCbrt2 * sCbrt2 * sCbrt2
            
            r = 4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss
            g = -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss
            b = -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss
            
            // 5. Linear → sRGB（使用查找表，内联边界检查）
            var rIdx = (r * 4096f).toInt()
            var gIdx = (g * 4096f).toInt()
            var bIdx = (b * 4096f).toInt()
            if (rIdx < 0) rIdx = 0 else if (rIdx > 4096) rIdx = 4096
            if (gIdx < 0) gIdx = 0 else if (gIdx > 4096) gIdx = 4096
            if (bIdx < 0) bIdx = 0 else if (bIdx > 4096) bIdx = 4096
            
            val rSrgb = linearToSrgbLut[rIdx]
            val gSrgb = linearToSrgbLut[gIdx]
            val bSrgb = linearToSrgbLut[bIdx]
            
            // 转换回 0-255 范围
            var finalR = (rSrgb * 255f).toInt()
            var finalG = (gSrgb * 255f).toInt()
            var finalB = (bSrgb * 255f).toInt()
            if (finalR < 0) finalR = 0 else if (finalR > 255) finalR = 255
            if (finalG < 0) finalG = 0 else if (finalG > 255) finalG = 255
            if (finalB < 0) finalB = 0 else if (finalB > 255) finalB = 255
            
            pixels[i] = a or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }
        
        // 启动所有线程
        threads.forEach { it.start() }
        // 等待所有线程完成
        threads.forEach { it.join() }
        
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        
        if (input != output) {
            input.recycle()
        }
        
        return output
    }
    
    // ============= 颜色空间转换函数（与 GPU Shader 完全一致）=============
    
    /**
     * sRGB → Linear（精确转换，与 GPU Shader 一致）
     */
    private fun srgbToLinear(x: Float): Float {
        return if (x <= 0.04045f) {
            x / 12.92f
        } else {
            ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }
    
    /**
     * Linear → sRGB（精确转换，与 GPU Shader 一致）
     */
    private fun linearToSrgb(x: Float): Float {
        val clamped = x.coerceIn(0f, 1f)
        return if (clamped <= 0.0031308f) {
            clamped * 12.92f
        } else {
            1.055f * clamped.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        }
    }
    
    /**
     * Linear RGB → OKLab（与 GPU Shader 一致）
     */
    private fun linearRgbToOklab(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        
        val lCbrt = kotlin.math.cbrt(l.toDouble()).toFloat()
        val mCbrt = kotlin.math.cbrt(m.toDouble()).toFloat()
        val sCbrt = kotlin.math.cbrt(s.toDouble()).toFloat()
        
        return floatArrayOf(
            0.2104542553f * lCbrt + 0.7936177850f * mCbrt - 0.0040720468f * sCbrt,
            1.9779984951f * lCbrt - 2.4285922050f * mCbrt + 0.4505937099f * sCbrt,
            0.0259040371f * lCbrt + 0.7827717662f * mCbrt - 0.8086757660f * sCbrt
        )
    }
    
    /**
     * OKLab → Linear RGB（与 GPU Shader 一致）
     */
    private fun oklabToLinearRgb(oklab: FloatArray): FloatArray {
        val L = oklab[0]
        val a = oklab[1]
        val b = oklab[2]
        
        val lCbrt = L + 0.3963377774f * a + 0.2158037573f * b
        val mCbrt = L - 0.1055613458f * a - 0.0638541728f * b
        val sCbrt = L - 0.0894841775f * a - 1.2914855480f * b
        
        val l = lCbrt * lCbrt * lCbrt
        val m = mCbrt * mCbrt * mCbrt
        val s = sCbrt * sCbrt * sCbrt
        
        return floatArrayOf(
            (4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s).coerceIn(0f, 1f),
            (-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s).coerceIn(0f, 1f),
            (-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s).coerceIn(0f, 1f)
        )
    }
    
    /**
     * OKLab 空间饱和度调整（带高光/阴影保护，与 GPU Shader 一致）
     */
    private fun adjustSaturationOklab(oklab: FloatArray, satAdjust: Float): FloatArray {
        val L = oklab[0]
        val a = oklab[1]
        val b = oklab[2]
        
        // 计算色度 C
        val C = kotlin.math.sqrt((a * a + b * b).toDouble()).toFloat()
        if (C < 0.0001f) {
            return oklab // 灰色，无需调整
        }
        
        // 高光保护：亮度越高，饱和度调整越弱
        val highlightProtection = 1f - smoothstep(0.7f, 1.0f, L)
        // 阴影保护：亮度越低，饱和度调整越弱
        val shadowProtection = smoothstep(0.0f, 0.2f, L)
        
        // 计算有效饱和度调整量
        val effectiveSatAdjust = satAdjust * highlightProtection * shadowProtection
        
        // 应用饱和度调整
        var newC = C * (1f + effectiveSatAdjust)
        
        // 软裁剪防止过饱和（与 GPU Shader 一致）
        if (newC > 0.4f) {
            newC = 0.4f + (newC - 0.4f) / (1f + (newC - 0.4f))
        }
        
        newC = newC.coerceIn(0f, 0.5f)
        
        val scale = if (C > 0.0001f) newC / C else 1f
        
        return floatArrayOf(L, a * scale, b * scale)
    }
    
    /**
     * 平滑阶梯函数（与 GPU Shader 一致）
     */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    
    /**
     * 计算白平衡 RGB 乘数（与 GPU Shader kelvinToRgbMultipliers 完全一致）
     * 
     * 使用 Tanner Helland 算法计算色温对应的 RGB 值，然后以 5500K 为基准归一化
     */
    private fun calculateWhiteBalanceMultipliers(targetKelvin: Float): FloatArray {
        val temp = targetKelvin / 100f
        val rgb = FloatArray(3)
        
        // 红色分量
        rgb[0] = if (temp <= 66f) {
            1f
        } else {
            (1.292936186f * Math.pow((temp - 60.0).toDouble(), -0.1332047592).toFloat()).coerceIn(0f, 1f)
        }
        
        // 绿色分量
        rgb[1] = if (temp <= 66f) {
            (0.3900815788f * Math.log(temp.toDouble()).toFloat() - 0.6318414438f).coerceIn(0f, 1f)
        } else {
            (1.129890861f * Math.pow((temp - 60.0).toDouble(), -0.0755148492).toFloat()).coerceIn(0f, 1f)
        }
        
        // 蓝色分量
        rgb[2] = if (temp >= 66f) {
            1f
        } else if (temp <= 19f) {
            0f
        } else {
            (0.5432067891f * Math.log((temp - 10f).toDouble()).toFloat() - 1.1962540892f).coerceIn(0f, 1f)
        }
        
        // 归一化（以 5500K 为基准，与 GPU Shader 一致）
        val baseRgb = floatArrayOf(1f, 0.94f, 0.91f) // 5500K 近似值
        return floatArrayOf(
            baseRgb[0] / rgb[0].coerceAtLeast(0.001f),
            baseRgb[1] / rgb[1].coerceAtLeast(0.001f),
            baseRgb[2] / rgb[2].coerceAtLeast(0.001f)
        )
    }

    /**
     * 保存照片到相册（带方向信息）
     * 所有 I/O 操作在 IO 调度器执行
     */
    private suspend fun savePhotoToGallery(processedPhoto: ProcessedPhoto) = withContext(Dispatchers.IO) {
        Timber.d("savePhotoToGallery started, data size: ${processedPhoto.data.size} bytes")
        try {
            // 获取设置的输出格式
            val outputFormat = settings.value.outputFormat
            val isLumaLogEnabled = settings.value.lumaLogEnabled
            
            // 根据输出格式确定 MIME 类型和文件扩展名
            val (mimeType, extension) = when (outputFormat) {
                OutputFormat.JPEG -> "image/jpeg" to "jpg"
                OutputFormat.HEIF -> "image/heif" to "heic"
                OutputFormat.RAW_DNG -> "image/x-adobe-dng" to "dng"
                OutputFormat.RAW_JPEG -> "image/jpeg" to "jpg" // RAW+JPEG 先保存 JPEG，RAW 另存
            }
            
            // 生成文件名：LUMA_20260105_143052
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val displayName = "LUMA_$timestamp"

            Timber.d("Saving photo: $displayName with format: $outputFormat ($mimeType), orientation: ${processedPhoto.orientation}, lumaLog: $isLumaLogEnabled")

            // 如果是 HEIF 格式，需要重新编码
            val finalData = when (outputFormat) {
                OutputFormat.HEIF -> {
                    // 将 JPEG 解码并重新编码为 HEIF
                    val bitmap = BitmapFactory.decodeByteArray(processedPhoto.data, 0, processedPhoto.data.size)
                    val outputStream = ByteArrayOutputStream()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, outputStream)
                        // 注意：Android 原生不直接支持 HEIF 编码，这里用 WEBP 作为替代
                        // 实际上需要使用 ImageWriter 或第三方库来编码 HEIF
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                    bitmap.recycle()
                    outputStream.toByteArray()
                }
                else -> processedPhoto.data
            }

            // 创建文件
            val result = mediaStoreHelper.createImageFile(
                displayName = displayName,
                mimeType = mimeType
            )

            result.onSuccess { (uri, outputStream) ->
                // 写入数据
                outputStream.use { stream ->
                    stream.write(finalData)
                    stream.flush()
                }

                // 完成写入
                mediaStoreHelper.finishPendingFile(uri)

                // 写入 EXIF 方向信息（需要在文件写入后操作）
                try {
                    // 通过 ContentResolver 获取文件描述符来写入 EXIF
                    val pfd = mediaStoreHelper.getContentResolver().openFileDescriptor(uri, "rw")
                    pfd?.use { fd ->
                        val exif = ExifInterface(fd.fileDescriptor)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            processedPhoto.orientation.toString()
                        )
                        exif.saveAttributes()
                        Timber.d("EXIF orientation written: ${processedPhoto.orientation}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to write EXIF orientation")
                }

                // 更新元数据
                mediaStoreHelper.updateImageMetadata(
                    uri = uri,
                    dateTaken = System.currentTimeMillis()
                )

                Timber.d("Photo saved successfully: $uri")
                
                // 如果启用了 LumaLog，保存 RAW 或灰片
                if (isLumaLogEnabled) {
                    if (cameraSessionManager.isRawCaptureSupported()) {
                        // 设备支持 RAW，保存 DNG 格式
                        saveLumaLogImage(timestamp, _cameraState.value.flashMode)
                    } else {
                        // 设备不支持 RAW，保存灰片
                        saveLumaLogFlatImage(processedPhoto, timestamp)
                    }
                }
            }.onFailure { error ->
                Timber.e(error, "Failed to create image file")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save photo to gallery")
        }
    }
    
    /**
     * 保存 LumaLog RAW 图像（DNG 格式，保留全部传感器细节）
     * 如果设备不支持 RAW，则保存灰片（Flat Profile）作为备选
     */
    private suspend fun saveLumaLogImage(timestamp: String, flashMode: FlashMode) {
        try {
            // 检查设备是否支持 RAW 捕获
            if (cameraSessionManager.isRawCaptureSupported()) {
                Timber.d("Capturing LumaLog RAW (DNG)...")
                saveLumaLogRawImage(timestamp, flashMode)
            } else {
                Timber.d("Device does not support RAW, saving flat profile instead...")
                // 设备不支持 RAW，使用当前照片生成灰片
                // 这部分在 savePhotoToGallery 中处理
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save LumaLog image")
        }
    }
    
    /**
     * 保存 LumaLog RAW 图像（DNG 格式）
     */
    private suspend fun saveLumaLogRawImage(timestamp: String, flashMode: FlashMode) {
        try {
            // 捕获 RAW 图像
            val rawResult = cameraSessionManager.captureRawPhoto(flashMode)
            
            try {
                val displayName = "LUMA_${timestamp}_RAW"
                
                // 创建 DNG 文件
                val dngResult = mediaStoreHelper.createImageFile(
                    displayName = displayName,
                    mimeType = MediaStoreHelper.MIME_TYPE_DNG
                )
                
                dngResult.onSuccess { (uri, outputStream) ->
                    // 写入 DNG 数据
                    val writeResult = dngWriter.writeDng(
                        outputStream = outputStream,
                        image = rawResult.rawImage,
                        characteristics = rawResult.characteristics,
                        captureResult = rawResult.captureResult,
                        orientation = rawResult.jpegOrientation,
                        metadata = com.luma.camera.storage.DngMetadata(
                            description = "LumaLog RAW - Full Sensor Data for Maximum Post-Processing Flexibility"
                        )
                    )
                    
                    writeResult.onSuccess {
                        mediaStoreHelper.finishPendingFile(uri)
                        mediaStoreHelper.updateImageMetadata(
                            uri = uri,
                            dateTaken = System.currentTimeMillis()
                        )
                        Timber.d("LumaLog RAW (DNG) saved: $uri")
                    }.onFailure { error ->
                        Timber.e(error, "Failed to write DNG data")
                        mediaStoreHelper.deleteFile(uri)
                    }
                }.onFailure { error ->
                    Timber.e(error, "Failed to create DNG file")
                }
            } finally {
                // 必须关闭 RAW 图像
                rawResult.rawImage.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to capture LumaLog RAW")
        }
    }

    /**
     * 保存 LumaLog 灰片（保留全部细节的低对比度/低饱和度图像）
     * 用于不支持 RAW 的设备
     */
    private suspend fun saveLumaLogFlatImage(processedPhoto: ProcessedPhoto, timestamp: String) {
        try {
            Timber.d("Generating LumaLog flat image...")
            
            // 解码原始图像
            val originalBitmap = BitmapFactory.decodeByteArray(
                processedPhoto.data, 0, processedPhoto.data.size
            ) ?: run {
                Timber.w("Failed to decode photo for LumaLog processing")
                return
            }
            
            // 使用 FlatProfileGenerator 生成灰片
            val flatImage = flatProfileGenerator.generate(originalBitmap)
            
            // 编码为 JPEG
            val flatOutputStream = ByteArrayOutputStream()
            flatImage.compress(Bitmap.CompressFormat.JPEG, 100, flatOutputStream)
            val flatData = flatOutputStream.toByteArray()
            
            // 生成灰片文件名
            val flatDisplayName = "LUMA_${timestamp}_FLAT"
            
            // 保存灰片
            val flatResult = mediaStoreHelper.createImageFile(
                displayName = flatDisplayName,
                mimeType = "image/jpeg"
            )
            
            flatResult.onSuccess { (flatUri, flatOutput) ->
                flatOutput.use { stream ->
                    stream.write(flatData)
                    stream.flush()
                }
                
                mediaStoreHelper.finishPendingFile(flatUri)
                
                // 写入 EXIF 方向信息
                try {
                    val pfd = mediaStoreHelper.getContentResolver().openFileDescriptor(flatUri, "rw")
                    pfd?.use { fd ->
                        val exif = ExifInterface(fd.fileDescriptor)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            processedPhoto.orientation.toString()
                        )
                        // 添加自定义标签标识这是 LumaLog 灰片
                        exif.setAttribute(
                            ExifInterface.TAG_IMAGE_DESCRIPTION,
                            "LumaLog Flat Profile - Low Contrast, Low Saturation for Maximum Editing Flexibility"
                        )
                        exif.saveAttributes()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to write EXIF for flat image")
                }
                
                mediaStoreHelper.updateImageMetadata(
                    uri = flatUri,
                    dateTaken = System.currentTimeMillis()
                )
                
                Timber.d("LumaLog flat image saved: $flatUri")
            }.onFailure { error ->
                Timber.e(error, "Failed to save LumaLog flat image")
            }
            
            // 清理
            if (!originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
            if (!flatImage.isRecycled) {
                flatImage.recycle()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate LumaLog flat image")
        }
    }
    
    /**
     * 捕获并保存实况照片
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLivePhotoWithData(photoData: ByteArray, orientation: Int = ExifInterface.ORIENTATION_NORMAL) = withContext(Dispatchers.IO) {
        try {
            Timber.d("Capturing live photo...")
            
            // 解码照片为 Bitmap
            val bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size)
            if (bitmap == null) {
                Timber.e("Failed to decode photo for live photo")
                // 回退到普通照片保存
                savePhotoToGallery(ProcessedPhoto(photoData, orientation))
                return@withContext
            }
            
            // 获取输出目录
            val outputDir = mediaStoreHelper.getLivePhotoOutputDir()
            
            // 调用 LivePhotoManager 捕获实况照片
            val result = livePhotoManager.captureLivePhoto(
                photo = bitmap,
                outputDir = outputDir
            )
            
            result.onSuccess { livePhotoResult ->
                Timber.d("Live photo captured: ${livePhotoResult.photoFile}, ${livePhotoResult.videoFile}")
                // 将文件添加到媒体库
                mediaStoreHelper.addLivePhotoToMediaStore(
                    photoFile = livePhotoResult.photoFile,
                    videoFile = livePhotoResult.videoFile
                )
            }.onFailure { error ->
                Timber.e(error, "Failed to capture live photo, falling back to normal photo")
                // 回退到普通照片保存
                savePhotoToGallery(ProcessedPhoto(photoData, orientation))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error capturing live photo")
            // 回退到普通照片保存
            savePhotoToGallery(ProcessedPhoto(photoData, orientation))
        }
    }

    // ==================== 触摸对焦 ====================

    fun onTouchFocus(x: Float, y: Float) {
        hapticFeedback.tick()
        cameraController.triggerFocus(x, y)
    }

    fun onLongPress(x: Float, y: Float) {
        // 长按锁定 AE/AF
        toggleAeLock()
        toggleAfLock()
    }

    // ==================== 生命周期 ====================
    
    // 保存的预览 Surface，用于恢复相机
    private var savedPreviewSurface: Surface? = null
    
    /**
     * 暂停相机 - 当 Activity 进入后台时调用
     * 
     * 同步执行以确保在 Activity 暂停前完成
     */
    fun pauseCamera() {
        Timber.d("Pausing camera...")
        try {
            // 停止实况照片缓冲录制
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                livePhotoManager.stopBuffering()
            }
            
            // 停止 GL 渲染器（暂停模式，保留 EGL 上下文）
            glPreviewRenderer.onPause()
            
            // 关闭相机会话
            cameraSessionManager.closeCamera()
            _cameraState.value = _cameraState.value.copy(isCameraReady = false)
            
            Timber.d("Camera paused successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error pausing camera")
        }
    }
    
    /**
     * 恢复相机 - 当 Activity 从后台恢复时调用
     * 
     * 恢复流程：
     * 1. 检查 GL 渲染器是否需要重新初始化
     * 2. 如果需要重新初始化，触发完整的重新初始化流程
     * 3. 如果不需要，直接恢复渲染器并重新打开相机
     */
    fun resumeCamera() {
        Timber.d("resumeCamera called")
        viewModelScope.launch {
            try {
                // 首先恢复 GL 渲染器（这会检测 EGL Surface 是否有效）
                glPreviewRenderer.onResume()
                
                // 等待一小段时间让渲染器完成恢复检查
                kotlinx.coroutines.delay(50)
                
                // 检查渲染器是否需要重新初始化（EGL Surface 失效）
                if (glPreviewRenderer.needsReinitialization()) {
                    Timber.d("GL Renderer needs reinitialization, triggering full reinitialization")
                    // 触发完整的重新初始化流程
                    // 回调函数会在重新初始化完成后自动重新打开相机
                    glPreviewRenderer.triggerReinitialization()
                    return@launch
                }
                
                // 检查渲染器是否就绪
                if (!glPreviewRenderer.isReady()) {
                    Timber.d("GL Renderer not ready, waiting for reinitialization")
                    shouldResumeCamera = true
                    return@launch
                }
                
                // 检查是否有有效的 Surface 可用
                val cameraSurface = glPreviewRenderer.getCameraSurface()
                if (cameraSurface != null && cameraSurface.isValid) {
                    Timber.d("Surface is valid, reopening camera...")
                    // Surface 有效，直接重新打开相机
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val currentFocalLength = _cameraState.value.currentFocalLength
                        val result = cameraSessionManager.openCamera(
                            focalLength = currentFocalLength,
                            previewSurface = cameraSurface
                        )
                        result.onSuccess {
                            Timber.d("Camera resumed successfully")
                            _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                            glPreviewRenderer.startRendering()
                            
                            // 如果实况功能已启用，恢复缓冲录制
                            if (_cameraState.value.isLivePhotoEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLivePhotoBuffering()
                            }
                        }.onFailure { error ->
                            Timber.e(error, "Failed to resume camera")
                            _cameraState.value = _cameraState.value.copy(isCameraReady = false)
                            // 尝试设置标志等待重新初始化
                            shouldResumeCamera = true
                        }
                    }
                } else {
                    Timber.d("Surface not ready, setting flag to resume when surface is available")
                    shouldResumeCamera = true
                }
            } catch (e: Exception) {
                Timber.e(e, "Error resuming camera")
                shouldResumeCamera = true
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraSessionManager.closeCamera()
        cameraController.release()
    }
}
