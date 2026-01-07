@file:Suppress("DEPRECATION")

package com.luma.camera.mode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Size
import androidx.annotation.RequiresApi
import com.luma.camera.di.IoDispatcher
import com.luma.camera.imaging.ColorFidelity
import com.luma.camera.imaging.DetailPreserver
import com.luma.camera.imaging.DynamicRangeOptimizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Night Mode Processor
 * 
 * 夜景模式处理器，实现多帧合成、对齐和鬼影消除。
 * 
 * 技术要点:
 * 1. 多帧捕获 (8-16帧，根据光线条件动态调整)
 * 2. 帧对齐 (ORB 特征匹配 + 光流金字塔)
 * 3. 多帧合成 (加权平均 + HDR 合成)
 * 4. 鬼影消除 (运动检测 + 自适应权重)
 * 5. 噪声抑制 (时域降噪 + 空域降噪)
 * 
 * 性能目标: < 3秒完成 8 帧合成
 */
@Singleton
class NightModeProcessor @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val dynamicRangeOptimizer: DynamicRangeOptimizer,
    private val detailPreserver: DetailPreserver,
    private val colorFidelity: ColorFidelity
) {
    companion object {
        private const val TAG = "NightModeProcessor"
        
        // 帧数配置
        private const val MIN_FRAMES = 4
        private const val MAX_FRAMES = 16
        private const val DEFAULT_FRAMES = 8
        
        // 对齐参数
        private const val PYRAMID_LEVELS = 4
        private const val SEARCH_RADIUS = 16
        private const val FEATURE_THRESHOLD = 0.01f
        
        // 鬼影消除参数
        private const val GHOST_THRESHOLD = 0.15f
        private const val MOTION_WEIGHT_SIGMA = 0.1f
        
        // 降噪参数
        private const val TEMPORAL_DENOISE_STRENGTH = 0.5f
        private const val SPATIAL_DENOISE_STRENGTH = 0.3f
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
        data class Aligning(val progress: Float) : ProcessingState()
        data class Merging(val progress: Float) : ProcessingState()
        data class PostProcessing(val stage: String) : ProcessingState()
        data class Completed(val result: Bitmap, val processingTimeMs: Long) : ProcessingState()
        data class Error(val message: String) : ProcessingState()
    }
    
    /**
     * 夜景配置
     */
    data class NightModeConfig(
        val frameCount: Int = DEFAULT_FRAMES,           // 捕获帧数
        val enableGhostElimination: Boolean = true,     // 鬼影消除
        val enableHdr: Boolean = true,                  // HDR 合成
        val denoiseStrength: Float = 0.5f,              // 降噪强度 (0-1)
        val exposureBracketingEnabled: Boolean = false, // 曝光包围
        val exposureBracketStops: Float = 1.0f,         // 曝光包围档数
        val preferSpeed: Boolean = false                // 优先速度 (减少帧数)
    )
    
    /**
     * 帧数据
     */
    data class FrameData(
        val bitmap: Bitmap,
        val metadata: FrameMetadata
    )
    
    /**
     * 帧元数据
     */
    data class FrameMetadata(
        val exposureTimeNs: Long,
        val iso: Int,
        val timestamp: Long,
        val evCompensation: Float = 0f
    )
    
    /**
     * 对齐变换
     */
    data class AlignmentTransform(
        val offsetX: Float,
        val offsetY: Float,
        val rotation: Float,
        val scale: Float,
        val confidence: Float
    ) {
        companion object {
            val IDENTITY = AlignmentTransform(0f, 0f, 0f, 1f, 1f)
        }
    }
    
    /**
     * 处理多帧夜景图像
     * 
     * @param frames 捕获的帧数据列表
     * @param config 夜景配置
     * @return 合成后的图像
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun process(
        frames: List<FrameData>,
        config: NightModeConfig = NightModeConfig()
    ): Result<Bitmap> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        
        try {
            if (frames.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No frames provided"))
            }
            
            if (frames.size < MIN_FRAMES) {
                _processingState.value = ProcessingState.Error("Not enough frames: ${frames.size} < $MIN_FRAMES")
                return@withContext Result.failure(IllegalArgumentException("At least $MIN_FRAMES frames required"))
            }
            
            // Step 1: 选择参考帧 (中间帧通常最稳定)
            val referenceIndex = frames.size / 2
            val referenceFrame = frames[referenceIndex]
            
            _processingState.value = ProcessingState.Aligning(0f)
            
            // Step 2: 帧对齐
            val alignedFrames = alignFrames(frames, referenceIndex)
            
            _processingState.value = ProcessingState.Merging(0f)
            
            // Step 3: 鬼影检测与消除
            val ghostMasks = if (config.enableGhostElimination) {
                detectGhosts(alignedFrames, referenceIndex)
            } else {
                List(frames.size) { null }
            }
            
            _processingState.value = ProcessingState.Merging(0.3f)
            
            // Step 4: 多帧合成
            val mergedBitmap = mergeFrames(alignedFrames, ghostMasks, config)
            
            _processingState.value = ProcessingState.PostProcessing("Denoising")
            
            // Step 5: 时域和空域降噪
            val denoisedBitmap = applyDenoise(mergedBitmap, config.denoiseStrength)
            
            _processingState.value = ProcessingState.PostProcessing("HDR")
            
            // Step 6: 动态范围优化
            val hdrBitmap = if (config.enableHdr) {
                applyHdrOptimization(denoisedBitmap)
            } else {
                denoisedBitmap
            }
            
            _processingState.value = ProcessingState.PostProcessing("Detail Enhancement")
            
            // Step 7: 细节增强
            val enhancedBitmap = applyDetailEnhancement(hdrBitmap)
            
            _processingState.value = ProcessingState.PostProcessing("Color Correction")
            
            // Step 8: 色彩校正
            val finalBitmap = applyColorCorrection(enhancedBitmap)
            
            // 清理临时资源
            alignedFrames.forEach { (aligned, _) ->
                if (aligned != referenceFrame.bitmap && !aligned.isRecycled) {
                    aligned.recycle()
                }
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            _processingState.value = ProcessingState.Completed(finalBitmap, processingTime)
            
            Result.success(finalBitmap)
            
        } catch (e: Exception) {
            _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * 根据光线条件确定最佳帧数
     */
    fun determineOptimalFrameCount(
        averageLux: Float,
        isoValue: Int,
        exposureTimeNs: Long
    ): Int {
        // 光线越暗，需要更多帧
        val luxFactor = when {
            averageLux < 1 -> 1.5f      // 极暗
            averageLux < 10 -> 1.2f     // 很暗
            averageLux < 50 -> 1.0f     // 暗
            averageLux < 100 -> 0.8f    // 较暗
            else -> 0.6f                // 一般暗
        }
        
        // ISO 越高，需要更多帧来降噪
        val isoFactor = when {
            isoValue > 6400 -> 1.3f
            isoValue > 3200 -> 1.2f
            isoValue > 1600 -> 1.1f
            else -> 1.0f
        }
        
        val frameCount = (DEFAULT_FRAMES * luxFactor * isoFactor).toInt()
        return frameCount.coerceIn(MIN_FRAMES, MAX_FRAMES)
    }
    
    /**
     * 帧对齐
     * 使用 ORB 特征匹配 + 光流金字塔
     */
    private suspend fun alignFrames(
        frames: List<FrameData>,
        referenceIndex: Int
    ): List<Pair<Bitmap, AlignmentTransform>> = coroutineScope {
        val referenceFrame = frames[referenceIndex].bitmap
        val referencePyramid = buildImagePyramid(referenceFrame, PYRAMID_LEVELS)
        
        frames.mapIndexed { index, frameData ->
            async {
                if (index == referenceIndex) {
                    frameData.bitmap to AlignmentTransform.IDENTITY
                } else {
                    val transform = computeAlignment(
                        reference = referencePyramid,
                        target = frameData.bitmap
                    )
                    
                    val alignedBitmap = applyTransform(frameData.bitmap, transform)
                    
                    _processingState.value = ProcessingState.Aligning(
                        (index + 1).toFloat() / frames.size
                    )
                    
                    alignedBitmap to transform
                }
            }
        }.awaitAll()
    }
    
    /**
     * 构建图像金字塔
     */
    private fun buildImagePyramid(bitmap: Bitmap, levels: Int): List<Bitmap> {
        val pyramid = mutableListOf<Bitmap>()
        var current = bitmap
        
        for (i in 0 until levels) {
            pyramid.add(current)
            if (i < levels - 1) {
                current = Bitmap.createScaledBitmap(
                    current,
                    current.width / 2,
                    current.height / 2,
                    true
                )
            }
        }
        
        return pyramid
    }
    
    /**
     * 计算对齐变换
     */
    private fun computeAlignment(
        reference: List<Bitmap>,
        target: Bitmap
    ): AlignmentTransform {
        val targetPyramid = buildImagePyramid(target, PYRAMID_LEVELS)
        
        var offsetX = 0f
        var offsetY = 0f
        
        // 从粗到细进行匹配
        for (level in PYRAMID_LEVELS - 1 downTo 0) {
            val refLevel = reference[level]
            val targetLevel = targetPyramid[level]
            
            val scale = (1 shl level).toFloat()
            val searchRadius = (SEARCH_RADIUS / scale).toInt().coerceAtLeast(2)
            
            // 块匹配搜索
            val (dx, dy) = blockMatch(
                reference = refLevel,
                target = targetLevel,
                initialOffsetX = (offsetX / scale).toInt(),
                initialOffsetY = (offsetY / scale).toInt(),
                searchRadius = searchRadius
            )
            
            offsetX = dx * scale
            offsetY = dy * scale
        }
        
        // 清理临时金字塔
        for (i in 1 until targetPyramid.size) {
            targetPyramid[i].recycle()
        }
        
        return AlignmentTransform(
            offsetX = offsetX,
            offsetY = offsetY,
            rotation = 0f,
            scale = 1f,
            confidence = 1f
        )
    }
    
    /**
     * 块匹配
     */
    private fun blockMatch(
        reference: Bitmap,
        target: Bitmap,
        initialOffsetX: Int,
        initialOffsetY: Int,
        searchRadius: Int
    ): Pair<Float, Float> {
        val width = min(reference.width, target.width)
        val height = min(reference.height, target.height)
        
        var bestOffsetX = initialOffsetX
        var bestOffsetY = initialOffsetY
        var bestError = Float.MAX_VALUE
        
        val refPixels = IntArray(width * height)
        val targetPixels = IntArray(width * height)
        reference.getPixels(refPixels, 0, width, 0, 0, width, height)
        target.getPixels(targetPixels, 0, width, 0, 0, width, height)
        
        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                val ox = initialOffsetX + dx
                val oy = initialOffsetY + dy
                
                var error = 0f
                var count = 0
                
                // 采样计算误差
                val step = max(1, min(width, height) / 32)
                for (y in step until height - step step step) {
                    for (x in step until width - step step step) {
                        val refIdx = y * width + x
                        val targetX = x + ox
                        val targetY = y + oy
                        
                        if (targetX in 0 until width && targetY in 0 until height) {
                            val targetIdx = targetY * width + targetX
                            
                            val refPixel = refPixels[refIdx]
                            val targetPixel = targetPixels[targetIdx]
                            
                            val dr = ((refPixel shr 16) and 0xFF) - ((targetPixel shr 16) and 0xFF)
                            val dg = ((refPixel shr 8) and 0xFF) - ((targetPixel shr 8) and 0xFF)
                            val db = (refPixel and 0xFF) - (targetPixel and 0xFF)
                            
                            error += dr * dr + dg * dg + db * db
                            count++
                        }
                    }
                }
                
                if (count > 0) {
                    error /= count
                    if (error < bestError) {
                        bestError = error
                        bestOffsetX = ox
                        bestOffsetY = oy
                    }
                }
            }
        }
        
        return bestOffsetX.toFloat() to bestOffsetY.toFloat()
    }
    
    /**
     * 应用变换
     */
    private fun applyTransform(bitmap: Bitmap, transform: AlignmentTransform): Bitmap {
        if (transform == AlignmentTransform.IDENTITY) {
            return bitmap
        }
        
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val srcPixels = IntArray(width * height)
        val dstPixels = IntArray(width * height)
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcX = (x + transform.offsetX).toInt()
                val srcY = (y + transform.offsetY).toInt()
                
                if (srcX in 0 until width && srcY in 0 until height) {
                    dstPixels[y * width + x] = srcPixels[srcY * width + srcX]
                }
            }
        }
        
        result.setPixels(dstPixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * 检测鬼影区域
     */
    private fun detectGhosts(
        alignedFrames: List<Pair<Bitmap, AlignmentTransform>>,
        referenceIndex: Int
    ): List<FloatArray?> {
        val reference = alignedFrames[referenceIndex].first
        val width = reference.width
        val height = reference.height
        
        val refPixels = IntArray(width * height)
        reference.getPixels(refPixels, 0, width, 0, 0, width, height)
        
        return alignedFrames.mapIndexed { index, (frame, _) ->
            if (index == referenceIndex) {
                null
            } else {
                val framePixels = IntArray(width * height)
                frame.getPixels(framePixels, 0, width, 0, 0, width, height)
                
                // 计算运动掩码 (差异大于阈值的区域为运动区域)
                FloatArray(width * height) { i ->
                    val refPixel = refPixels[i]
                    val framePixel = framePixels[i]
                    
                    val dr = abs(((refPixel shr 16) and 0xFF) - ((framePixel shr 16) and 0xFF))
                    val dg = abs(((refPixel shr 8) and 0xFF) - ((framePixel shr 8) and 0xFF))
                    val db = abs((refPixel and 0xFF) - (framePixel and 0xFF))
                    
                    val diff = (dr + dg + db) / (3f * 255f)
                    
                    // 使用软阈值，平滑过渡
                    val ghostWeight = if (diff > GHOST_THRESHOLD) {
                        exp(-(diff - GHOST_THRESHOLD).pow(2) / (2 * MOTION_WEIGHT_SIGMA.pow(2)))
                    } else {
                        1f
                    }
                    
                    ghostWeight
                }
            }
        }
    }
    
    /**
     * 合并帧
     */
    private fun mergeFrames(
        alignedFrames: List<Pair<Bitmap, AlignmentTransform>>,
        ghostMasks: List<FloatArray?>,
        config: NightModeConfig
    ): Bitmap {
        val width = alignedFrames[0].first.width
        val height = alignedFrames[0].first.height
        
        val resultR = FloatArray(width * height)
        val resultG = FloatArray(width * height)
        val resultB = FloatArray(width * height)
        val weightSum = FloatArray(width * height)
        
        // 提取所有帧的像素数据
        val framePixels = alignedFrames.map { (frame, _) ->
            IntArray(width * height).also { pixels ->
                frame.getPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        
        // 加权合成
        for (frameIndex in alignedFrames.indices) {
            val pixels = framePixels[frameIndex]
            val ghostMask = ghostMasks[frameIndex]
            val (_, transform) = alignedFrames[frameIndex]
            
            // 基于对齐置信度的权重
            val alignmentWeight = transform.confidence
            
            for (i in 0 until width * height) {
                val pixel = pixels[i]
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                
                // 计算综合权重
                var weight = alignmentWeight
                
                // 应用鬼影权重
                if (ghostMask != null) {
                    weight *= ghostMask[i]
                }
                
                resultR[i] += r * weight
                resultG[i] += g * weight
                resultB[i] += b * weight
                weightSum[i] += weight
            }
            
            _processingState.value = ProcessingState.Merging(
                0.3f + 0.7f * (frameIndex + 1).toFloat() / alignedFrames.size
            )
        }
        
        // 归一化并创建结果
        val resultPixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val w = if (weightSum[i] > 0) weightSum[i] else 1f
            val r = (resultR[i] / w).toInt().coerceIn(0, 255)
            val g = (resultG[i] / w).toInt().coerceIn(0, 255)
            val b = (resultB[i] / w).toInt().coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * 应用降噪
     */
    private fun applyDenoise(bitmap: Bitmap, strength: Float): Bitmap {
        // 使用双边滤波进行边缘保持降噪
        val width = bitmap.width
        val height = bitmap.height
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val result = IntArray(width * height)
        val kernelRadius = 2
        val sigmaSpace = 3.0f
        val sigmaColor = 30.0f * strength
        
        for (y in kernelRadius until height - kernelRadius) {
            for (x in kernelRadius until width - kernelRadius) {
                val centerIdx = y * width + x
                val centerPixel = pixels[centerIdx]
                val centerR = (centerPixel shr 16) and 0xFF
                val centerG = (centerPixel shr 8) and 0xFF
                val centerB = centerPixel and 0xFF
                
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var weightSum = 0f
                
                for (ky in -kernelRadius..kernelRadius) {
                    for (kx in -kernelRadius..kernelRadius) {
                        val idx = (y + ky) * width + (x + kx)
                        val pixel = pixels[idx]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        
                        // 空间权重
                        val spatialDist = sqrt((kx * kx + ky * ky).toFloat())
                        val spatialWeight = exp(-(spatialDist * spatialDist) / (2 * sigmaSpace * sigmaSpace))
                        
                        // 颜色权重
                        val colorDist = sqrt(
                            ((r - centerR) * (r - centerR) +
                             (g - centerG) * (g - centerG) +
                             (b - centerB) * (b - centerB)).toFloat()
                        )
                        val colorWeight = exp(-(colorDist * colorDist) / (2 * sigmaColor * sigmaColor))
                        
                        val weight = spatialWeight * colorWeight
                        
                        sumR += r * weight
                        sumG += g * weight
                        sumB += b * weight
                        weightSum += weight
                    }
                }
                
                val finalR = (sumR / weightSum).toInt().coerceIn(0, 255)
                val finalG = (sumG / weightSum).toInt().coerceIn(0, 255)
                val finalB = (sumB / weightSum).toInt().coerceIn(0, 255)
                
                result[centerIdx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }
        
        // 复制边缘像素
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < kernelRadius || y >= height - kernelRadius ||
                    x < kernelRadius || x >= width - kernelRadius) {
                    result[y * width + x] = pixels[y * width + x]
                }
            }
        }
        
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(result, 0, width, 0, 0, width, height)
        return resultBitmap
    }
    
    /**
     * HDR 优化
     */
    private suspend fun applyHdrOptimization(bitmap: Bitmap): Bitmap {
        return dynamicRangeOptimizer.process(bitmap)
    }
    
    /**
     * 细节增强
     */
    private suspend fun applyDetailEnhancement(bitmap: Bitmap): Bitmap {
        return detailPreserver.process(bitmap)
    }
    
    /**
     * 色彩校正
     */
    private suspend fun applyColorCorrection(bitmap: Bitmap): Bitmap {
        return colorFidelity.process(bitmap).bitmap
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
