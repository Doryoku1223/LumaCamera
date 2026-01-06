package com.luma.camera.mode

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Timer & Interval Shooting Controller
 * 
 * 定时拍摄控制器，支持:
 * 1. 倒计时拍摄 (2s, 5s, 10s)
 * 2. 间隔拍摄 (延时摄影)
 * 3. 自动包围曝光 (AEB)
 * 4. 语音控制拍摄
 * 5. 连拍模式
 */
@Singleton
class TimerShootingController @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "TimerShootingController"
        
        // 预设倒计时
        val PRESET_TIMERS = listOf(2, 5, 10)
        
        // 间隔拍摄默认值
        private const val DEFAULT_INTERVAL_SECONDS = 5
        private const val DEFAULT_SHOT_COUNT = 10
        private const val MAX_SHOT_COUNT = 999
        
        // 语音关键词
        private val VOICE_TRIGGERS = listOf("拍照", "cheese", "capture", "shoot")
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null
    
    // 状态
    private val _state = MutableStateFlow<TimerState>(TimerState.Idle)
    val state: StateFlow<TimerState> = _state.asStateFlow()
    
    // 事件
    private val _events = MutableSharedFlow<TimerEvent>()
    val events: SharedFlow<TimerEvent> = _events.asSharedFlow()
    
    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    
    init {
        initializeTts()
    }
    
    /**
     * 定时器状态
     */
    sealed class TimerState {
        object Idle : TimerState()
        data class Countdown(val remainingSeconds: Int, val totalSeconds: Int) : TimerState()
        data class IntervalShooting(
            val currentShot: Int,
            val totalShots: Int,
            val nextShotInSeconds: Int
        ) : TimerState()
        data class AebShooting(
            val currentExposure: Int,
            val totalExposures: Int,
            val evOffset: Float
        ) : TimerState()
        object VoiceListening : TimerState()
        data class BurstShooting(val shotCount: Int) : TimerState()
    }
    
    /**
     * 定时器事件
     */
    sealed class TimerEvent {
        object TriggerCapture : TimerEvent()
        data class CountdownTick(val remaining: Int) : TimerEvent()
        object CountdownComplete : TimerEvent()
        data class IntervalComplete(val totalShots: Int) : TimerEvent()
        data class AebCapture(val evOffset: Float, val index: Int) : TimerEvent()
        object AebComplete : TimerEvent()
        object Cancelled : TimerEvent()
        data class Error(val message: String) : TimerEvent()
    }
    
    /**
     * 倒计时配置
     */
    data class CountdownConfig(
        val seconds: Int = 5,
        val playSounds: Boolean = true,
        val speakCountdown: Boolean = false
    )
    
    /**
     * 间隔拍摄配置
     */
    data class IntervalConfig(
        val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
        val shotCount: Int = DEFAULT_SHOT_COUNT,
        val initialDelaySeconds: Int = 0,
        val playShutterSound: Boolean = true
    )
    
    /**
     * AEB 配置
     */
    data class AebConfig(
        val bracketCount: Int = 3,              // 曝光数量 (3, 5, 7)
        val evStep: Float = 1.0f,               // EV 步进 (0.5, 1.0, 2.0)
        val autoMergeHdr: Boolean = false,      // 自动合成 HDR
        val delayBetweenShotsMs: Long = 200     // 拍摄间隔
    )
    
    /**
     * 连拍配置
     */
    data class BurstConfig(
        val maxShots: Int = 10,
        val intervalMs: Long = 100,
        val stopOnRelease: Boolean = true
    )
    
    /**
     * 初始化 TTS
     */
    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }
    
    /**
     * 开始倒计时拍摄
     */
    fun startCountdown(config: CountdownConfig = CountdownConfig()) {
        cancel()
        
        currentJob = scope.launch {
            for (remaining in config.seconds downTo 1) {
                _state.value = TimerState.Countdown(remaining, config.seconds)
                _events.emit(TimerEvent.CountdownTick(remaining))
                
                // 播放音效或语音
                if (config.speakCountdown && ttsReady) {
                    speak(remaining.toString())
                }
                
                delay(1000)
            }
            
            // 触发拍摄
            _events.emit(TimerEvent.TriggerCapture)
            _events.emit(TimerEvent.CountdownComplete)
            _state.value = TimerState.Idle
        }
    }
    
    /**
     * 开始间隔拍摄
     */
    fun startIntervalShooting(config: IntervalConfig = IntervalConfig()) {
        cancel()
        
        currentJob = scope.launch {
            // 初始延迟
            if (config.initialDelaySeconds > 0) {
                delay(config.initialDelaySeconds * 1000L)
            }
            
            for (shot in 1..config.shotCount) {
                // 更新状态
                val nextShotIn = if (shot < config.shotCount) config.intervalSeconds else 0
                _state.value = TimerState.IntervalShooting(shot, config.shotCount, nextShotIn)
                
                // 触发拍摄
                _events.emit(TimerEvent.TriggerCapture)
                
                // 等待间隔
                if (shot < config.shotCount) {
                    for (remaining in config.intervalSeconds downTo 1) {
                        _state.value = TimerState.IntervalShooting(shot, config.shotCount, remaining)
                        delay(1000)
                    }
                }
            }
            
            _events.emit(TimerEvent.IntervalComplete(config.shotCount))
            _state.value = TimerState.Idle
        }
    }
    
    /**
     * 开始 AEB 包围曝光
     */
    fun startAebShooting(config: AebConfig = AebConfig()) {
        cancel()
        
        currentJob = scope.launch {
            // 计算 EV 偏移列表
            val evOffsets = calculateEvOffsets(config.bracketCount, config.evStep)
            
            for ((index, evOffset) in evOffsets.withIndex()) {
                _state.value = TimerState.AebShooting(
                    currentExposure = index + 1,
                    totalExposures = evOffsets.size,
                    evOffset = evOffset
                )
                
                // 发送 AEB 拍摄事件
                _events.emit(TimerEvent.AebCapture(evOffset, index))
                
                // 延迟
                if (index < evOffsets.size - 1) {
                    delay(config.delayBetweenShotsMs)
                }
            }
            
            _events.emit(TimerEvent.AebComplete)
            _state.value = TimerState.Idle
        }
    }
    
    /**
     * 计算 EV 偏移列表
     */
    private fun calculateEvOffsets(count: Int, step: Float): List<Float> {
        val half = count / 2
        return (-half..half).map { it * step }
    }
    
    /**
     * 开始连拍
     */
    fun startBurstShooting(config: BurstConfig = BurstConfig()) {
        cancel()
        
        currentJob = scope.launch {
            var shotCount = 0
            
            while (shotCount < config.maxShots) {
                shotCount++
                _state.value = TimerState.BurstShooting(shotCount)
                _events.emit(TimerEvent.TriggerCapture)
                delay(config.intervalMs)
            }
            
            _state.value = TimerState.Idle
        }
    }
    
    /**
     * 停止连拍
     */
    fun stopBurstShooting() {
        if (_state.value is TimerState.BurstShooting) {
            cancel()
        }
    }
    
    /**
     * 开始语音监听
     */
    fun startVoiceListening() {
        _state.value = TimerState.VoiceListening
        // 实际语音识别需要集成 Speech Recognition API
        // 这里提供基础框架
    }
    
    /**
     * 停止语音监听
     */
    fun stopVoiceListening() {
        if (_state.value is TimerState.VoiceListening) {
            _state.value = TimerState.Idle
        }
    }
    
    /**
     * 处理语音结果
     */
    fun onVoiceResult(text: String) {
        val lowerText = text.lowercase()
        
        for (trigger in VOICE_TRIGGERS) {
            if (lowerText.contains(trigger.lowercase())) {
                scope.launch {
                    _events.emit(TimerEvent.TriggerCapture)
                }
                break
            }
        }
    }
    
    /**
     * 取消当前操作
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        
        scope.launch {
            if (_state.value !is TimerState.Idle) {
                _events.emit(TimerEvent.Cancelled)
            }
            _state.value = TimerState.Idle
        }
    }
    
    /**
     * 语音播放
     */
    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

/**
 * AEB (Auto Exposure Bracketing) Processor
 * 
 * 自动包围曝光处理器
 */
@Singleton
class AebProcessor @Inject constructor() {
    companion object {
        private const val TAG = "AebProcessor"
    }
    
    /**
     * AEB 结果
     */
    data class AebResult(
        val frames: List<AebFrame>,
        val hdrMerged: android.graphics.Bitmap? = null
    )
    
    /**
     * AEB 单帧
     */
    data class AebFrame(
        val bitmap: android.graphics.Bitmap,
        val evOffset: Float,
        val exposureTimeNs: Long,
        val iso: Int,
        val timestamp: Long
    )
    
    /**
     * 生成 AEB 曝光参数
     */
    fun generateAebParameters(
        baseExposureNs: Long,
        baseIso: Int,
        evOffsets: List<Float>
    ): List<ExposureSettings> {
        return evOffsets.map { evOffset ->
            calculateExposureForEv(baseExposureNs, baseIso, evOffset)
        }
    }
    
    /**
     * 计算指定 EV 偏移的曝光参数
     */
    private fun calculateExposureForEv(
        baseExposureNs: Long,
        baseIso: Int,
        evOffset: Float
    ): ExposureSettings {
        // EV 变化 = log2(新曝光/基准曝光)
        // 新曝光 = 基准曝光 * 2^EV
        val exposureMultiplier = 2.0.pow(evOffset.toDouble())
        
        // 优先调整快门速度
        var newExposureNs = (baseExposureNs * exposureMultiplier).toLong()
        var newIso = baseIso
        
        // 检查快门速度限制
        val maxExposureNs = 1_000_000_000L // 1秒
        val minExposureNs = 100_000L       // 0.1ms
        
        if (newExposureNs > maxExposureNs) {
            // 快门已达上限，调整 ISO
            val isoMultiplier = newExposureNs.toDouble() / maxExposureNs
            newIso = (baseIso * isoMultiplier).toInt().coerceIn(100, 12800)
            newExposureNs = maxExposureNs
        } else if (newExposureNs < minExposureNs) {
            // 快门已达下限，调整 ISO
            val isoMultiplier = newExposureNs.toDouble() / minExposureNs
            newIso = (baseIso * isoMultiplier).toInt().coerceIn(100, 12800)
            newExposureNs = minExposureNs
        }
        
        return ExposureSettings(
            exposureTimeNs = newExposureNs,
            iso = newIso,
            evOffset = evOffset
        )
    }
    
    /**
     * 曝光设置
     */
    data class ExposureSettings(
        val exposureTimeNs: Long,
        val iso: Int,
        val evOffset: Float
    )
    
    /**
     * 验证 AEB 结果
     */
    fun validateAebResult(frames: List<AebFrame>): ValidationResult {
        if (frames.isEmpty()) {
            return ValidationResult.Invalid("No frames captured")
        }
        
        // 检查帧数
        val expectedCount = frames.first().evOffset.let { 
            // 推断预期帧数
            frames.size
        }
        
        // 检查曝光差异
        val exposures = frames.map { it.exposureTimeNs }
        val hasVariation = exposures.distinct().size > 1
        
        if (!hasVariation) {
            return ValidationResult.Warning("All frames have same exposure")
        }
        
        // 检查亮度分布
        val brightnessValues = frames.map { frame ->
            calculateAverageBrightness(frame.bitmap)
        }
        
        val hasGoodSpread = brightnessValues.max() - brightnessValues.min() > 50
        
        return if (hasGoodSpread) {
            ValidationResult.Valid(frames.size, brightnessValues)
        } else {
            ValidationResult.Warning("Limited brightness range")
        }
    }
    
    /**
     * 计算平均亮度
     */
    private fun calculateAverageBrightness(bitmap: android.graphics.Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = 10
        
        var totalBrightness = 0L
        var sampleCount = 0
        
        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                sampleCount++
            }
        }
        
        return totalBrightness.toFloat() / sampleCount
    }
    
    /**
     * 验证结果
     */
    sealed class ValidationResult {
        data class Valid(val frameCount: Int, val brightnessValues: List<Float>) : ValidationResult()
        data class Warning(val message: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}

/**
 * 连拍缓冲区
 */
class BurstBuffer(private val maxSize: Int = 30) {
    private val frames = mutableListOf<BurstFrame>()
    private val lock = Any()
    
    /**
     * 连拍帧
     */
    data class BurstFrame(
        val bitmap: android.graphics.Bitmap,
        val timestamp: Long,
        val index: Int
    )
    
    /**
     * 添加帧
     */
    fun addFrame(bitmap: android.graphics.Bitmap, timestamp: Long): Boolean {
        synchronized(lock) {
            if (frames.size >= maxSize) {
                return false
            }
            
            frames.add(BurstFrame(
                bitmap = bitmap,
                timestamp = timestamp,
                index = frames.size
            ))
            return true
        }
    }
    
    /**
     * 获取所有帧
     */
    fun getAllFrames(): List<BurstFrame> {
        synchronized(lock) {
            return frames.toList()
        }
    }
    
    /**
     * 获取最佳帧 (清晰度最高)
     */
    fun getBestFrame(): BurstFrame? {
        synchronized(lock) {
            return frames.maxByOrNull { calculateSharpness(it.bitmap) }
        }
    }
    
    /**
     * 计算清晰度
     */
    private fun calculateSharpness(bitmap: android.graphics.Bitmap): Float {
        // 简化的 Laplacian 方差计算
        val width = bitmap.width
        val height = bitmap.height
        val step = 4
        
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        
        for (y in step until height - step step step) {
            for (x in step until width - step step step) {
                val center = getBrightness(bitmap, x, y)
                val laplacian = 4 * center -
                    getBrightness(bitmap, x - step, y) -
                    getBrightness(bitmap, x + step, y) -
                    getBrightness(bitmap, x, y - step) -
                    getBrightness(bitmap, x, y + step)
                
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }
        
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return variance.toFloat()
    }
    
    /**
     * 获取亮度
     */
    private fun getBrightness(bitmap: android.graphics.Bitmap, x: Int, y: Int): Float {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
    
    /**
     * 清除缓冲区
     */
    fun clear() {
        synchronized(lock) {
            frames.forEach { it.bitmap.recycle() }
            frames.clear()
        }
    }
    
    /**
     * 帧数
     */
    fun size(): Int {
        synchronized(lock) {
            return frames.size
        }
    }
}
