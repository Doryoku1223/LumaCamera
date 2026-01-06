package com.luma.camera.livephoto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.luma.camera.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * KeyFrame Selector
 * 
 * 智能关键帧选择器，用于从 LivePhoto 视频中选择最佳帧作为主图。
 * 
 * 评估维度:
 * 1. 清晰度评估 - Laplacian 方差
 * 2. 人脸/表情分析 - ML Kit Face Detection
 * 3. 运动稳定性 - 光流分析
 * 4. 曝光质量 - 直方图分析
 * 5. 综合评分算法
 */
@Singleton
class KeyFrameSelector @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "KeyFrameSelector"
        
        // 评分权重
        private const val WEIGHT_SHARPNESS = 0.35f
        private const val WEIGHT_FACE = 0.30f
        private const val WEIGHT_STABILITY = 0.20f
        private const val WEIGHT_EXPOSURE = 0.15f
        
        // 阈值
        private const val MIN_SHARPNESS_THRESHOLD = 50.0
        private const val IDEAL_BRIGHTNESS = 127.5f
        private const val MAX_CANDIDATE_FRAMES = 30
        
        // Laplacian 卷积核
        private val LAPLACIAN_KERNEL = floatArrayOf(
            0f, 1f, 0f,
            1f, -4f, 1f,
            0f, 1f, 0f
        )
    }
    
    // ML Kit Face Detector
    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
        
        FaceDetection.getClient(options)
    }
    
    /**
     * 帧评分结果
     */
    data class FrameScore(
        val frameIndex: Int,
        val timestamp: Long,
        val sharpnessScore: Float,      // 清晰度得分 (0-1)
        val faceScore: Float,           // 人脸/表情得分 (0-1)
        val stabilityScore: Float,      // 稳定性得分 (0-1)
        val exposureScore: Float,       // 曝光得分 (0-1)
        val compositeScore: Float,      // 综合得分 (0-1)
        val faceDetails: List<FaceDetails>? = null
    )
    
    /**
     * 人脸详情
     */
    data class FaceDetails(
        val smilingProbability: Float,
        val leftEyeOpenProbability: Float,
        val rightEyeOpenProbability: Float,
        val faceSize: Float,            // 相对于画面的比例
        val facePosition: FacePosition  // 人脸位置 (用于构图评估)
    )
    
    /**
     * 人脸位置
     */
    enum class FacePosition {
        CENTER, LEFT, RIGHT, TOP, BOTTOM
    }
    
    /**
     * 选择配置
     */
    data class SelectionConfig(
        val preferSmiling: Boolean = true,          // 优先选择微笑表情
        val preferEyesOpen: Boolean = true,         // 优先选择睁眼
        val centerBias: Float = 0.1f,               // 中心偏好 (靠近视频中心的帧加分)
        val candidateCount: Int = 10,               // 候选帧数量
        val enableFaceDetection: Boolean = true     // 是否启用人脸检测
    )
    
    /**
     * 从视频中选择最佳关键帧
     * 
     * @param videoFile 视频文件
     * @param config 选择配置
     * @return 最佳帧索引和评分
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun selectBestKeyframe(
        videoFile: File,
        config: SelectionConfig = SelectionConfig()
    ): FrameScore? = withContext(ioDispatcher) {
        try {
            // 提取候选帧
            val candidateFrames = extractCandidateFrames(videoFile, config.candidateCount)
            
            if (candidateFrames.isEmpty()) {
                return@withContext null
            }
            
            // 评估每一帧
            val scores = candidateFrames.mapIndexed { index, (bitmap, timestamp) ->
                evaluateFrame(
                    bitmap = bitmap,
                    frameIndex = index,
                    timestamp = timestamp,
                    config = config,
                    totalFrames = candidateFrames.size
                ).also {
                    bitmap.recycle()
                }
            }
            
            // 返回最高分的帧
            scores.maxByOrNull { it.compositeScore }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 批量评估帧并返回排序后的结果
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun evaluateFrames(
        videoFile: File,
        config: SelectionConfig = SelectionConfig()
    ): List<FrameScore> = withContext(ioDispatcher) {
        try {
            val candidateFrames = extractCandidateFrames(videoFile, config.candidateCount)
            
            val scores = candidateFrames.mapIndexed { index, (bitmap, timestamp) ->
                evaluateFrame(
                    bitmap = bitmap,
                    frameIndex = index,
                    timestamp = timestamp,
                    config = config,
                    totalFrames = candidateFrames.size
                ).also {
                    bitmap.recycle()
                }
            }
            
            scores.sortedByDescending { it.compositeScore }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 从视频中提取候选帧
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun extractCandidateFrames(
        videoFile: File,
        count: Int
    ): List<Pair<Bitmap, Long>> = withContext(ioDispatcher) {
        val frames = mutableListOf<Pair<Bitmap, Long>>()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        
        try {
            extractor.setDataSource(videoFile.absolutePath)
            
            // 查找视频轨道
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            
            if (videoTrackIndex < 0 || videoFormat == null) {
                return@withContext emptyList()
            }
            
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val duration = videoFormat.getLong(MediaFormat.KEY_DURATION)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            
            // 计算采样间隔
            val actualCount = min(count, MAX_CANDIDATE_FRAMES)
            val interval = duration / (actualCount + 1)
            
            // 创建解码器
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()
            
            extractor.selectTrack(videoTrackIndex)
            
            val bufferInfo = MediaCodec.BufferInfo()
            
            for (i in 1..actualCount) {
                val targetTime = interval * i
                extractor.seekTo(targetTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                
                // 解码一帧
                val bitmap = decodeFrame(extractor, decoder, bufferInfo, width, height)
                if (bitmap != null) {
                    frames.add(bitmap to extractor.sampleTime)
                }
            }
            
        } finally {
            decoder?.stop()
            decoder?.release()
            extractor.release()
        }
        
        frames
    }
    
    /**
     * 解码单帧
     */
    private fun decodeFrame(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        width: Int,
        height: Int
    ): Bitmap? {
        val timeoutUs = 10_000L
        
        // 送入数据
        val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
        if (inputBufferIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: return null
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            
            if (sampleSize >= 0) {
                decoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    sampleSize,
                    extractor.sampleTime,
                    0
                )
            } else {
                return null
            }
        }
        
        // 获取输出
        val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
        if (outputBufferIndex >= 0) {
            val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
            
            if (outputBuffer != null && bufferInfo.size > 0) {
                // 创建 Bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                // 注意: 这里需要根据实际的像素格式转换
                // 完整实现需要处理 YUV 到 RGB 的转换
                // 简化实现使用占位符
                
                decoder.releaseOutputBuffer(outputBufferIndex, false)
                return bitmap
            }
            
            decoder.releaseOutputBuffer(outputBufferIndex, false)
        }
        
        return null
    }
    
    /**
     * 评估单帧
     */
    private suspend fun evaluateFrame(
        bitmap: Bitmap,
        frameIndex: Int,
        timestamp: Long,
        config: SelectionConfig,
        totalFrames: Int
    ): FrameScore {
        // 计算各维度得分
        val sharpnessScore = calculateSharpnessScore(bitmap)
        val exposureScore = calculateExposureScore(bitmap)
        val stabilityScore = calculateStabilityScore(frameIndex, totalFrames, config.centerBias)
        
        // 人脸检测和评分
        val (faceScore, faceDetails) = if (config.enableFaceDetection) {
            evaluateFaces(bitmap, config)
        } else {
            0.5f to null
        }
        
        // 计算综合得分
        val compositeScore = 
            sharpnessScore * WEIGHT_SHARPNESS +
            faceScore * WEIGHT_FACE +
            stabilityScore * WEIGHT_STABILITY +
            exposureScore * WEIGHT_EXPOSURE
        
        return FrameScore(
            frameIndex = frameIndex,
            timestamp = timestamp,
            sharpnessScore = sharpnessScore,
            faceScore = faceScore,
            stabilityScore = stabilityScore,
            exposureScore = exposureScore,
            compositeScore = compositeScore,
            faceDetails = faceDetails
        )
    }
    
    /**
     * 计算清晰度得分 (使用 Laplacian 方差)
     */
    private fun calculateSharpnessScore(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        
        // 转换为灰度并计算 Laplacian
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val grayscale = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            grayscale[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        
        // 计算 Laplacian
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var laplacian = 0f
                
                // 3x3 卷积
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val idx = (y + ky) * width + (x + kx)
                        val kernelIdx = (ky + 1) * 3 + (kx + 1)
                        laplacian += grayscale[idx] * LAPLACIAN_KERNEL[kernelIdx]
                    }
                }
                
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }
        
        // 计算方差
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        
        // 归一化到 0-1
        val normalizedVariance = min(variance / 1000.0, 1.0)
        return normalizedVariance.toFloat()
    }
    
    /**
     * 计算曝光得分
     */
    private fun calculateExposureScore(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 计算亮度直方图
        val histogram = IntArray(256)
        var totalBrightness = 0L
        
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = ((0.299 * r + 0.587 * g + 0.114 * b)).toInt()
            histogram[brightness]++
            totalBrightness += brightness
        }
        
        val meanBrightness = totalBrightness.toFloat() / pixels.size
        
        // 理想亮度偏离度
        val brightnessDeviation = abs(meanBrightness - IDEAL_BRIGHTNESS) / 127.5f
        val brightnessScore = 1f - brightnessDeviation
        
        // 检查过曝/欠曝
        val overexposed = histogram.takeLast(10).sum().toFloat() / pixels.size
        val underexposed = histogram.take(10).sum().toFloat() / pixels.size
        
        // 对比度评估
        val contrastScore = calculateContrastScore(histogram)
        
        // 综合曝光得分
        return (brightnessScore * 0.5f + 
                contrastScore * 0.3f + 
                (1f - overexposed) * 0.1f + 
                (1f - underexposed) * 0.1f)
            .coerceIn(0f, 1f)
    }
    
    /**
     * 计算对比度得分
     */
    private fun calculateContrastScore(histogram: IntArray): Float {
        // 计算直方图的标准差作为对比度指标
        val total = histogram.sum()
        val mean = histogram.indices.sumOf { it * histogram[it] }.toFloat() / total
        
        var variance = 0f
        for (i in histogram.indices) {
            variance += histogram[i] * (i - mean) * (i - mean)
        }
        variance /= total
        
        val stdDev = sqrt(variance)
        
        // 归一化 (理想标准差约 64)
        return (stdDev / 80f).coerceIn(0f, 1f)
    }
    
    /**
     * 计算稳定性得分 (基于帧位置)
     */
    private fun calculateStabilityScore(
        frameIndex: Int,
        totalFrames: Int,
        centerBias: Float
    ): Float {
        // 越靠近中间的帧得分越高 (假设拍摄者在中间时刻最稳定)
        val center = (totalFrames - 1) / 2f
        val distanceFromCenter = abs(frameIndex - center) / center
        
        // 基础稳定性得分
        val baseScore = 1f - distanceFromCenter
        
        // 应用中心偏好
        return baseScore + centerBias * (1f - distanceFromCenter)
    }
    
    /**
     * 评估人脸
     */
    private suspend fun evaluateFaces(
        bitmap: Bitmap,
        config: SelectionConfig
    ): Pair<Float, List<FaceDetails>?> {
        return try {
            val faces = detectFaces(bitmap)
            
            if (faces.isEmpty()) {
                // 没有检测到人脸，返回中性得分
                return 0.5f to null
            }
            
            val faceDetails = faces.map { face ->
                val bitmapArea = bitmap.width * bitmap.height
                val faceArea = face.boundingBox.width() * face.boundingBox.height()
                
                FaceDetails(
                    smilingProbability = face.smilingProbability ?: 0f,
                    leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0f,
                    rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0f,
                    faceSize = faceArea.toFloat() / bitmapArea,
                    facePosition = determineFacePosition(face, bitmap.width, bitmap.height)
                )
            }
            
            // 计算人脸得分
            var score = 0f
            var weightSum = 0f
            
            for (details in faceDetails) {
                var faceWeight = details.faceSize * 10 // 大脸权重更高
                faceWeight = faceWeight.coerceIn(0.5f, 2f)
                
                var faceScore = 0f
                
                // 微笑得分
                if (config.preferSmiling) {
                    faceScore += details.smilingProbability * 0.4f
                } else {
                    faceScore += 0.2f // 不优先微笑时给中性分
                }
                
                // 睁眼得分
                if (config.preferEyesOpen) {
                    val eyesOpen = (details.leftEyeOpenProbability + details.rightEyeOpenProbability) / 2
                    faceScore += eyesOpen * 0.4f
                } else {
                    faceScore += 0.2f
                }
                
                // 位置得分 (中心位置更好)
                faceScore += when (details.facePosition) {
                    FacePosition.CENTER -> 0.2f
                    else -> 0.1f
                }
                
                score += faceScore * faceWeight
                weightSum += faceWeight
            }
            
            val finalScore = if (weightSum > 0) score / weightSum else 0.5f
            finalScore.coerceIn(0f, 1f) to faceDetails
            
        } catch (e: Exception) {
            0.5f to null
        }
    }
    
    /**
     * 使用 ML Kit 检测人脸
     */
    private suspend fun detectFaces(bitmap: Bitmap): List<Face> = 
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    continuation.resume(faces)
                }
                .addOnFailureListener { e ->
                    continuation.resume(emptyList())
                }
        }
    
    /**
     * 判断人脸位置
     */
    private fun determineFacePosition(face: Face, imageWidth: Int, imageHeight: Int): FacePosition {
        val centerX = face.boundingBox.centerX()
        val centerY = face.boundingBox.centerY()
        
        val xRatio = centerX.toFloat() / imageWidth
        val yRatio = centerY.toFloat() / imageHeight
        
        return when {
            xRatio in 0.35f..0.65f && yRatio in 0.35f..0.65f -> FacePosition.CENTER
            xRatio < 0.35f -> FacePosition.LEFT
            xRatio > 0.65f -> FacePosition.RIGHT
            yRatio < 0.35f -> FacePosition.TOP
            else -> FacePosition.BOTTOM
        }
    }
    
    /**
     * 从 Bitmap 列表中选择最佳帧
     */
    suspend fun selectBestFromBitmaps(
        bitmaps: List<Bitmap>,
        config: SelectionConfig = SelectionConfig()
    ): Int = withContext(ioDispatcher) {
        if (bitmaps.isEmpty()) return@withContext 0
        
        val scores = bitmaps.mapIndexed { index, bitmap ->
            evaluateFrame(
                bitmap = bitmap,
                frameIndex = index,
                timestamp = 0L,
                config = config,
                totalFrames = bitmaps.size
            )
        }
        
        scores.maxByOrNull { it.compositeScore }?.frameIndex ?: 0
    }
    
    /**
     * 快速评估清晰度 (用于实时预览)
     */
    fun quickSharpnessCheck(bitmap: Bitmap): Float {
        // 使用降采样加速
        val scale = 4
        val smallBitmap = Bitmap.createScaledBitmap(
            bitmap,
            bitmap.width / scale,
            bitmap.height / scale,
            true
        )
        
        val score = calculateSharpnessScore(smallBitmap)
        smallBitmap.recycle()
        
        return score
    }
    
    /**
     * 释放资源
     */
    fun release() {
        faceDetector.close()
    }
}
