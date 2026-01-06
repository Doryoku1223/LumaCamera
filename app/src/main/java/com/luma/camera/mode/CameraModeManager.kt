package com.luma.camera.mode

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import com.luma.camera.livephoto.KeyFrameSelector
import com.luma.camera.livephoto.LivePhotoEncoder
import com.luma.camera.livephoto.LivePhotoLutProcessor
import com.luma.camera.livephoto.LivePhotoManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera Mode Manager
 * 
 * 相机模式集成管理器，统一管理所有拍摄模式。
 * 
 * 支持模式:
 * - Auto: 自动模式
 * - Pro: 专业手动模式
 * - Night: 夜景模式
 * - Portrait: 人像模式
 * - LongExposure: 长曝光模式
 * - LivePhoto: 实况照片
 * - Video: 视频模式
 */
@Singleton
class CameraModeManager @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val nightModeProcessor: NightModeProcessor,
    private val portraitModeProcessor: PortraitModeProcessor,
    private val longExposureProcessor: LongExposureProcessor,
    private val timerController: TimerShootingController,
    private val livePhotoManager: LivePhotoManager,
    private val livePhotoEncoder: LivePhotoEncoder,
    private val keyFrameSelector: KeyFrameSelector,
    private val livePhotoLutProcessor: LivePhotoLutProcessor
) {
    companion object {
        private const val TAG = "CameraModeManager"
    }
    
    // 当前模式
    private val _currentMode = MutableStateFlow<CameraMode>(CameraMode.Auto)
    val currentMode: StateFlow<CameraMode> = _currentMode.asStateFlow()
    
    // 模式状态
    private val _modeState = MutableStateFlow<ModeState>(ModeState.Ready)
    val modeState: StateFlow<ModeState> = _modeState.asStateFlow()
    
    // 各模式配置
    private val _nightConfig = MutableStateFlow(NightModeProcessor.NightModeConfig())
    val nightConfig: StateFlow<NightModeProcessor.NightModeConfig> = _nightConfig.asStateFlow()
    
    private val _portraitConfig = MutableStateFlow(PortraitModeProcessor.PortraitConfig())
    val portraitConfig: StateFlow<PortraitModeProcessor.PortraitConfig> = _portraitConfig.asStateFlow()
    
    private val _longExposureConfig = MutableStateFlow(LongExposureProcessor.LongExposureConfig())
    val longExposureConfig: StateFlow<LongExposureProcessor.LongExposureConfig> = _longExposureConfig.asStateFlow()
    
    /**
     * 相机模式
     */
    enum class CameraMode {
        Auto,           // 自动模式
        Pro,            // 专业模式
        Night,          // 夜景模式
        Portrait,       // 人像模式
        LongExposure,   // 长曝光模式
        LivePhoto,      // 实况照片
        Video,          // 视频模式
        SlowMotion,     // 慢动作
        TimeLapse       // 延时摄影
    }
    
    /**
     * 模式状态
     */
    sealed class ModeState {
        object Ready : ModeState()
        object Capturing : ModeState()
        data class Processing(val progress: Float, val stage: String) : ModeState()
        data class Completed(val result: CaptureResult) : ModeState()
        data class Error(val message: String) : ModeState()
    }
    
    /**
     * 拍摄结果
     */
    sealed class CaptureResult {
        data class Photo(
            val bitmap: Bitmap,
            val file: File?,
            val metadata: PhotoMetadata
        ) : CaptureResult()
        
        data class LivePhoto(
            val photoFile: File,
            val videoFile: File,
            val heicFile: File?,
            val metadata: PhotoMetadata
        ) : CaptureResult()
        
        data class Video(
            val file: File,
            val durationMs: Long
        ) : CaptureResult()
        
        data class Burst(
            val photos: List<Bitmap>,
            val bestIndex: Int
        ) : CaptureResult()
    }
    
    /**
     * 照片元数据
     */
    data class PhotoMetadata(
        val width: Int,
        val height: Int,
        val iso: Int,
        val exposureTimeNs: Long,
        val aperture: Float,
        val focalLength: Float,
        val timestamp: Long,
        val mode: CameraMode,
        val processingTimeMs: Long
    )
    
    /**
     * 切换模式
     */
    fun switchMode(mode: CameraMode) {
        if (_currentMode.value == mode) return
        
        // 清理当前模式
        cleanupCurrentMode()
        
        // 切换到新模式
        _currentMode.value = mode
        _modeState.value = ModeState.Ready
        
        // 初始化新模式
        initializeMode(mode)
    }
    
    /**
     * 初始化模式
     */
    private fun initializeMode(mode: CameraMode) {
        when (mode) {
            CameraMode.LivePhoto -> {
                // LivePhoto 需要开始缓冲
            }
            CameraMode.Night -> {
                // 夜景模式可能需要调整相机参数
            }
            CameraMode.Portrait -> {
                // 人像模式启用人脸检测
            }
            CameraMode.LongExposure -> {
                // 长曝光模式准备帧缓冲
            }
            else -> {
                // 其他模式无需特殊初始化
            }
        }
    }
    
    /**
     * 清理当前模式
     */
    private fun cleanupCurrentMode() {
        when (_currentMode.value) {
            CameraMode.LivePhoto -> {
                livePhotoManager.release()
            }
            CameraMode.Night -> {
                nightModeProcessor.reset()
            }
            CameraMode.Portrait -> {
                // 人像模式清理
            }
            CameraMode.LongExposure -> {
                longExposureProcessor.reset()
            }
            else -> {}
        }
    }
    
    /**
     * 更新夜景配置
     */
    fun updateNightConfig(config: NightModeProcessor.NightModeConfig) {
        _nightConfig.value = config
    }
    
    /**
     * 更新人像配置
     */
    fun updatePortraitConfig(config: PortraitModeProcessor.PortraitConfig) {
        _portraitConfig.value = config
    }
    
    /**
     * 更新长曝光配置
     */
    fun updateLongExposureConfig(config: LongExposureProcessor.LongExposureConfig) {
        _longExposureConfig.value = config
    }
    
    /**
     * 处理夜景照片
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun processNightPhoto(
        frames: List<NightModeProcessor.FrameData>
    ): Result<Bitmap> = withContext(ioDispatcher) {
        _modeState.value = ModeState.Capturing
        
        val result = nightModeProcessor.process(frames, _nightConfig.value)
        
        result.fold(
            onSuccess = { bitmap ->
                _modeState.value = ModeState.Completed(
                    CaptureResult.Photo(
                        bitmap = bitmap,
                        file = null,
                        metadata = createMetadata(CameraMode.Night, 0)
                    )
                )
            },
            onFailure = { e ->
                _modeState.value = ModeState.Error(e.message ?: "Night mode processing failed")
            }
        )
        
        result
    }
    
    /**
     * 处理人像照片
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun processPortraitPhoto(
        inputBitmap: Bitmap
    ): Result<PortraitModeProcessor.PortraitResult> = withContext(ioDispatcher) {
        _modeState.value = ModeState.Capturing
        
        val result = portraitModeProcessor.process(inputBitmap, _portraitConfig.value)
        
        result.fold(
            onSuccess = { portraitResult ->
                _modeState.value = ModeState.Completed(
                    CaptureResult.Photo(
                        bitmap = portraitResult.processedImage,
                        file = null,
                        metadata = createMetadata(CameraMode.Portrait, portraitResult.processingTimeMs)
                    )
                )
            },
            onFailure = { e ->
                _modeState.value = ModeState.Error(e.message ?: "Portrait mode processing failed")
            }
        )
        
        result
    }
    
    /**
     * 处理长曝光照片
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun processLongExposurePhoto(
        frames: List<LongExposureProcessor.FrameData>
    ): Result<Bitmap> = withContext(ioDispatcher) {
        _modeState.value = ModeState.Capturing
        
        val result = longExposureProcessor.process(frames, _longExposureConfig.value)
        
        result.fold(
            onSuccess = { bitmap ->
                _modeState.value = ModeState.Completed(
                    CaptureResult.Photo(
                        bitmap = bitmap,
                        file = null,
                        metadata = createMetadata(CameraMode.LongExposure, 0)
                    )
                )
            },
            onFailure = { e ->
                _modeState.value = ModeState.Error(e.message ?: "Long exposure processing failed")
            }
        )
        
        result
    }
    
    /**
     * 创建元数据
     */
    private fun createMetadata(mode: CameraMode, processingTimeMs: Long): PhotoMetadata {
        return PhotoMetadata(
            width = 0,
            height = 0,
            iso = 0,
            exposureTimeNs = 0,
            aperture = 0f,
            focalLength = 0f,
            timestamp = System.currentTimeMillis(),
            mode = mode,
            processingTimeMs = processingTimeMs
        )
    }
    
    /**
     * 获取模式描述
     */
    fun getModeDescription(mode: CameraMode): ModeDescription {
        return when (mode) {
            CameraMode.Auto -> ModeDescription(
                name = "自动",
                description = "智能场景识别，自动调整参数",
                icon = "auto",
                requiresMultiFrame = false
            )
            CameraMode.Pro -> ModeDescription(
                name = "专业",
                description = "完全手动控制 ISO、快门、白平衡",
                icon = "pro",
                requiresMultiFrame = false
            )
            CameraMode.Night -> ModeDescription(
                name = "夜景",
                description = "多帧合成，低光环境最佳效果",
                icon = "night",
                requiresMultiFrame = true
            )
            CameraMode.Portrait -> ModeDescription(
                name = "人像",
                description = "AI 背景虚化，美颜效果",
                icon = "portrait",
                requiresMultiFrame = false
            )
            CameraMode.LongExposure -> ModeDescription(
                name = "长曝光",
                description = "光轨、丝绢水流、ND 模拟",
                icon = "long_exposure",
                requiresMultiFrame = true
            )
            CameraMode.LivePhoto -> ModeDescription(
                name = "实况",
                description = "拍摄动态照片，记录精彩瞬间",
                icon = "live_photo",
                requiresMultiFrame = true
            )
            CameraMode.Video -> ModeDescription(
                name = "视频",
                description = "录制高质量视频",
                icon = "video",
                requiresMultiFrame = true
            )
            CameraMode.SlowMotion -> ModeDescription(
                name = "慢动作",
                description = "高帧率录制，慢速回放",
                icon = "slow_motion",
                requiresMultiFrame = true
            )
            CameraMode.TimeLapse -> ModeDescription(
                name = "延时",
                description = "间隔拍摄，时间压缩效果",
                icon = "time_lapse",
                requiresMultiFrame = true
            )
        }
    }
    
    /**
     * 模式描述
     */
    data class ModeDescription(
        val name: String,
        val description: String,
        val icon: String,
        val requiresMultiFrame: Boolean
    )
    
    /**
     * 获取可用模式列表
     */
    fun getAvailableModes(): List<CameraMode> {
        return listOf(
            CameraMode.Auto,
            CameraMode.Pro,
            CameraMode.Night,
            CameraMode.Portrait,
            CameraMode.LongExposure,
            CameraMode.LivePhoto,
            CameraMode.Video
        )
    }
    
    /**
     * 检查模式是否可用
     */
    fun isModeAvailable(mode: CameraMode): Boolean {
        // 可以根据设备能力判断
        return true
    }
    
    /**
     * 获取定时器控制器
     */
    fun getTimerController(): TimerShootingController = timerController
    
    /**
     * 释放资源
     */
    fun release() {
        cleanupCurrentMode()
        timerController.release()
        portraitModeProcessor.release()
        keyFrameSelector.release()
    }
}

/**
 * Mode Transition Animator
 * 
 * 模式切换动画控制器
 */
class ModeTransitionAnimator {
    
    /**
     * 过渡动画类型
     */
    enum class TransitionType {
        FADE,           // 淡入淡出
        SLIDE,          // 滑动
        SCALE,          // 缩放
        MORPH           // 变形
    }
    
    /**
     * 计算过渡进度
     */
    fun calculateProgress(
        startTime: Long,
        durationMs: Long = 300
    ): Float {
        val elapsed = System.currentTimeMillis() - startTime
        return (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
    }
    
    /**
     * 缓动函数
     */
    fun easeInOut(t: Float): Float {
        return if (t < 0.5f) {
            2 * t * t
        } else {
            -1 + (4 - 2 * t) * t
        }
    }
}
