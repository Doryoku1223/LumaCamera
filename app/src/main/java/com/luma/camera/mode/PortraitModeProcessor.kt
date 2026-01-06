package com.luma.camera.mode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.luma.camera.di.IoDispatcher
import com.luma.camera.imaging.ColorFidelity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Portrait Mode Processor
 * 
 * 人像模式处理器，使用 ML Kit 实现 AI 背景虚化和深度估计。
 * 
 * 技术要点:
 * 1. 人像分割 - ML Kit Selfie Segmentation
 * 2. 人脸检测 - ML Kit Face Detection  
 * 3. 深度估计 - 基于分割的伪深度图生成
 * 4. 背景虚化 - 可变半径高斯模糊
 * 5. 边缘优化 - 抗锯齿和羽化处理
 * 
 * 性能目标: < 1秒完成处理
 */
@Singleton
class PortraitModeProcessor @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val colorFidelity: ColorFidelity
) {
    companion object {
        private const val TAG = "PortraitModeProcessor"
        
        // 虚化参数
        private const val DEFAULT_BLUR_RADIUS = 25f
        private const val MAX_BLUR_RADIUS = 50f
        private const val MIN_BLUR_RADIUS = 5f
        
        // 边缘处理
        private const val EDGE_FEATHER_RADIUS = 5
        private const val EDGE_SMOOTH_ITERATIONS = 2
        
        // 深度层级
        private const val DEPTH_LEVELS = 8
    }
    
    // ML Kit 分割器
    private val segmenter: Segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }
    
    // ML Kit 人脸检测器
    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        FaceDetection.getClient(options)
    }
    
    // 处理状态
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()
    
    /**
     * 处理状态
     */
    sealed class ProcessingState {
        object Idle : ProcessingState()
        data class Segmenting(val progress: Float) : ProcessingState()
        data class Processing(val stage: String) : ProcessingState()
        data class Completed(val result: Bitmap, val processingTimeMs: Long) : ProcessingState()
        data class Error(val message: String) : ProcessingState()
    }
    
    /**
     * 人像模式配置
     */
    data class PortraitConfig(
        val blurStrength: Float = 0.5f,           // 虚化强度 (0-1)
        val blurShape: BlurShape = BlurShape.CIRCULAR,  // 虚化形状
        val enableFaceBeautify: Boolean = false,   // 美颜
        val beautifyStrength: Float = 0.3f,        // 美颜强度
        val enableDepthAware: Boolean = true,      // 深度感知虚化
        val preserveEdges: Boolean = true,         // 保持边缘清晰
        val bokehStyle: BokehStyle = BokehStyle.NATURAL  // 光斑风格
    )
    
    /**
     * 虚化形状
     */
    enum class BlurShape {
        CIRCULAR,   // 圆形 (标准)
        OVAL,       // 椭圆形
        LINEAR      // 线性渐变
    }
    
    /**
     * 光斑风格
     */
    enum class BokehStyle {
        NATURAL,    // 自然
        SWIRL,      // 旋转
        HEART,      // 心形
        STAR        // 星形
    }
    
    /**
     * 处理结果
     */
    data class PortraitResult(
        val processedImage: Bitmap,
        val segmentationMask: Bitmap?,
        val depthMap: Bitmap?,
        val faces: List<FaceInfo>,
        val processingTimeMs: Long
    )
    
    /**
     * 人脸信息
     */
    data class FaceInfo(
        val boundingBox: RectF,
        val landmarks: Map<String, Pair<Float, Float>>,
        val smilingProbability: Float?,
        val leftEyeOpenProbability: Float?,
        val rightEyeOpenProbability: Float?
    )
    
    /**
     * 处理人像照片
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun process(
        inputBitmap: Bitmap,
        config: PortraitConfig = PortraitConfig()
    ): Result<PortraitResult> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        
        try {
            _processingState.value = ProcessingState.Segmenting(0f)
            
            // Step 1: 人像分割
            val segmentationMask = performSegmentation(inputBitmap)
                ?: return@withContext Result.failure(Exception("Segmentation failed"))
            
            _processingState.value = ProcessingState.Segmenting(0.5f)
            
            // Step 2: 人脸检测
            val faces = detectFaces(inputBitmap)
            
            _processingState.value = ProcessingState.Processing("Generating depth map")
            
            // Step 3: 生成深度图
            val depthMap = if (config.enableDepthAware) {
                generateDepthMap(segmentationMask, faces, inputBitmap.width, inputBitmap.height)
            } else {
                null
            }
            
            _processingState.value = ProcessingState.Processing("Applying blur")
            
            // Step 4: 应用背景虚化
            val blurredBackground = applyBackgroundBlur(
                original = inputBitmap,
                mask = segmentationMask,
                depthMap = depthMap,
                config = config
            )
            
            _processingState.value = ProcessingState.Processing("Edge refinement")
            
            // Step 5: 合成前景和背景
            val composited = compositeImage(
                foreground = inputBitmap,
                background = blurredBackground,
                mask = segmentationMask,
                config = config
            )
            
            // Step 6: 美颜处理 (可选)
            val finalImage = if (config.enableFaceBeautify && faces.isNotEmpty()) {
                applyFaceBeautify(composited, faces, config.beautifyStrength)
            } else {
                composited
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            
            val result = PortraitResult(
                processedImage = finalImage,
                segmentationMask = segmentationMask,
                depthMap = depthMap,
                faces = faces,
                processingTimeMs = processingTime
            )
            
            _processingState.value = ProcessingState.Completed(finalImage, processingTime)
            
            Result.success(result)
            
        } catch (e: Exception) {
            _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * 执行人像分割
     */
    private suspend fun performSegmentation(bitmap: Bitmap): Bitmap? = 
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            segmenter.process(inputImage)
                .addOnSuccessListener { segmentationMask ->
                    val mask = segmentationMaskToBitmap(
                        segmentationMask,
                        bitmap.width,
                        bitmap.height
                    )
                    continuation.resume(mask)
                }
                .addOnFailureListener { e ->
                    continuation.resume(null)
                }
        }
    
    /**
     * 将分割掩码转换为 Bitmap
     */
    private fun segmentationMaskToBitmap(
        mask: SegmentationMask,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val buffer = mask.buffer
        val maskWidth = mask.width
        val maskHeight = mask.height
        
        // 创建掩码 Bitmap
        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ALPHA_8)
        val pixels = ByteArray(maskWidth * maskHeight)
        
        buffer.rewind()
        for (i in 0 until maskWidth * maskHeight) {
            val confidence = buffer.float
            pixels[i] = (confidence * 255).toInt().coerceIn(0, 255).toByte()
        }
        
        maskBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        
        // 缩放到目标尺寸
        return if (maskWidth != targetWidth || maskHeight != targetHeight) {
            Bitmap.createScaledBitmap(maskBitmap, targetWidth, targetHeight, true)
        } else {
            maskBitmap
        }
    }
    
    /**
     * 人脸检测
     */
    private suspend fun detectFaces(bitmap: Bitmap): List<FaceInfo> = 
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val faceInfoList = faces.map { face ->
                        FaceInfo(
                            boundingBox = RectF(face.boundingBox),
                            landmarks = extractLandmarks(face),
                            smilingProbability = face.smilingProbability,
                            leftEyeOpenProbability = face.leftEyeOpenProbability,
                            rightEyeOpenProbability = face.rightEyeOpenProbability
                        )
                    }
                    continuation.resume(faceInfoList)
                }
                .addOnFailureListener {
                    continuation.resume(emptyList())
                }
        }
    
    /**
     * 提取人脸关键点
     */
    private fun extractLandmarks(face: Face): Map<String, Pair<Float, Float>> {
        val landmarks = mutableMapOf<String, Pair<Float, Float>>()
        
        face.allLandmarks.forEach { landmark ->
            landmarks[landmark.landmarkType.toString()] = 
                Pair(landmark.position.x, landmark.position.y)
        }
        
        return landmarks
    }
    
    /**
     * 生成深度图
     */
    private fun generateDepthMap(
        segmentationMask: Bitmap,
        faces: List<FaceInfo>,
        width: Int,
        height: Int
    ): Bitmap {
        val depthMap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(depthMap)
        
        // 基于分割掩码生成基础深度
        val maskPixels = IntArray(width * height)
        segmentationMask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        
        val depthPixels = IntArray(width * height)
        
        for (i in 0 until width * height) {
            val maskValue = maskPixels[i] and 0xFF
            
            // 前景 = 近距离 (高值), 背景 = 远距离 (低值)
            val depth = if (maskValue > 128) {
                // 前景: 根据到边缘的距离调整深度
                200 + (maskValue - 128) / 2
            } else {
                // 背景: 较低的深度值
                50 + maskValue / 3
            }
            
            depthPixels[i] = (0xFF shl 24) or (depth shl 16) or (depth shl 8) or depth
        }
        
        // 在人脸区域增加深度 (更近)
        for (face in faces) {
            val cx = face.boundingBox.centerX().toInt()
            val cy = face.boundingBox.centerY().toInt()
            val radius = max(face.boundingBox.width(), face.boundingBox.height()).toInt() / 2
            
            for (y in max(0, cy - radius) until min(height, cy + radius)) {
                for (x in max(0, cx - radius) until min(width, cx + radius)) {
                    val distance = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat())
                    if (distance < radius) {
                        val idx = y * width + x
                        // 人脸中心深度最高
                        val faceDepth = (255 * (1 - distance / radius)).toInt()
                        val currentDepth = (depthPixels[idx] shr 16) and 0xFF
                        val newDepth = max(currentDepth, faceDepth)
                        depthPixels[idx] = (0xFF shl 24) or (newDepth shl 16) or (newDepth shl 8) or newDepth
                    }
                }
            }
        }
        
        depthMap.setPixels(depthPixels, 0, width, 0, 0, width, height)
        return depthMap
    }
    
    /**
     * 应用背景虚化
     */
    private fun applyBackgroundBlur(
        original: Bitmap,
        mask: Bitmap,
        depthMap: Bitmap?,
        config: PortraitConfig
    ): Bitmap {
        val width = original.width
        val height = original.height
        
        // 计算模糊半径
        val blurRadius = MIN_BLUR_RADIUS + 
            (MAX_BLUR_RADIUS - MIN_BLUR_RADIUS) * config.blurStrength
        
        // 使用栈模糊算法 (比高斯模糊更快)
        return if (config.enableDepthAware && depthMap != null) {
            applyDepthAwareBlur(original, depthMap, blurRadius)
        } else {
            applyUniformBlur(original, blurRadius.toInt())
        }
    }
    
    /**
     * 均匀模糊
     */
    private fun applyUniformBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val result = IntArray(width * height)
        
        // 水平模糊
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                
                for (kx in -radius..radius) {
                    val nx = x + kx
                    if (nx in 0 until width) {
                        val pixel = pixels[y * width + nx]
                        r += (pixel shr 16) and 0xFF
                        g += (pixel shr 8) and 0xFF
                        b += pixel and 0xFF
                        count++
                    }
                }
                
                result[y * width + x] = (0xFF shl 24) or 
                    ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        
        // 垂直模糊
        val final = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                
                for (ky in -radius..radius) {
                    val ny = y + ky
                    if (ny in 0 until height) {
                        val pixel = result[ny * width + x]
                        r += (pixel shr 16) and 0xFF
                        g += (pixel shr 8) and 0xFF
                        b += pixel and 0xFF
                        count++
                    }
                }
                
                final[y * width + x] = (0xFF shl 24) or 
                    ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        
        val blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurred.setPixels(final, 0, width, 0, 0, width, height)
        return blurred
    }
    
    /**
     * 深度感知模糊
     */
    private fun applyDepthAwareBlur(
        original: Bitmap,
        depthMap: Bitmap,
        maxRadius: Float
    ): Bitmap {
        val width = original.width
        val height = original.height
        
        val pixels = IntArray(width * height)
        val depthPixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)
        depthMap.getPixels(depthPixels, 0, width, 0, 0, width, height)
        
        val result = IntArray(width * height)
        
        // 对每个像素根据深度应用不同程度的模糊
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val depth = (depthPixels[idx] shr 16) and 0xFF
                
                // 深度越低 (背景)，模糊越强
                val blurRadius = ((255 - depth) / 255f * maxRadius).toInt().coerceIn(0, maxRadius.toInt())
                
                if (blurRadius == 0) {
                    result[idx] = pixels[idx]
                } else {
                    // 采样周围像素
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    
                    val step = max(1, blurRadius / 3)
                    for (ky in -blurRadius..blurRadius step step) {
                        for (kx in -blurRadius..blurRadius step step) {
                            val nx = x + kx
                            val ny = y + ky
                            if (nx in 0 until width && ny in 0 until height) {
                                val pixel = pixels[ny * width + nx]
                                r += (pixel shr 16) and 0xFF
                                g += (pixel shr 8) and 0xFF
                                b += pixel and 0xFF
                                count++
                            }
                        }
                    }
                    
                    if (count > 0) {
                        result[idx] = (0xFF shl 24) or 
                            ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    } else {
                        result[idx] = pixels[idx]
                    }
                }
            }
        }
        
        val blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurred.setPixels(result, 0, width, 0, 0, width, height)
        return blurred
    }
    
    /**
     * 合成前景和背景
     */
    private fun compositeImage(
        foreground: Bitmap,
        background: Bitmap,
        mask: Bitmap,
        config: PortraitConfig
    ): Bitmap {
        val width = foreground.width
        val height = foreground.height
        
        val fgPixels = IntArray(width * height)
        val bgPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        
        foreground.getPixels(fgPixels, 0, width, 0, 0, width, height)
        background.getPixels(bgPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        
        val result = IntArray(width * height)
        
        for (i in 0 until width * height) {
            val alpha = (maskPixels[i] and 0xFF) / 255f
            
            // 应用边缘羽化
            val smoothedAlpha = if (config.preserveEdges) {
                smoothEdge(alpha)
            } else {
                alpha
            }
            
            // 混合前景和背景
            val fgR = (fgPixels[i] shr 16) and 0xFF
            val fgG = (fgPixels[i] shr 8) and 0xFF
            val fgB = fgPixels[i] and 0xFF
            
            val bgR = (bgPixels[i] shr 16) and 0xFF
            val bgG = (bgPixels[i] shr 8) and 0xFF
            val bgB = bgPixels[i] and 0xFF
            
            val r = (fgR * smoothedAlpha + bgR * (1 - smoothedAlpha)).toInt()
            val g = (fgG * smoothedAlpha + bgG * (1 - smoothedAlpha)).toInt()
            val b = (fgB * smoothedAlpha + bgB * (1 - smoothedAlpha)).toInt()
            
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        val composited = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        composited.setPixels(result, 0, width, 0, 0, width, height)
        return composited
    }
    
    /**
     * 边缘平滑
     */
    private fun smoothEdge(alpha: Float): Float {
        // 使用 smoothstep 函数
        val t = alpha.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
    
    /**
     * 美颜处理
     */
    private fun applyFaceBeautify(
        bitmap: Bitmap,
        faces: List<FaceInfo>,
        strength: Float
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (face in faces) {
            val bounds = face.boundingBox
            val startX = bounds.left.toInt().coerceIn(0, width - 1)
            val startY = bounds.top.toInt().coerceIn(0, height - 1)
            val endX = bounds.right.toInt().coerceIn(0, width)
            val endY = bounds.bottom.toInt().coerceIn(0, height)
            
            // 磨皮 - 双边滤波
            for (y in startY until endY) {
                for (x in startX until endX) {
                    val idx = y * width + x
                    val pixel = pixels[idx]
                    
                    // 简化的磨皮效果
                    var r = (pixel shr 16) and 0xFF
                    var g = (pixel shr 8) and 0xFF
                    var b = pixel and 0xFF
                    
                    // 轻微提亮
                    val brightness = 1 + strength * 0.1f
                    r = (r * brightness).toInt().coerceIn(0, 255)
                    g = (g * brightness).toInt().coerceIn(0, 255)
                    b = (b * brightness).toInt().coerceIn(0, 255)
                    
                    pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * 实时预览处理 (低分辨率，快速)
     */
    suspend fun processPreview(
        inputBitmap: Bitmap,
        config: PortraitConfig = PortraitConfig()
    ): Bitmap? = withContext(ioDispatcher) {
        try {
            // 降采样
            val scale = 4
            val smallBitmap = Bitmap.createScaledBitmap(
                inputBitmap,
                inputBitmap.width / scale,
                inputBitmap.height / scale,
                true
            )
            
            // 快速分割
            val mask = performSegmentation(smallBitmap) ?: return@withContext null
            
            // 快速模糊
            val blurred = applyUniformBlur(smallBitmap, 5)
            
            // 合成
            val result = compositeImage(smallBitmap, blurred, mask, config)
            
            // 放大回原始尺寸
            Bitmap.createScaledBitmap(result, inputBitmap.width, inputBitmap.height, true)
            
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        segmenter.close()
        faceDetector.close()
    }
}
