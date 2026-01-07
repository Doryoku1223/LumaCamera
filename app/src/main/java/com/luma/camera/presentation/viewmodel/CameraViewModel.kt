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
 * 鐩告満涓荤晫闈?ViewModel
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

    // 鐩告満鐘舵€?
    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    // 姘村钩浠搴︼紙浠庝紶鎰熷櫒鑾峰彇锛?
    val levelAngle: StateFlow<Float> = sensorInfoManager.stabilityState
        .map { it.roll }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    // 璁剧疆
    val settings: StateFlow<CameraSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CameraSettings()
        )

    // 鍙敤鐒︽
    private val _availableFocalLengths = MutableStateFlow<List<FocalLength>>(emptyList())
    val availableFocalLengths: StateFlow<List<FocalLength>> = _availableFocalLengths.asStateFlow()

    // LUT 鍒楄〃
    val lutFilters = lutManager.lutFilters

    // 褰撳墠閫変腑鐨?LUT
    val currentLut = lutManager.currentLut

    // 婊ら暅闈㈡澘鏄惁鎵撳紑
    private val _isFilterPanelOpen = MutableStateFlow(false)
    val isFilterPanelOpen: StateFlow<Boolean> = _isFilterPanelOpen.asStateFlow()
    
    // 璋冭壊鐩橀璁惧垪琛?
    private val _colorPresets = MutableStateFlow<List<ColorPreset>>(ColorPreset.defaultPresets())
    val colorPresets: StateFlow<List<ColorPreset>> = _colorPresets.asStateFlow()
    
    // 鏍囧織锛氭槸鍚﹂渶瑕佸湪 Surface 灏辩华鍚庤嚜鍔ㄦ仮澶嶇浉鏈?
    private var shouldResumeCamera = false
    private var livePhotoInputSurface: Surface? = null

    init {
        initializeCamera()
        initializeSensors()
        initializeColorPalette()
        initializeHistogramAnalysis()
        setupRendererReinitCallback()
    }
    
    /**
     * 璁剧疆娓叉煋鍣ㄩ噸鏂板垵濮嬪寲鍥炶皟
     * 褰撴覆鏌撳櫒闇€瑕侀噸鏂板垵濮嬪寲鏃讹紙渚嬪 EGL Surface 澶辨晥锛夛紝浼氳嚜鍔ㄩ噸鏂拌繛鎺ョ浉鏈?
     */
    private fun setupRendererReinitCallback() {
        glPreviewRenderer.setReinitializeCallback { cameraSurface ->
            Timber.d("Renderer reinitialized, reopening camera with new surface")
            viewModelScope.launch {
                try {
                    glPreviewRenderer.startRendering()
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val currentFocalLength = _cameraState.value.currentFocalLength
                        val recordingSurface = ensureLivePhotoSurface()
                        val result = cameraSessionManager.openCamera(
                            focalLength = currentFocalLength,
                            previewSurface = cameraSurface,
                            recordingSurface = recordingSurface
                        )
                        result.onSuccess {
                            Timber.d("Camera reopened after renderer reinitialization")
                            _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                            shouldResumeCamera = false
                            applyPreviewEffects()
                            
                            // 濡傛灉瀹炲喌鍔熻兘宸插惎鐢紝鎭㈠缂撳啿褰曞埗
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
            // 鍒濆鍖栫浉鏈烘帶鍒跺櫒
            cameraController.initialize()

            // 鍒濆鍖栧鎽勭鐞?
            multiCameraManager.initialize()
            _availableFocalLengths.value = multiCameraManager.getAvailableFocalLengths()

            // 鍒濆鍖?LUT 绠＄悊鍣?
            lutManager.initialize()
        }
    }
    
    private fun initializeSensors() {
        // 鍒濆鍖栦紶鎰熷櫒锛堢敤浜庢按骞充华锛?
        sensorInfoManager.initialize()
    }
    
    /**
     * 鍒濆鍖栬皟鑹茬洏
     */
    private fun initializeColorPalette() {
        viewModelScope.launch {
            // 浠庢寔涔呭寲鍔犺浇鑷畾涔夐璁?
            val customPresets = colorPaletteRepository.getCustomPresets()
            _colorPresets.value = ColorPreset.defaultPresets() + customPresets
        }
    }
    
    /**
     * 鍒濆鍖栫洿鏂瑰浘鍒嗘瀽
     */
    private fun initializeHistogramAnalysis() {
        // 璁剧疆甯ф暟鎹洖璋冪敤浜庣洿鏂瑰浘鍒嗘瀽
        glPreviewRenderer.setFrameDataCallback { frameData, width, height ->
            viewModelScope.launch {
                analyzeFrameForHistogram(frameData, width, height)
            }
        }
    }
    
    /**
     * 鍒嗘瀽甯ф暟鎹敤浜庣洿鏂瑰浘
     */
    private suspend fun analyzeFrameForHistogram(frameData: ByteArray, width: Int, height: Int) {
        // 浠呭湪鐩存柟鍥惧紑鍚椂鍒嗘瀽
        if (!settings.value.showHistogram) return
        
        try {
            // 璁＄畻鐩存柟鍥炬暟鎹?
            val red = FloatArray(256)
            val green = FloatArray(256)
            val blue = FloatArray(256)
            val luminance = FloatArray(256)
            
            var maxValue = 0f
            
            // 瑙ｆ瀽 RGBA 鏁版嵁
            for (i in 0 until frameData.size step 16) {  // 閲囨牱浠ユ彁楂橀€熷害
                if (i + 3 >= frameData.size) break
                
                val r = frameData[i].toInt() and 0xFF
                val g = frameData[i + 1].toInt() and 0xFF
                val b = frameData[i + 2].toInt() and 0xFF
                
                red[r]++
                green[g]++
                blue[b]++
                
                // BT.709 浜害
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                luminance[luma]++
            }
            
            // 褰掍竴鍖?
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
            
            // 鏇存柊鐘舵€?
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

    // ==================== 鐩告満妯″紡 ====================

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

    // ==================== 鐒︽鍒囨崲 ====================

    fun setFocalLength(focalLength: FocalLength) {
        // 濡傛灉鏄浉鍚岀劍娈碉紝涓嶉渶瑕佸垏鎹?
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

    // ==================== 棰勮 Surface ====================

    fun onPreviewSurfaceReady(surface: Surface) {
        viewModelScope.launch {
            try {
                Timber.d("Preview surface ready, opening camera...")
                savedPreviewSurface = surface  // 淇濆瓨 Surface 鐢ㄤ簬鍚庣画鎭㈠
                cameraController.setPreviewSurface(surface)
                
                // 浣跨敤 CameraSessionManager 鎵撳紑鐩告満骞跺惎鍔ㄩ瑙?
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val currentFocalLength = _cameraState.value.currentFocalLength
                    val recordingSurface = ensureLivePhotoSurface()
                    val result = cameraSessionManager.openCamera(
                        focalLength = currentFocalLength,
                        previewSurface = surface,
                        recordingSurface = recordingSurface
                    )
                    
                    result.onSuccess {
                        Timber.d("Camera opened successfully with focal length: $currentFocalLength")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                        shouldResumeCamera = false
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
     * GL 棰勮 Surface 鍑嗗灏辩华锛堢浉鏈哄簲杈撳嚭鍒版 Surface锛?
     * 
     * 褰撲娇鐢?GLPreviewRenderer 鏃讹紝鐩告満杈撳嚭鍒?GL 娓叉煋鍣ㄧ殑 SurfaceTexture锛?
     * 鐒跺悗娓叉煋鍣ㄥ啀杈撳嚭鍒板睆骞曘€傝繖鏍峰彲浠ュ疄鐜板疄鏃?LUT 棰勮銆?
     */
    fun onGLPreviewSurfaceReady(surface: Surface) {
        viewModelScope.launch {
            try {
                Timber.d("GL Preview surface ready, opening camera to GL renderer...")
                savedPreviewSurface = surface  // 淇濆瓨 Surface 鐢ㄤ簬鍚庣画鎭㈠
                cameraController.setPreviewSurface(surface)
                
                // 璁剧疆宄板€煎鐒︾姸鎬?
                glPreviewRenderer.setFocusPeakingEnabled(settings.value.focusPeakingEnabled)
                
                // 浣跨敤 CameraSessionManager 鎵撳紑鐩告満骞跺惎鍔ㄩ瑙?
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val currentFocalLength = _cameraState.value.currentFocalLength
                    val recordingSurface = ensureLivePhotoSurface()
                    val result = cameraSessionManager.openCamera(
                        focalLength = currentFocalLength,
                        previewSurface = surface,
                        recordingSurface = recordingSurface
                    )
                    
                    result.onSuccess {
                        Timber.d("Camera opened to GL renderer with focal length: $currentFocalLength")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                        shouldResumeCamera = false
                        
                        // 濡傛灉鏈夐€変腑鐨?LUT锛屾洿鏂伴瑙?
                        applyPreviewEffects()
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

    /**
     * ?? Surface ????
     */
    fun onPreviewSurfaceDestroyed() {
        Timber.d("Preview surface destroyed, closing camera session")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                livePhotoManager.stopBuffering()
            }
            livePhotoInputSurface = null
            cameraSessionManager.closeCamera()
            _cameraState.value = _cameraState.value.copy(isCameraReady = false)
            shouldResumeCamera = true
        } catch (e: Exception) {
            Timber.e(e, "Error handling preview surface destroyed")
        }
    }

    // ==================== 闂厜鐏?====================

    fun setFlashMode(mode: FlashMode) {
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(flashMode = mode)
        
        // 搴旂敤闂厜鐏缃埌鐩告満浼氳瘽
        cameraSessionManager.setFlashMode(mode)
        Timber.d("Flash mode set to: $mode")
    }

    fun cycleFlashMode() {
        val modes = FlashMode.entries
        val currentIndex = modes.indexOf(_cameraState.value.flashMode)
        val nextIndex = (currentIndex + 1) % modes.size
        setFlashMode(modes[nextIndex])
    }

    // ==================== 鐢婚潰姣斾緥 ====================

    fun setAspectRatio(ratio: AspectRatio) {
        // 濡傛灉鏄浉鍚屾瘮渚嬶紝涓嶉渶瑕佸垏鎹?
        if (ratio == _cameraState.value.aspectRatio) {
            return
        }
        
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(aspectRatio = ratio)
        Timber.d("Aspect ratio changed to: $ratio")
        
        // 娉ㄦ剰锛氱敾闈㈡瘮渚嬬殑鏀瑰彉浼氶€氳繃 CameraViewfinder 鐨?aspectRatio 鍙傛暟鑷姩鏇存柊 UI
        // 鐩告満棰勮鏈韩涓嶉渶瑕侀噸鏂伴厤缃紝鍙渶瑕佹敼鍙樺彇鏅櫒鐨勬樉绀鸿鍒囧尯鍩?
    }


    private fun ensureLivePhotoSurface(): Surface? {
        if (!_cameraState.value.isLivePhotoEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        livePhotoInputSurface?.let { surface ->
            if (surface.isValid) {
                return surface
            }
        }
        val surface = livePhotoManager.startBuffering()
        if (surface == null || !surface.isValid) {
            Timber.w("Failed to acquire live photo surface, disabling")
            _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = false)
            livePhotoInputSurface = null
            return null
        }
        livePhotoInputSurface = surface
        return surface
    }

    private fun reopenCameraWithRecordingSurface(recordingSurface: Surface?) {
        val previewSurface = glPreviewRenderer.getCameraSurface() ?: savedPreviewSurface
        if (previewSurface == null || !previewSurface.isValid) {
            Timber.w("Reopen skipped: preview surface not ready")
            shouldResumeCamera = true
            return
        }
        viewModelScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val currentFocalLength = _cameraState.value.currentFocalLength
                    val result = cameraSessionManager.openCamera(
                        focalLength = currentFocalLength,
                        previewSurface = previewSurface,
                        recordingSurface = recordingSurface
                    )
                    result.onSuccess {
                        _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                        shouldResumeCamera = false
                    }.onFailure { error ->
                        Timber.e(error, "Failed to reopen camera")
                        _cameraState.value = _cameraState.value.copy(isCameraReady = false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error reopening camera")
            }
        }
    }

    // ==================== Live Photo ====================

    fun toggleLivePhoto() {
        hapticFeedback.click()
        val current = _cameraState.value.isLivePhotoEnabled
        val newState = !current
        _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = newState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (newState) {
                // ??????????????????????? Surface
                val surface = ensureLivePhotoSurface()
                if (surface != null) {
                    if (_cameraState.value.isCameraReady) {
                        reopenCameraWithRecordingSurface(surface)
                    }
                } else {
                    _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = false)
                }
            } else {
                // ??????????? Surface
                livePhotoManager.stopBuffering()
                livePhotoInputSurface = null
                if (_cameraState.value.isCameraReady) {
                    reopenCameraWithRecordingSurface(null)
                }
            }
        }
    }
    
    /**
     * 鍚姩瀹炲喌鐓х墖缂撳啿褰曞埗
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startLivePhotoBuffering() {
        viewModelScope.launch {
            try {
                Timber.d("Starting live photo buffering...")
                val surface = ensureLivePhotoSurface()
                if (surface != null) {
                    Timber.d("Live photo buffering started successfully")
                } else {
                    Timber.w("Failed to start live photo buffering - surface unavailable")
                    _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting live photo buffering")
                _cameraState.value = _cameraState.value.copy(isLivePhotoEnabled = false)
            }
        }
    }

    // ==================== 婊ら暅 ====================

    fun selectLut(lutId: String?) {
        hapticFeedback.tick()
        lutManager.selectLut(lutId)
        
        // 濡傛灉 lutId 涓嶄负 null锛屽皾璇曚粠 lutFilters 涓幏鍙栧畬鏁寸殑 LutFilter 瀵硅薄
        val lut = if (lutId != null) {
            lutManager.lutFilters.value.find { it.id == lutId }
        } else null
        
        _cameraState.value = _cameraState.value.copy(
            currentLutId = lutId,
            selectedLut = lut
        )
        
        // 鏇存柊 GL 棰勮娓叉煋鍣ㄧ殑 LUT
        updatePreviewLut(lut)
    }

    fun selectLut(lut: LutFilter?) {
        hapticFeedback.tick()
        lutManager.selectLut(lut?.id)
        _cameraState.value = _cameraState.value.copy(
            currentLutId = lut?.id,
            selectedLut = lut
        )
        
        // 鏇存柊 GL 棰勮娓叉煋鍣ㄧ殑 LUT
        updatePreviewLut(lut)
    }
    
    /**
     * 鏇存柊棰勮 LUT
     */
    private fun updatePreviewLut(lut: LutFilter?) {
        if (lut == null) {
            // 娓呴櫎 LUT
            glPreviewRenderer.setLutData(null, 0, null)
        } else {
            // 鑾峰彇 LUT 鏁版嵁锛屽湪 GL 绾跨▼涓婂垱寤虹汗鐞?
            val lutData = lutManager.getLutData(lut.id)
            if (lutData != null) {
                glPreviewRenderer.setLutData(lut.id, lutData.size, lutData.data)
                Timber.d("Preview LUT updated with data: ${lut.name}, size=${lutData.size}")
            } else {
                // LUT 鏁版嵁灏氭湭鍔犺浇锛屽皾璇曞姞杞?
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

    private fun applyPreviewEffects() {
        val palette = _cameraState.value.colorPalette
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = palette.temperatureKelvin,
            saturation = palette.saturation,
            tone = palette.tone
        )
        glPreviewRenderer.setLutIntensity(_cameraState.value.lutIntensity / 100f)
        updatePreviewLut(_cameraState.value.selectedLut)
    }

    fun setLutIntensity(intensity: Int) {
        val clampedIntensity = intensity.coerceIn(0, 100)
        _cameraState.value = _cameraState.value.copy(lutIntensity = clampedIntensity)
        // 鏇存柊棰勮娓叉煋鍣ㄧ殑 LUT 寮哄害 (0-1)
        glPreviewRenderer.setLutIntensity(clampedIntensity / 100f)
    }

    fun setLutIntensity(intensity: Float) {
        // 鍋囪杈撳叆鏄?0-1 鑼冨洿鐨?Float锛岃浆鎹负 0-100 鐨?Int
        val intIntensity = (intensity * 100).toInt().coerceIn(0, 100)
        _cameraState.value = _cameraState.value.copy(lutIntensity = intIntensity)
        // 鏇存柊棰勮娓叉煋鍣ㄧ殑 LUT 寮哄害 (0-1)
        glPreviewRenderer.setLutIntensity(intIntensity / 100f)
    }

    fun importLut(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                Timber.d("Importing LUT from: $uri")
                val importedLut = lutManager.importLutFromUri(uri)
                if (importedLut != null) {
                    Timber.d("LUT imported successfully: ${importedLut.name}")
                    // 鑷姩閫変腑瀵煎叆鐨?LUT
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

    // ==================== 璋冭壊鐩?====================

    /**
     * 鎵撳紑璋冭壊鐩橀潰鏉?
     */
    fun openColorPalettePanel() {
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(isColorPalettePanelOpen = true)
    }

    /**
     * 鍏抽棴璋冭壊鐩橀潰鏉?
     */
    fun closeColorPalettePanel() {
        _cameraState.value = _cameraState.value.copy(isColorPalettePanelOpen = false)
    }

    /**
     * 鏇存柊璋冭壊鍙傛暟
     */
    fun updateColorPalette(palette: ColorPalette) {
        _cameraState.value = _cameraState.value.copy(
            colorPalette = palette,
            selectedPresetId = null // 鐢ㄦ埛鎵嬪姩璋冩暣鍚庡彇娑堥璁鹃€夋嫨
        )
        
        // 瀹炴椂鏇存柊 GL 娓叉煋鍣?
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = palette.temperatureKelvin,
            saturation = palette.saturation,
            tone = palette.tone
        )
    }

    /**
     * 閫夋嫨棰勮
     */
    fun selectColorPreset(preset: ColorPreset) {
        hapticFeedback.tick()
        _cameraState.value = _cameraState.value.copy(
            colorPalette = preset.palette,
            selectedPresetId = preset.id
        )
        
        // 瀹炴椂鏇存柊 GL 娓叉煋鍣?
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = preset.palette.temperatureKelvin,
            saturation = preset.palette.saturation,
            tone = preset.palette.tone
        )
    }

    /**
     * 閲嶇疆鎵€鏈夎皟鑹插弬鏁?
     */
    fun resetColorPalette() {
        hapticFeedback.click()
        val defaultPalette = ColorPalette.DEFAULT
        _cameraState.value = _cameraState.value.copy(
            colorPalette = defaultPalette,
            selectedPresetId = ColorPreset.PRESET_ORIGINAL.id
        )
        
        // 瀹炴椂鏇存柊 GL 娓叉煋鍣?
        glPreviewRenderer.updateColorPalette(
            temperatureKelvin = defaultPalette.temperatureKelvin,
            saturation = defaultPalette.saturation,
            tone = defaultPalette.tone
        )
    }

    /**
     * 淇濆瓨褰撳墠鍙傛暟涓烘柊棰勮
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
                
                // 鏇存柊棰勮鍒楄〃
                _colorPresets.value = ColorPreset.defaultPresets() + colorPaletteRepository.getCustomPresets()
                
                // 閫変腑鏂伴璁?
                _cameraState.value = _cameraState.value.copy(selectedPresetId = newPreset.id)
                
                hapticFeedback.success()
                Timber.d("Preset saved: $name")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save preset")
                hapticFeedback.error()
            }
        }
    }

    // ==================== Pro 妯″紡鍙傛暟 ====================

    fun updateManualParameters(update: (ManualParameters) -> ManualParameters) {
        val current = _cameraState.value.manualParameters
        val updated = update(current)
        _cameraState.value = _cameraState.value.copy(manualParameters = updated)
        
        // 灏嗗弬鏁板簲鐢ㄥ埌鐩告満浼氳瘽
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

    // ==================== 鎷嶇収 ====================
    
    /**
     * 澶勭悊鍚庣殑鐓х墖鏁版嵁锛堝寘鍚柟鍚戜俊鎭級
     */
    private data class ProcessedPhoto(
        val data: ByteArray,
        val orientation: Int = ExifInterface.ORIENTATION_NORMAL
    )

    /**
     * 鎷嶇収 - 浣跨敤寮傛鍚庡彴澶勭悊
     * 
     * 娴佺▼锛?
     * 1. 绔嬪嵆鎹曡幏鐓х墖鏁版嵁
     * 2. 灏嗗鐞嗕换鍔℃彁浜ゅ埌鍚庡彴闃熷垪
     * 3. 绔嬪嵆杩斿洖璁╃敤鎴峰彲浠ョ户缁媿鐓?
     * 4. 鍚庡彴瀹屾垚澶勭悊鍚庤嚜鍔ㄤ繚瀛樺埌鐩稿唽
     */
    fun capturePhoto() {
        hapticFeedback.heavyClick()
        _cameraState.value = _cameraState.value.copy(isCapturing = true)

        viewModelScope.launch {
            try {
                // 浣跨敤 CameraSessionManager 鎷嶇収锛堝畠宸茬粡鎵撳紑浜嗙浉鏈猴級
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val captureStart = System.currentTimeMillis()
                    val photoData = cameraSessionManager.capturePhoto(
                        flashMode = _cameraState.value.flashMode
                    )
                    Timber.d("Photo captured in ${System.currentTimeMillis() - captureStart}ms, size: ${photoData.size} bytes")
                    
                    // 鏀堕泦褰撳墠鐨勫鐞嗗弬鏁帮紙鍦ㄤ富绾跨▼鎹曡幏锛岄伩鍏嶇珵鎬佹潯浠讹級
                    val selectedLut = _cameraState.value.selectedLut ?: run {
                        val lutId = _cameraState.value.currentLutId
                        if (lutId != null) lutManager.lutFilters.value.find { it.id == lutId } else null
                    }
                    val lutIntensity = _cameraState.value.lutIntensity / 100f
                    val colorPalette = _cameraState.value.colorPalette
                    val watermarkEnabled = settings.value.watermarkEnabled
                    val watermarkPosition = settings.value.watermarkPosition
                    val isLivePhoto = _cameraState.value.isLivePhotoEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    
                    // 鎻愪氦鍒板悗鍙板鐞嗛槦鍒?- 绔嬪嵆杩斿洖
                    photoProcessingQueue.submit(
                        photoData = photoData,
                        selectedLut = selectedLut,
                        lutIntensity = lutIntensity,
                        colorPalette = colorPalette,
                        watermarkEnabled = watermarkEnabled,
                        watermarkPosition = watermarkPosition,
                        isLivePhoto = isLivePhoto
                    ) { result ->
                        // 澶勭悊瀹屾垚鍥炶皟锛堝湪鍚庡彴绾跨▼鎵ц锛?
                        handleProcessedPhoto(result, isLivePhoto)
                    }
                    
                    // 蹇€熷弽棣?- 鎷嶇収瀹屾垚锛堝鐞嗗湪鍚庡彴缁х画锛?
                    hapticFeedback.success()
                    Timber.d("Photo submitted for background processing")
                    
                } else {
                    Timber.w("Camera capture requires Android P or higher")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to capture photo")
                hapticFeedback.error()
            } finally {
                // 绔嬪嵆閲嶇疆鎷嶇収鐘舵€侊紝璁╃敤鎴峰彲浠ュ揩閫熻繛缁媿鐓?
                _cameraState.value = _cameraState.value.copy(isCapturing = false)
            }
        }
    }
    
    /**
     * 澶勭悊瀹屾垚鐨勭収鐗囷紙鍦ㄥ悗鍙伴槦鍒椾腑璋冪敤锛?
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
     * 搴旂敤 LUT 婊ら暅鍜岃皟鑹茬洏鍙傛暟鍒扮収鐗囷紝鍚屾椂淇濈暀 EXIF 鏂瑰悜淇℃伅
     * 浣跨敤鍚庡彴绾跨▼澶勭悊浠ラ伩鍏?ANR
     */
    private suspend fun applyLutToPhoto(photoData: ByteArray): ProcessedPhoto = withContext(Dispatchers.Default) {
        // 鍏堜粠鍘熷 JPEG 璇诲彇鏂瑰悜淇℃伅
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
        
        // 浼樺厛浣跨敤 selectedLut锛屽鏋滀负 null 鍒欏皾璇曢€氳繃 currentLutId 鏌ユ壘
        var selectedLut = _cameraState.value.selectedLut
        if (selectedLut == null) {
            val lutId = _cameraState.value.currentLutId
            if (lutId != null) {
                selectedLut = lutManager.lutFilters.value.find { it.id == lutId }
            }
        }
        
        val lutIntensity = _cameraState.value.lutIntensity / 100f  // 杞崲涓?0-1 鑼冨洿
        val colorPalette = _cameraState.value.colorPalette
        val hasColorAdjustments = !colorPalette.isDefault()
        
        // 濡傛灉娌℃湁閫変腑 LUT 涓旀病鏈夎皟鑹插弬鏁帮紝鐩存帴杩斿洖鍘熷浘
        if ((selectedLut == null || lutIntensity <= 0f) && !hasColorAdjustments) {
            Timber.d("No LUT selected and no color adjustments, returning original photo")
            return@withContext ProcessedPhoto(photoData, originalOrientation)
        }
        
        try {
            Timber.d("Applying effects - LUT: ${selectedLut?.name}, ColorPalette: temp=${colorPalette.temperatureKelvin}K, sat=${colorPalette.saturation}, tone=${colorPalette.tone}")
            
            // 瑙ｇ爜 JPEG 涓?Bitmap
            val startDecode = System.currentTimeMillis()
            var bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size)
            if (bitmap == null) {
                Timber.e("Failed to decode photo data to bitmap")
                return@withContext ProcessedPhoto(photoData, originalOrientation)
            }
            Timber.d("Decoded bitmap: ${bitmap.width}x${bitmap.height} in ${System.currentTimeMillis() - startDecode}ms")
            
            // 搴旂敤璋冭壊鐩樺弬鏁?
            if (hasColorAdjustments) {
                val startColor = System.currentTimeMillis()
                Timber.d("Starting color palette processing...")
                bitmap = applyColorPaletteToBitmap(bitmap, colorPalette)
                Timber.d("Color palette applied in ${System.currentTimeMillis() - startColor}ms")
            }
            
            // 搴旂敤 LUT
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
            
            // 搴旂敤姘村嵃锛堝鏋滃惎鐢級- 浼犻€?EXIF 鏂瑰悜浠ョ‘淇濇按鍗颁綅缃纭?
            val watermarkEnabled = settings.value.watermarkEnabled
            if (watermarkEnabled) {
                val startWatermark = System.currentTimeMillis()
                val watermarkPosition = settings.value.watermarkPosition
                bitmap = watermarkRenderer.applyWatermark(bitmap, watermarkPosition, originalOrientation)
                Timber.d("Watermark applied in ${System.currentTimeMillis() - startWatermark}ms")
            }
            
            // 缂栫爜鍥?JPEG
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
     * 搴旂敤璋冭壊鐩樺弬鏁板埌 Bitmap锛圕PU 澶勭悊锛岀敤浜庢媿鐓э級
     * 
     * 涓?GPU Shader (ColorPaletteShader) 绠楁硶淇濇寔涓€鑷达細
     * 1. 鑹叉俯锛氫娇鐢?RGB 涔樻暟
     * 2. 楗卞拰搴︼細浣跨敤 HSL 棰滆壊绌洪棿璋冩暣
     * 3. 鍏夊奖锛氫娇鐢?S 鏇茬嚎璋冩暣瀵规瘮搴?
     * 
     * 浼樺寲锛氫娇鐢ㄩ璁＄畻鐨勬煡鎵捐〃鍔犻€?sRGB <-> Linear 杞崲
     */
    private fun applyColorPaletteToBitmap(input: Bitmap, palette: ColorPalette): Bitmap {
        val width = input.width
        val height = input.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 鑾峰彇鍙傛暟锛堜笌 GPU Shader 瀹屽叏涓€鑷达級
        val targetKelvin = palette.targetKelvin
        val exposureGain = palette.exposureGain
        val saturationAdjust = palette.saturationAdjust
        
        // 璁＄畻鐧藉钩琛?RGB 涔樻暟
        val wbMult = calculateWhiteBalanceMultipliers(targetKelvin)
        val wbR = wbMult[0]
        val wbG = wbMult[1]
        val wbB = wbMult[2]
        
        // 棰勮绠?sRGB -> Linear 鏌ユ壘琛?(256 涓€?
        val srgbToLinearLut = FloatArray(256) { i ->
            val x = i / 255f
            if (x <= 0.04045f) x / 12.92f
            else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        
        // 棰勮绠?Linear -> sRGB 鏌ユ壘琛?(4097 涓€硷紝瑕嗙洊 0-1 鑼冨洿)
        val linearToSrgbLut = FloatArray(4097) { i ->
            val x = i / 4096f
            if (x <= 0.0031308f) x * 12.92f
            else (1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f)
        }
        
        // 浣跨敤鍒嗗潡澶勭悊浼樺寲鎬ц兘锛圓ndroid 涓?Java Stream 鏁堢巼浣庯級
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
            
            // 1. sRGB 鈫?Linear锛堜娇鐢ㄦ煡鎵捐〃锛?
            var r = srgbToLinearLut[ri]
            var g = srgbToLinearLut[gi]
            var b = srgbToLinearLut[bi]
            
            // 2. 鐧藉钩琛★紙鍦?Linear 绌洪棿锛?
            r *= wbR
            g *= wbG
            b *= wbB
            
            // 3. 鏇濆厜澧炵泭锛堝湪 Linear 绌洪棿锛?
            r *= exposureGain
            g *= exposureGain
            b *= exposureGain
            
            // 4. 楗卞拰搴﹁皟鏁达紙OKLab 绌洪棿锛屽唴鑱旇绠楋級
            // === 鍐呰仈 linearRgbToOklab ===
            val lms_l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
            val lms_m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
            val lms_s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
            
            val lCbrt = kotlin.math.cbrt(lms_l.toDouble()).toFloat()
            val mCbrt = kotlin.math.cbrt(lms_m.toDouble()).toFloat()
            val sCbrt = kotlin.math.cbrt(lms_s.toDouble()).toFloat()
            
            val okL = 0.2104542553f * lCbrt + 0.7936177850f * mCbrt - 0.0040720468f * sCbrt
            val okA = 1.9779984951f * lCbrt - 2.4285922050f * mCbrt + 0.4505937099f * sCbrt
            val okB = 0.0259040371f * lCbrt + 0.7827717662f * mCbrt - 0.8086757660f * sCbrt
            
            // === 鍐呰仈 adjustSaturationOklab ===
            val C = kotlin.math.sqrt((okA * okA + okB * okB).toDouble()).toFloat()
            var adjA = okA
            var adjB = okB
            
            if (C >= 0.0001f) {
                // 鍐呰仈 smoothstep 鐢ㄤ簬楂樺厜淇濇姢
                var t = (okL - 0.7f) / 0.3f
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val highlightProtection = 1f - t * t * (3f - 2f * t)
                
                // 鍐呰仈 smoothstep 鐢ㄤ簬闃村奖淇濇姢
                t = okL / 0.2f
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val shadowProtection = t * t * (3f - 2f * t)
                
                val effectiveSatAdjust = saturationAdjust * highlightProtection * shadowProtection
                var newC = C * (1f + effectiveSatAdjust)
                
                // 杞鍓?
                if (newC > 0.4f) {
                    newC = 0.4f + (newC - 0.4f) / (1f + (newC - 0.4f))
                }
                if (newC < 0f) newC = 0f else if (newC > 0.5f) newC = 0.5f
                
                val scale = newC / C
                adjA = okA * scale
                adjB = okB * scale
            }
            
            // === 鍐呰仈 oklabToLinearRgb ===
            val lCbrt2 = okL + 0.3963377774f * adjA + 0.2158037573f * adjB
            val mCbrt2 = okL - 0.1055613458f * adjA - 0.0638541728f * adjB
            val sCbrt2 = okL - 0.0894841775f * adjA - 1.2914855480f * adjB
            
            val ll = lCbrt2 * lCbrt2 * lCbrt2
            val mm = mCbrt2 * mCbrt2 * mCbrt2
            val ss = sCbrt2 * sCbrt2 * sCbrt2
            
            r = 4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss
            g = -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss
            b = -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss
            
            // 5. Linear 鈫?sRGB锛堜娇鐢ㄦ煡鎵捐〃锛屽唴鑱旇竟鐣屾鏌ワ級
            var rIdx = (r * 4096f).toInt()
            var gIdx = (g * 4096f).toInt()
            var bIdx = (b * 4096f).toInt()
            if (rIdx < 0) rIdx = 0 else if (rIdx > 4096) rIdx = 4096
            if (gIdx < 0) gIdx = 0 else if (gIdx > 4096) gIdx = 4096
            if (bIdx < 0) bIdx = 0 else if (bIdx > 4096) bIdx = 4096
            
            val rSrgb = linearToSrgbLut[rIdx]
            val gSrgb = linearToSrgbLut[gIdx]
            val bSrgb = linearToSrgbLut[bIdx]
            
            // 杞崲鍥?0-255 鑼冨洿
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
        
        // 鍚姩鎵€鏈夌嚎绋?
        threads.forEach { it.start() }
        // 绛夊緟鎵€鏈夌嚎绋嬪畬鎴?
        threads.forEach { it.join() }
        
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        
        if (input != output) {
            input.recycle()
        }
        
        return output
    }
    
    // ============= 棰滆壊绌洪棿杞崲鍑芥暟锛堜笌 GPU Shader 瀹屽叏涓€鑷达級=============
    
    /**
     * sRGB 鈫?Linear锛堢簿纭浆鎹紝涓?GPU Shader 涓€鑷达級
     */
    private fun srgbToLinear(x: Float): Float {
        return if (x <= 0.04045f) {
            x / 12.92f
        } else {
            ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }
    
    /**
     * Linear 鈫?sRGB锛堢簿纭浆鎹紝涓?GPU Shader 涓€鑷达級
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
     * Linear RGB 鈫?OKLab锛堜笌 GPU Shader 涓€鑷达級
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
     * OKLab 鈫?Linear RGB锛堜笌 GPU Shader 涓€鑷达級
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
     * OKLab 绌洪棿楗卞拰搴﹁皟鏁达紙甯﹂珮鍏?闃村奖淇濇姢锛屼笌 GPU Shader 涓€鑷达級
     */
    private fun adjustSaturationOklab(oklab: FloatArray, satAdjust: Float): FloatArray {
        val L = oklab[0]
        val a = oklab[1]
        val b = oklab[2]
        
        // 璁＄畻鑹插害 C
        val C = kotlin.math.sqrt((a * a + b * b).toDouble()).toFloat()
        if (C < 0.0001f) {
            return oklab // 鐏拌壊锛屾棤闇€璋冩暣
        }
        
        // 楂樺厜淇濇姢锛氫寒搴﹁秺楂橈紝楗卞拰搴﹁皟鏁磋秺寮?
        val highlightProtection = 1f - smoothstep(0.7f, 1.0f, L)
        // 闃村奖淇濇姢锛氫寒搴﹁秺浣庯紝楗卞拰搴﹁皟鏁磋秺寮?
        val shadowProtection = smoothstep(0.0f, 0.2f, L)
        
        // 璁＄畻鏈夋晥楗卞拰搴﹁皟鏁撮噺
        val effectiveSatAdjust = satAdjust * highlightProtection * shadowProtection
        
        // 搴旂敤楗卞拰搴﹁皟鏁?
        var newC = C * (1f + effectiveSatAdjust)
        
        // 杞鍓槻姝㈣繃楗卞拰锛堜笌 GPU Shader 涓€鑷达級
        if (newC > 0.4f) {
            newC = 0.4f + (newC - 0.4f) / (1f + (newC - 0.4f))
        }
        
        newC = newC.coerceIn(0f, 0.5f)
        
        val scale = if (C > 0.0001f) newC / C else 1f
        
        return floatArrayOf(L, a * scale, b * scale)
    }
    
    /**
     * 骞虫粦闃舵鍑芥暟锛堜笌 GPU Shader 涓€鑷达級
     */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    
    /**
     * 璁＄畻鐧藉钩琛?RGB 涔樻暟锛堜笌 GPU Shader kelvinToRgbMultipliers 瀹屽叏涓€鑷达級
     * 
     * 浣跨敤 Tanner Helland 绠楁硶璁＄畻鑹叉俯瀵瑰簲鐨?RGB 鍊硷紝鐒跺悗浠?5500K 涓哄熀鍑嗗綊涓€鍖?
     */
    private fun calculateWhiteBalanceMultipliers(targetKelvin: Float): FloatArray {
        val temp = targetKelvin / 100f
        val rgb = FloatArray(3)
        
        // 绾㈣壊鍒嗛噺
        rgb[0] = if (temp <= 66f) {
            1f
        } else {
            (1.292936186f * Math.pow((temp - 60.0).toDouble(), -0.1332047592).toFloat()).coerceIn(0f, 1f)
        }
        
        // 缁胯壊鍒嗛噺
        rgb[1] = if (temp <= 66f) {
            (0.3900815788f * Math.log(temp.toDouble()).toFloat() - 0.6318414438f).coerceIn(0f, 1f)
        } else {
            (1.129890861f * Math.pow((temp - 60.0).toDouble(), -0.0755148492).toFloat()).coerceIn(0f, 1f)
        }
        
        // 钃濊壊鍒嗛噺
        rgb[2] = if (temp >= 66f) {
            1f
        } else if (temp <= 19f) {
            0f
        } else {
            (0.5432067891f * Math.log((temp - 10f).toDouble()).toFloat() - 1.1962540892f).coerceIn(0f, 1f)
        }
        
        // 褰掍竴鍖栵紙浠?5500K 涓哄熀鍑嗭紝涓?GPU Shader 涓€鑷达級
        val baseRgb = floatArrayOf(1f, 0.94f, 0.91f) // 5500K 杩戜技鍊?
        return floatArrayOf(
            baseRgb[0] / rgb[0].coerceAtLeast(0.001f),
            baseRgb[1] / rgb[1].coerceAtLeast(0.001f),
            baseRgb[2] / rgb[2].coerceAtLeast(0.001f)
        )
    }

    /**
     * 淇濆瓨鐓х墖鍒扮浉鍐岋紙甯︽柟鍚戜俊鎭級
     * 鎵€鏈?I/O 鎿嶄綔鍦?IO 璋冨害鍣ㄦ墽琛?
     */
    private suspend fun savePhotoToGallery(processedPhoto: ProcessedPhoto) = withContext(Dispatchers.IO) {
        Timber.d("savePhotoToGallery started, data size: ${processedPhoto.data.size} bytes")
        try {
            // 鑾峰彇璁剧疆鐨勮緭鍑烘牸寮?
            val outputFormat = settings.value.outputFormat
            val isLumaLogEnabled = settings.value.lumaLogEnabled
            
            // 鏍规嵁杈撳嚭鏍煎紡纭畾 MIME 绫诲瀷鍜屾枃浠舵墿灞曞悕
            val (mimeType, extension) = when (outputFormat) {
                OutputFormat.JPEG -> "image/jpeg" to "jpg"
                OutputFormat.HEIF -> "image/heif" to "heic"
                OutputFormat.RAW_DNG -> "image/x-adobe-dng" to "dng"
                OutputFormat.RAW_JPEG -> "image/jpeg" to "jpg" // RAW+JPEG 鍏堜繚瀛?JPEG锛孯AW 鍙﹀瓨
            }
            
            // 鐢熸垚鏂囦欢鍚嶏細LUMA_20260105_143052
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val displayName = "LUMA_$timestamp"

            Timber.d("Saving photo: $displayName with format: $outputFormat ($mimeType), orientation: ${processedPhoto.orientation}, lumaLog: $isLumaLogEnabled")

            // 濡傛灉鏄?HEIF 鏍煎紡锛岄渶瑕侀噸鏂扮紪鐮?
            val finalData = when (outputFormat) {
                OutputFormat.HEIF -> {
                    // 灏?JPEG 瑙ｇ爜骞堕噸鏂扮紪鐮佷负 HEIF
                    val bitmap = BitmapFactory.decodeByteArray(processedPhoto.data, 0, processedPhoto.data.size)
                    val outputStream = ByteArrayOutputStream()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, outputStream)
                        // 娉ㄦ剰锛欰ndroid 鍘熺敓涓嶇洿鎺ユ敮鎸?HEIF 缂栫爜锛岃繖閲岀敤 WEBP 浣滀负鏇夸唬
                        // 瀹為檯涓婇渶瑕佷娇鐢?ImageWriter 鎴栫涓夋柟搴撴潵缂栫爜 HEIF
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                    bitmap.recycle()
                    outputStream.toByteArray()
                }
                else -> processedPhoto.data
            }

            // 鍒涘缓鏂囦欢
            val result = mediaStoreHelper.createImageFile(
                displayName = displayName,
                mimeType = mimeType
            )

            result.onSuccess { (uri, outputStream) ->
                // 鍐欏叆鏁版嵁
                outputStream.use { stream ->
                    stream.write(finalData)
                    stream.flush()
                }

                // 瀹屾垚鍐欏叆
                mediaStoreHelper.finishPendingFile(uri)

                // 鍐欏叆 EXIF 鏂瑰悜淇℃伅锛堥渶瑕佸湪鏂囦欢鍐欏叆鍚庢搷浣滐級
                try {
                    // 閫氳繃 ContentResolver 鑾峰彇鏂囦欢鎻忚堪绗︽潵鍐欏叆 EXIF
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

                // 鏇存柊鍏冩暟鎹?
                mediaStoreHelper.updateImageMetadata(
                    uri = uri,
                    dateTaken = System.currentTimeMillis()
                )

                Timber.d("Photo saved successfully: $uri")
                
                // 濡傛灉鍚敤浜?LumaLog锛屼繚瀛?RAW 鎴栫伆鐗?
                if (isLumaLogEnabled) {
                    if (cameraSessionManager.isRawCaptureSupported()) {
                        // 璁惧鏀寔 RAW锛屼繚瀛?DNG 鏍煎紡
                        saveLumaLogImage(timestamp, _cameraState.value.flashMode)
                    } else {
                        // 璁惧涓嶆敮鎸?RAW锛屼繚瀛樼伆鐗?
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
     * 淇濆瓨 LumaLog RAW 鍥惧儚锛圖NG 鏍煎紡锛屼繚鐣欏叏閮ㄤ紶鎰熷櫒缁嗚妭锛?
     * 濡傛灉璁惧涓嶆敮鎸?RAW锛屽垯淇濆瓨鐏扮墖锛團lat Profile锛変綔涓哄閫?
     */
    private suspend fun saveLumaLogImage(timestamp: String, flashMode: FlashMode) {
        try {
            // 妫€鏌ヨ澶囨槸鍚︽敮鎸?RAW 鎹曡幏
            if (cameraSessionManager.isRawCaptureSupported()) {
                Timber.d("Capturing LumaLog RAW (DNG)...")
                saveLumaLogRawImage(timestamp, flashMode)
            } else {
                Timber.d("Device does not support RAW, saving flat profile instead...")
                // 璁惧涓嶆敮鎸?RAW锛屼娇鐢ㄥ綋鍓嶇収鐗囩敓鎴愮伆鐗?
                // 杩欓儴鍒嗗湪 savePhotoToGallery 涓鐞?
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save LumaLog image")
        }
    }
    
    /**
     * 淇濆瓨 LumaLog RAW 鍥惧儚锛圖NG 鏍煎紡锛?
     */
    private suspend fun saveLumaLogRawImage(timestamp: String, flashMode: FlashMode) {
        try {
            // 鎹曡幏 RAW 鍥惧儚
            val rawResult = cameraSessionManager.captureRawPhoto(flashMode)
            
            try {
                val displayName = "LUMA_${timestamp}_RAW"
                
                // 鍒涘缓 DNG 鏂囦欢
                val dngResult = mediaStoreHelper.createImageFile(
                    displayName = displayName,
                    mimeType = MediaStoreHelper.MIME_TYPE_DNG
                )
                
                dngResult.onSuccess { (uri, outputStream) ->
                    // 鍐欏叆 DNG 鏁版嵁
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
                // 蹇呴』鍏抽棴 RAW 鍥惧儚
                rawResult.rawImage.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to capture LumaLog RAW")
        }
    }

    /**
     * 淇濆瓨 LumaLog 鐏扮墖锛堜繚鐣欏叏閮ㄧ粏鑺傜殑浣庡姣斿害/浣庨ケ鍜屽害鍥惧儚锛?
     * 鐢ㄤ簬涓嶆敮鎸?RAW 鐨勮澶?
     */
    private suspend fun saveLumaLogFlatImage(processedPhoto: ProcessedPhoto, timestamp: String) {
        try {
            Timber.d("Generating LumaLog flat image...")
            
            // 瑙ｇ爜鍘熷鍥惧儚
            val originalBitmap = BitmapFactory.decodeByteArray(
                processedPhoto.data, 0, processedPhoto.data.size
            ) ?: run {
                Timber.w("Failed to decode photo for LumaLog processing")
                return
            }
            
            // 浣跨敤 FlatProfileGenerator 鐢熸垚鐏扮墖
            val flatImage = flatProfileGenerator.generate(originalBitmap)
            
            // 缂栫爜涓?JPEG
            val flatOutputStream = ByteArrayOutputStream()
            flatImage.compress(Bitmap.CompressFormat.JPEG, 100, flatOutputStream)
            val flatData = flatOutputStream.toByteArray()
            
            // 鐢熸垚鐏扮墖鏂囦欢鍚?
            val flatDisplayName = "LUMA_${timestamp}_FLAT"
            
            // 淇濆瓨鐏扮墖
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
                
                // 鍐欏叆 EXIF 鏂瑰悜淇℃伅
                try {
                    val pfd = mediaStoreHelper.getContentResolver().openFileDescriptor(flatUri, "rw")
                    pfd?.use { fd ->
                        val exif = ExifInterface(fd.fileDescriptor)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            processedPhoto.orientation.toString()
                        )
                        // 娣诲姞鑷畾涔夋爣绛炬爣璇嗚繖鏄?LumaLog 鐏扮墖
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
            
            // 娓呯悊
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
     * 鎹曡幏骞朵繚瀛樺疄鍐电収鐗?
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureLivePhotoWithData(photoData: ByteArray, orientation: Int = ExifInterface.ORIENTATION_NORMAL) = withContext(Dispatchers.IO) {
        try {
            Timber.d("Capturing live photo...")
            
            // 瑙ｇ爜鐓х墖涓?Bitmap
            val bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size)
            if (bitmap == null) {
                Timber.e("Failed to decode photo for live photo")
                // 鍥為€€鍒版櫘閫氱収鐗囦繚瀛?
                savePhotoToGallery(ProcessedPhoto(photoData, orientation))
                return@withContext
            }
            
            // 鑾峰彇杈撳嚭鐩綍
            val outputDir = mediaStoreHelper.getLivePhotoOutputDir()
            
            // 璋冪敤 LivePhotoManager 鎹曡幏瀹炲喌鐓х墖
            val result = livePhotoManager.captureLivePhoto(
                photo = bitmap,
                outputDir = outputDir
            )
            
            result.onSuccess { livePhotoResult ->
                Timber.d("Live photo captured: ${livePhotoResult.photoFile}, ${livePhotoResult.videoFile}")
                // 灏嗘枃浠舵坊鍔犲埌濯掍綋搴?
                mediaStoreHelper.addLivePhotoToMediaStore(
                    photoFile = livePhotoResult.photoFile,
                    videoFile = livePhotoResult.videoFile
                )
            }.onFailure { error ->
                Timber.e(error, "Failed to capture live photo, falling back to normal photo")
                // 鍥為€€鍒版櫘閫氱収鐗囦繚瀛?
                savePhotoToGallery(ProcessedPhoto(photoData, orientation))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error capturing live photo")
            // 鍥為€€鍒版櫘閫氱収鐗囦繚瀛?
            savePhotoToGallery(ProcessedPhoto(photoData, orientation))
        }
    }

    // ==================== 瑙︽懜瀵圭劍 ====================

    fun onTouchFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        hapticFeedback.tick()
        if (viewWidth <= 0 || viewHeight <= 0) {
            Timber.w("Touch focus ignored: invalid view size ${viewWidth}x${viewHeight}")
            return
        }
        viewModelScope.launch {
            try {
                cameraSessionManager.triggerTouchFocus(x, y, viewWidth, viewHeight)
                Timber.d("Touch focus triggered at x=$x, y=$y")
            } catch (e: Exception) {
                Timber.e(e, "Failed to trigger touch focus")
            }
        }
    }

    fun onLongPress(x: Float, y: Float) {
        // 闀挎寜閿佸畾 AE/AF
        toggleAeLock()
        toggleAfLock()
    }

    // ==================== 鐢熷懡鍛ㄦ湡 ====================
    
    // 淇濆瓨鐨勯瑙?Surface锛岀敤浜庢仮澶嶇浉鏈?
    private var savedPreviewSurface: Surface? = null
    
    /**
     * 鏆傚仠鐩告満 - 褰?Activity 杩涘叆鍚庡彴鏃惰皟鐢?
     * 
     * 鍚屾鎵ц浠ョ‘淇濆湪 Activity 鏆傚仠鍓嶅畬鎴?
     */
    fun pauseCamera() {
        Timber.d("Pausing camera...")
        try {
            // 鍋滄瀹炲喌鐓х墖缂撳啿褰曞埗
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                livePhotoManager.stopBuffering()
            }
            
            // 鍋滄 GL 娓叉煋鍣紙鏆傚仠妯″紡锛屼繚鐣?EGL 涓婁笅鏂囷級
            glPreviewRenderer.onPause()
            
            // 鍏抽棴鐩告満浼氳瘽
            cameraSessionManager.closeCamera()
            _cameraState.value = _cameraState.value.copy(isCameraReady = false)
            
            Timber.d("Camera paused successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error pausing camera")
        }
    }
    
    /**
     * 鎭㈠鐩告満 - 褰?Activity 浠庡悗鍙版仮澶嶆椂璋冪敤
     * 
     * 鎭㈠娴佺▼锛?
     * 1. 妫€鏌?GL 娓叉煋鍣ㄦ槸鍚﹂渶瑕侀噸鏂板垵濮嬪寲
     * 2. 濡傛灉闇€瑕侀噸鏂板垵濮嬪寲锛岃Е鍙戝畬鏁寸殑閲嶆柊鍒濆鍖栨祦绋?
     * 3. 濡傛灉涓嶉渶瑕侊紝鐩存帴鎭㈠娓叉煋鍣ㄥ苟閲嶆柊鎵撳紑鐩告満
     */
    fun resumeCamera() {
        Timber.d("resumeCamera called")
        viewModelScope.launch {
            try {
                // 棣栧厛鎭㈠ GL 娓叉煋鍣紙杩欎細妫€娴?EGL Surface 鏄惁鏈夋晥锛?
                glPreviewRenderer.onResume()
                
                // 绛夊緟涓€灏忔鏃堕棿璁╂覆鏌撳櫒瀹屾垚鎭㈠妫€鏌?
                kotlinx.coroutines.delay(50)
                
                // 妫€鏌ユ覆鏌撳櫒鏄惁闇€瑕侀噸鏂板垵濮嬪寲锛圗GL Surface 澶辨晥锛?
                if (glPreviewRenderer.needsReinitialization()) {
                    Timber.d("GL Renderer needs reinitialization, triggering full reinitialization")
                    // 瑙﹀彂瀹屾暣鐨勯噸鏂板垵濮嬪寲娴佺▼
                    // 鍥炶皟鍑芥暟浼氬湪閲嶆柊鍒濆鍖栧畬鎴愬悗鑷姩閲嶆柊鎵撳紑鐩告満
                    glPreviewRenderer.triggerReinitialization()
                    return@launch
                }
                
                // 妫€鏌ユ覆鏌撳櫒鏄惁灏辩华
                if (!glPreviewRenderer.isReady()) {
                    Timber.d("GL Renderer not ready, waiting for reinitialization")
                    shouldResumeCamera = true
                    return@launch
                }
                
                // 妫€鏌ユ槸鍚︽湁鏈夋晥鐨?Surface 鍙敤
                val cameraSurface = glPreviewRenderer.getCameraSurface()
                if (cameraSurface != null && cameraSurface.isValid) {
                    Timber.d("Surface is valid, reopening camera...")
                    // Surface 鏈夋晥锛岀洿鎺ラ噸鏂版墦寮€鐩告満
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val currentFocalLength = _cameraState.value.currentFocalLength
                        val recordingSurface = ensureLivePhotoSurface()
                        val result = cameraSessionManager.openCamera(
                            focalLength = currentFocalLength,
                            previewSurface = cameraSurface,
                            recordingSurface = recordingSurface
                        )
                        result.onSuccess {
                            Timber.d("Camera resumed successfully")
                            _cameraState.value = _cameraState.value.copy(isCameraReady = true)
                            shouldResumeCamera = false
                            applyPreviewEffects()
                            glPreviewRenderer.startRendering()
                            
                            // 濡傛灉瀹炲喌鍔熻兘宸插惎鐢紝鎭㈠缂撳啿褰曞埗
                            if (_cameraState.value.isLivePhotoEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLivePhotoBuffering()
                            }
                        }.onFailure { error ->
                            Timber.e(error, "Failed to resume camera")
                            _cameraState.value = _cameraState.value.copy(isCameraReady = false)
                            // 灏濊瘯璁剧疆鏍囧織绛夊緟閲嶆柊鍒濆鍖?
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
