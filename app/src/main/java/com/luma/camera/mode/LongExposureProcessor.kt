package com.luma.camera.mode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import com.luma.camera.imaging.ColorFidelity
import com.luma.camera.imaging.DynamicRangeOptimizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Long Exposure Mode Processor
 * 
 * 长曝光模式处理器，实现光轨、丝绢水流、ND 滤镜模拟等效果。
 * 
 * 支持模式:
 * 1. 光轨模式 (Light Trails) - 捕获移动光源的轨迹
 * 2. 丝绢水流 (Silky Water) - 平滑化流水效果
 * 3. ND 滤镜模拟 - 虚拟减光镜效果
 * 4. 运动模糊 (Motion Blur) - 创意运动效果
 * 5. 星轨模式 (Star Trails) - 长时间曝光星空
 * 
 * 技术实现:
 * - 多帧叠加合成
 * - 运动区域检测
 * - 自适应曝光调整
 */
@Singleton
class LongExposureProcessor @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val dynamicRangeOptimizer: DynamicRangeOptimizer,
    private val colorFidelity: ColorFidelity
) {
    companion object {
        private const val TAG = "LongExposureProcessor"
        
        // 帧数配置
        private const val MIN_FRAMES_LIGHT_TRAILS = 30
        private const val MIN_FRAMES_SILKY_WATER = 60
        private const val MIN_FRAMES_STAR_TRAILS = 100
        
        // 性能
        private const val MAX_CONCURRENT_PROCESSING = 4
    }
    
    // 处理状态
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()
    
    /**
     * 处理状态
     */
    sealed class ProcessingState {
        object Idle : ProcessingState()
        data class Capturing(val currentFrame: Int, val totalFrames: Int) : ProcessingState()
        data class Processing(val progress: Float, val stage: String) : ProcessingState()
        data class Completed(val result: Bitmap, val processingTimeMs: Long) : ProcessingState()
        data class Error(val message: String) : ProcessingState()
    }
    
    /**
     * 长曝光模式
     */
    enum class LongExposureMode {
        LIGHT_TRAILS,   // 光轨
        SILKY_WATER,    // 丝绢水流
        ND_FILTER,      // ND 滤镜
        MOTION_BLUR,    // 运动模糊
        STAR_TRAILS     // 星轨
    }
    
    /**
     * 长曝光配置
     */
    data class LongExposureConfig(
        val mode: LongExposureMode = LongExposureMode.LIGHT_TRAILS,
        val exposureDurationSeconds: Float = 4f,        // 等效曝光时长
        val frameRate: Int = 30,                        // 帧率
        val blendStrength: Float = 0.5f,                // 混合强度
        val motionSensitivity: Float = 0.3f,            // 运动敏感度
        val ndStops: Int = 3,                           // ND 档数 (仅 ND 模式)
        val preserveStaticBackground: Boolean = true,    // 保持静态背景清晰
        val ghostReduction: Boolean = true              // 鬼影消除
    )
    
    /**
     * 帧数据
     */
    data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Long,
        val exposureTimeNs: Long,
        val iso: Int
    )
    
    /**
     * 处理长曝光图像
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun process(
        frames: List<FrameData>,
        config: LongExposureConfig = LongExposureConfig()
    ): Result<Bitmap> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        
        try {
            if (frames.size < getMinFrameCount(config.mode)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Not enough frames: ${frames.size}")
                )
            }
            
            _processingState.value = ProcessingState.Processing(0f, "Analyzing frames")
            
            // 根据模式选择处理方法
            val result = when (config.mode) {
                LongExposureMode.LIGHT_TRAILS -> processLightTrails(frames, config)
                LongExposureMode.SILKY_WATER -> processSilkyWater(frames, config)
                LongExposureMode.ND_FILTER -> processNdFilter(frames, config)
                LongExposureMode.MOTION_BLUR -> processMotionBlur(frames, config)
                LongExposureMode.STAR_TRAILS -> processStarTrails(frames, config)
            }
            
            _processingState.value = ProcessingState.Processing(0.9f, "Finalizing")
            
            // 后处理
            val finalResult = applyPostProcessing(result, config)
            
            val processingTime = System.currentTimeMillis() - startTime
            _processingState.value = ProcessingState.Completed(finalResult, processingTime)
            
            Result.success(finalResult)
            
        } catch (e: Exception) {
            _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * 获取模式所需的最小帧数
     */
    private fun getMinFrameCount(mode: LongExposureMode): Int = when (mode) {
        LongExposureMode.LIGHT_TRAILS -> MIN_FRAMES_LIGHT_TRAILS
        LongExposureMode.SILKY_WATER -> MIN_FRAMES_SILKY_WATER
        LongExposureMode.ND_FILTER -> 30
        LongExposureMode.MOTION_BLUR -> 15
        LongExposureMode.STAR_TRAILS -> MIN_FRAMES_STAR_TRAILS
    }
    
    /**
     * 光轨模式处理
     * 使用亮度最大值合成 (Lighten blend)
     */
    private suspend fun processLightTrails(
        frames: List<FrameData>,
        config: LongExposureConfig
    ): Bitmap = coroutineScope {
        val width = frames[0].bitmap.width
        val height = frames[0].bitmap.height
        
        // 并行提取像素
        val pixelArrays = frames.mapIndexed { index, frame ->
            async {
                IntArray(width * height).also { pixels ->
                    frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                }
            }
        }.awaitAll()
        
        // 使用亮度最大值合成
        val result = IntArray(width * height)
        
        for (i in 0 until width * height) {
            var maxBrightness = 0
            var maxPixel = pixelArrays[0][i]
            
            for (pixels in pixelArrays) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                
                if (brightness > maxBrightness) {
                    maxBrightness = brightness
                    maxPixel = pixel
                }
            }
            
            result[i] = maxPixel
            
            if (i % (width * height / 10) == 0) {
                _processingState.value = ProcessingState.Processing(
                    0.1f + 0.6f * i / (width * height),
                    "Compositing light trails"
                )
            }
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(result, 0, width, 0, 0, width, height)
        bitmap
    }
    
    /**
     * 丝绢水流模式处理
     * 使用加权平均合成 + 运动区域检测
     */
    private suspend fun processSilkyWater(
        frames: List<FrameData>,
        config: LongExposureConfig
    ): Bitmap = coroutineScope {
        val width = frames[0].bitmap.width
        val height = frames[0].bitmap.height
        
        // 计算参考帧 (中间帧)
        val referenceIndex = frames.size / 2
        val referencePixels = IntArray(width * height)
        frames[referenceIndex].bitmap.getPixels(referencePixels, 0, width, 0, 0, width, height)
        
        // 检测运动区域
        val motionMask = if (config.preserveStaticBackground) {
            detectMotionRegions(frames, referenceIndex, config.motionSensitivity)
        } else {
            FloatArray(width * height) { 1f } // 全部运动
        }
        
        _processingState.value = ProcessingState.Processing(0.3f, "Blending water")
        
        // 提取所有帧像素
        val pixelArrays = frames.map { frame ->
            IntArray(width * height).also { pixels ->
                frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        
        // 加权平均合成
        val resultR = FloatArray(width * height)
        val resultG = FloatArray(width * height)
        val resultB = FloatArray(width * height)
        
        val weight = 1f / frames.size
        
        for (pixels in pixelArrays) {
            for (i in 0 until width * height) {
                val pixel = pixels[i]
                resultR[i] += ((pixel shr 16) and 0xFF) * weight
                resultG[i] += ((pixel shr 8) and 0xFF) * weight
                resultB[i] += (pixel and 0xFF) * weight
            }
        }
        
        _processingState.value = ProcessingState.Processing(0.6f, "Preserving static regions")
        
        // 混合运动区域和静态区域
        val result = IntArray(width * height)
        for (i in 0 until width * height) {
            val motionWeight = motionMask[i]
            
            // 运动区域使用平均值，静态区域使用参考帧
            val blendedR = resultR[i] * motionWeight + 
                ((referencePixels[i] shr 16) and 0xFF) * (1 - motionWeight)
            val blendedG = resultG[i] * motionWeight + 
                ((referencePixels[i] shr 8) and 0xFF) * (1 - motionWeight)
            val blendedB = resultB[i] * motionWeight + 
                (referencePixels[i] and 0xFF) * (1 - motionWeight)
            
            result[i] = (0xFF shl 24) or 
                (blendedR.toInt().coerceIn(0, 255) shl 16) or 
                (blendedG.toInt().coerceIn(0, 255) shl 8) or 
                blendedB.toInt().coerceIn(0, 255)
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(result, 0, width, 0, 0, width, height)
        bitmap
    }
    
    /**
     * ND 滤镜模式处理
     * 模拟减光镜效果
     */
    private suspend fun processNdFilter(
        frames: List<FrameData>,
        config: LongExposureConfig
    ): Bitmap {
        val width = frames[0].bitmap.width
        val height = frames[0].bitmap.height
        
        // 计算 ND 系数 (每档减少一半光量)
        val ndFactor = 1f / (1 shl config.ndStops)
        
        // 提取所有帧像素
        val pixelArrays = frames.map { frame ->
            IntArray(width * height).also { pixels ->
                frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        
        _processingState.value = ProcessingState.Processing(0.3f, "Simulating ND filter")
        
        // 加权平均合成 (考虑 ND 效果)
        val resultR = DoubleArray(width * height)
        val resultG = DoubleArray(width * height)
        val resultB = DoubleArray(width * height)
        
        val weight = 1.0 / frames.size
        
        for (pixels in pixelArrays) {
            for (i in 0 until width * height) {
                val pixel = pixels[i]
                resultR[i] += ((pixel shr 16) and 0xFF) * weight
                resultG[i] += ((pixel shr 8) and 0xFF) * weight
                resultB[i] += (pixel and 0xFF) * weight
            }
        }
        
        // 应用 ND 效果 (降低亮度)
        val result = IntArray(width * height)
        for (i in 0 until width * height) {
            val r = (resultR[i] * (1 - ndFactor * 0.3)).toInt().coerceIn(0, 255)
            val g = (resultG[i] * (1 - ndFactor * 0.3)).toInt().coerceIn(0, 255)
            val b = (resultB[i] * (1 - ndFactor * 0.3)).toInt().coerceIn(0, 255)
            
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(result, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * 运动模糊模式处理
     */
    private suspend fun processMotionBlur(
        frames: List<FrameData>,
        config: LongExposureConfig
    ): Bitmap {
        val width = frames[0].bitmap.width
        val height = frames[0].bitmap.height
        
        // 使用时间加权平均
        val pixelArrays = frames.map { frame ->
            IntArray(width * height).also { pixels ->
                frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        
        _processingState.value = ProcessingState.Processing(0.4f, "Applying motion blur")
        
        // 时间加权 (最近的帧权重更高)
        val weights = FloatArray(frames.size) { i ->
            val t = i.toFloat() / (frames.size - 1)
            // 使用指数衰减
            exp(-2f * (1f - t))
        }
        val totalWeight = weights.sum()
        
        val resultR = FloatArray(width * height)
        val resultG = FloatArray(width * height)
        val resultB = FloatArray(width * height)
        
        for ((frameIndex, pixels) in pixelArrays.withIndex()) {
            val weight = weights[frameIndex] / totalWeight
            for (i in 0 until width * height) {
                val pixel = pixels[i]
                resultR[i] += ((pixel shr 16) and 0xFF) * weight
                resultG[i] += ((pixel shr 8) and 0xFF) * weight
                resultB[i] += (pixel and 0xFF) * weight
            }
        }
        
        val result = IntArray(width * height)
        for (i in 0 until width * height) {
            result[i] = (0xFF shl 24) or 
                (resultR[i].toInt().coerceIn(0, 255) shl 16) or 
                (resultG[i].toInt().coerceIn(0, 255) shl 8) or 
                resultB[i].toInt().coerceIn(0, 255)
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(result, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * 星轨模式处理
     * 使用最大值合成 + 星点增强
     */
    private suspend fun processStarTrails(
        frames: List<FrameData>,
        config: LongExposureConfig
    ): Bitmap = coroutineScope {
        val width = frames[0].bitmap.width
        val height = frames[0].bitmap.height
        
        // 并行提取像素
        val pixelArrays = frames.mapIndexed { index, frame ->
            async {
                IntArray(width * height).also { pixels ->
                    frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                }
            }
        }.awaitAll()
        
        _processingState.value = ProcessingState.Processing(0.3f, "Stacking star trails")
        
        // 检测星点 (高亮点)
        val starMask = detectStars(pixelArrays[0], width, height)
        
        // 使用亮度最大值合成星轨
        val result = IntArray(width * height)
        
        for (i in 0 until width * height) {
            var maxR = 0
            var maxG = 0
            var maxB = 0
            
            for (pixels in pixelArrays) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                maxR = max(maxR, r)
                maxG = max(maxG, g)
                maxB = max(maxB, b)
            }
            
            // 星点增强
            if (starMask[i] > 0.5f) {
                maxR = min(255, (maxR * 1.2f).toInt())
                maxG = min(255, (maxG * 1.2f).toInt())
                maxB = min(255, (maxB * 1.2f).toInt())
            }
            
            result[i] = (0xFF shl 24) or (maxR shl 16) or (maxG shl 8) or maxB
            
            if (i % (width * height / 10) == 0) {
                _processingState.value = ProcessingState.Processing(
                    0.3f + 0.5f * i / (width * height),
                    "Stacking star trails"
                )
            }
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(result, 0, width, 0, 0, width, height)
        bitmap
    }
    
    /**
     * 检测运动区域
     */
    private fun detectMotionRegions(
        frames: List<FrameData>,
        referenceIndex: Int,
        sensitivity: Float
    ): FloatArray {
        val width = frames[0].bitmap.width
        val height = frames[0].bitmap.height
        
        val referencePixels = IntArray(width * height)
        frames[referenceIndex].bitmap.getPixels(referencePixels, 0, width, 0, 0, width, height)
        
        val motionAccumulator = FloatArray(width * height)
        
        for ((index, frame) in frames.withIndex()) {
            if (index == referenceIndex) continue
            
            val pixels = IntArray(width * height)
            frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            for (i in 0 until width * height) {
                val refPixel = referencePixels[i]
                val pixel = pixels[i]
                
                val dr = abs(((refPixel shr 16) and 0xFF) - ((pixel shr 16) and 0xFF))
                val dg = abs(((refPixel shr 8) and 0xFF) - ((pixel shr 8) and 0xFF))
                val db = abs((refPixel and 0xFF) - (pixel and 0xFF))
                
                val diff = (dr + dg + db) / (3f * 255f)
                motionAccumulator[i] += if (diff > sensitivity * 0.1f) 1f else 0f
            }
        }
        
        // 归一化运动掩码
        val threshold = (frames.size - 1) * 0.3f
        return FloatArray(width * height) { i ->
            (motionAccumulator[i] / threshold).coerceIn(0f, 1f)
        }
    }
    
    /**
     * 检测星点
     */
    private fun detectStars(pixels: IntArray, width: Int, height: Int): FloatArray {
        val starMask = FloatArray(width * height)
        val brightnessThreshold = 200
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val pixel = pixels[idx]
                val brightness = (((pixel shr 16) and 0xFF) + 
                    ((pixel shr 8) and 0xFF) + 
                    (pixel and 0xFF)) / 3
                
                if (brightness > brightnessThreshold) {
                    // 检查是否是局部最亮点
                    var isLocalMax = true
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val neighborIdx = (y + dy) * width + (x + dx)
                            val neighborPixel = pixels[neighborIdx]
                            val neighborBrightness = (((neighborPixel shr 16) and 0xFF) + 
                                ((neighborPixel shr 8) and 0xFF) + 
                                (neighborPixel and 0xFF)) / 3
                            if (neighborBrightness >= brightness) {
                                isLocalMax = false
                                break
                            }
                        }
                        if (!isLocalMax) break
                    }
                    
                    if (isLocalMax) {
                        starMask[idx] = 1f
                    }
                }
            }
        }
        
        return starMask
    }
    
    /**
     * 后处理
     */
    private suspend fun applyPostProcessing(
        bitmap: Bitmap,
        config: LongExposureConfig
    ): Bitmap {
        // 动态范围优化
        val optimized = dynamicRangeOptimizer.process(bitmap)
        
        // 色彩校正
        return colorFidelity.process(optimized).bitmap
    }
    
    /**
     * 取消处理
     */
    fun cancel() {
        _processingState.value = ProcessingState.Idle
    }
    
    /**
     * 重置状态
     */
    fun reset() {
        _processingState.value = ProcessingState.Idle
    }
}
