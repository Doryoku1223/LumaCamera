package com.luma.camera.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.luma.camera.domain.model.ColorPalette
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.domain.model.WatermarkPosition
import com.luma.camera.lut.LutManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * 照片处理请求
 */
data class PhotoProcessingRequest(
    val id: Int,
    val photoData: ByteArray,
    val selectedLut: LutFilter?,
    val lutIntensity: Float,
    val colorPalette: ColorPalette,
    val watermarkEnabled: Boolean,
    val watermarkPosition: WatermarkPosition,
    val isLivePhoto: Boolean = false,
    val onComplete: suspend (ProcessedPhotoResult) -> Unit
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PhotoProcessingRequest
        return id == other.id
    }
    
    override fun hashCode(): Int = id
}

/**
 * 处理后的照片结果
 */
data class ProcessedPhotoResult(
    val id: Int,
    val data: ByteArray,
    val orientation: Int,
    val isSuccess: Boolean,
    val error: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProcessedPhotoResult
        return id == other.id
    }
    
    override fun hashCode(): Int = id
}

/**
 * 处理队列状态
 */
data class ProcessingQueueState(
    val pendingCount: Int = 0,
    val processingCount: Int = 0,
    val completedCount: Int = 0,
    val isProcessing: Boolean = false
)

/**
 * 照片处理队列
 * 
 * 异步处理照片效果（调色盘、LUT、水印），让用户可以快速连续拍照
 * 
 * 特点：
 * - 非阻塞：拍照后立即返回，处理在后台进行
 * - 并行处理：使用多核优化像素处理
 * - 状态可观察：可以显示处理进度
 * - 优先级：可以设置处理优先级
 */
@Singleton
class PhotoProcessingQueue @Inject constructor(
    private val lutManager: LutManager,
    private val watermarkRenderer: WatermarkRenderer
) {
    companion object {
        private const val TAG = "PhotoProcessingQueue"
        private const val MAX_CONCURRENT_PROCESSING = 2 // 最多同时处理 2 张照片
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestIdCounter = AtomicInteger(0)
    
    // 处理队列
    private val processingChannel = Channel<PhotoProcessingRequest>(Channel.UNLIMITED)
    
    // 队列状态
    private val _queueState = MutableStateFlow(ProcessingQueueState())
    val queueState: StateFlow<ProcessingQueueState> = _queueState.asStateFlow()
    
    // 正在处理的数量
    private val processingCount = AtomicInteger(0)
    
    init {
        // 启动处理协程
        repeat(MAX_CONCURRENT_PROCESSING) { workerId ->
            scope.launch {
                for (request in processingChannel) {
                    processRequest(request, workerId)
                }
            }
        }
    }
    
    /**
     * 提交照片处理请求
     * 
     * @return 请求 ID，可用于跟踪处理状态
     */
    fun submit(
        photoData: ByteArray,
        selectedLut: LutFilter?,
        lutIntensity: Float,
        colorPalette: ColorPalette,
        watermarkEnabled: Boolean,
        watermarkPosition: WatermarkPosition,
        isLivePhoto: Boolean = false,
        onComplete: suspend (ProcessedPhotoResult) -> Unit
    ): Int {
        val requestId = requestIdCounter.incrementAndGet()
        
        val request = PhotoProcessingRequest(
            id = requestId,
            photoData = photoData,
            selectedLut = selectedLut,
            lutIntensity = lutIntensity,
            colorPalette = colorPalette,
            watermarkEnabled = watermarkEnabled,
            watermarkPosition = watermarkPosition,
            isLivePhoto = isLivePhoto,
            onComplete = onComplete
        )
        
        // 更新状态
        _queueState.value = _queueState.value.copy(
            pendingCount = _queueState.value.pendingCount + 1
        )
        
        // 发送到处理通道
        scope.launch {
            processingChannel.send(request)
        }
        
        Timber.d("Submitted photo processing request #$requestId")
        return requestId
    }
    
    /**
     * 处理单个请求
     */
    private suspend fun processRequest(request: PhotoProcessingRequest, workerId: Int) {
        val startTime = System.currentTimeMillis()
        Timber.d("Worker $workerId: Starting to process request #${request.id}")
        
        // 更新状态
        processingCount.incrementAndGet()
        _queueState.value = _queueState.value.copy(
            pendingCount = maxOf(0, _queueState.value.pendingCount - 1),
            processingCount = processingCount.get(),
            isProcessing = true
        )
        
        try {
            // 读取 EXIF 方向
            val originalOrientation = try {
                val inputStream = java.io.ByteArrayInputStream(request.photoData)
                val exif = ExifInterface(inputStream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to read EXIF orientation")
                ExifInterface.ORIENTATION_NORMAL
            }
            
            val hasColorAdjustments = !request.colorPalette.isDefault()
            val hasLut = request.selectedLut != null && request.lutIntensity > 0f
            
            // 如果没有任何效果，直接返回原图
            if (!hasColorAdjustments && !hasLut && !request.watermarkEnabled) {
                Timber.d("No effects to apply, returning original")
                val result = ProcessedPhotoResult(
                    id = request.id,
                    data = request.photoData,
                    orientation = originalOrientation,
                    isSuccess = true
                )
                request.onComplete(result)
                return
            }
            
            // 解码 JPEG
            var bitmap = BitmapFactory.decodeByteArray(request.photoData, 0, request.photoData.size)
            if (bitmap == null) {
                throw Exception("Failed to decode photo")
            }
            
            Timber.d("Worker $workerId: Decoded ${bitmap.width}x${bitmap.height} in ${System.currentTimeMillis() - startTime}ms")
            
            // 应用调色盘（使用优化的并行处理）
            if (hasColorAdjustments) {
                val colorStart = System.currentTimeMillis()
                bitmap = applyColorPaletteOptimized(bitmap, request.colorPalette)
                Timber.d("Worker $workerId: Color palette applied in ${System.currentTimeMillis() - colorStart}ms")
            }
            
            // 应用 LUT
            if (hasLut) {
                val lutStart = System.currentTimeMillis()
                bitmap = lutManager.applyLut(
                    input = bitmap,
                    lutId = request.selectedLut!!.id,
                    intensity = request.lutIntensity
                )
                Timber.d("Worker $workerId: LUT applied in ${System.currentTimeMillis() - lutStart}ms")
            }
            
            // 应用水印
            if (request.watermarkEnabled) {
                val watermarkStart = System.currentTimeMillis()
                bitmap = watermarkRenderer.applyWatermark(
                    bitmap, 
                    request.watermarkPosition,
                    originalOrientation
                )
                Timber.d("Worker $workerId: Watermark applied in ${System.currentTimeMillis() - watermarkStart}ms")
            }
            
            // 编码 JPEG
            val encodeStart = System.currentTimeMillis()
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            bitmap.recycle()
            Timber.d("Worker $workerId: Encoded in ${System.currentTimeMillis() - encodeStart}ms")
            
            val totalTime = System.currentTimeMillis() - startTime
            Timber.d("Worker $workerId: Completed request #${request.id} in ${totalTime}ms")
            
            val result = ProcessedPhotoResult(
                id = request.id,
                data = outputStream.toByteArray(),
                orientation = originalOrientation,
                isSuccess = true
            )
            request.onComplete(result)
            
        } catch (e: Exception) {
            Timber.e(e, "Worker $workerId: Failed to process request #${request.id}")
            val result = ProcessedPhotoResult(
                id = request.id,
                data = request.photoData, // 返回原图
                orientation = ExifInterface.ORIENTATION_NORMAL,
                isSuccess = false,
                error = e.message
            )
            request.onComplete(result)
        } finally {
            // 更新状态
            processingCount.decrementAndGet()
            _queueState.value = _queueState.value.copy(
                processingCount = processingCount.get(),
                completedCount = _queueState.value.completedCount + 1,
                isProcessing = processingCount.get() > 0
            )
        }
    }
    
    /**
     * 优化的调色盘处理（使用 Thread 并行，与 CameraViewModel 一致）
     * 
     * Thread 在密集计算场景下比协程更高效
     */
    private fun applyColorPaletteOptimized(
        input: Bitmap, 
        palette: ColorPalette
    ): Bitmap {
        val width = input.width
        val height = input.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 获取参数
        val targetKelvin = palette.targetKelvin
        val exposureGain = palette.exposureGain
        val saturationAdjust = palette.saturationAdjust
        
        // 计算白平衡 RGB 乘数
        val wbMult = calculateWhiteBalanceMultipliers(targetKelvin)
        val wbR = wbMult[0]
        val wbG = wbMult[1]
        val wbB = wbMult[2]
        
        // 预计算 sRGB -> Linear 查找表
        val srgbToLinearLut = FloatArray(256) { i ->
            val x = i / 255f
            if (x <= 0.04045f) x / 12.92f
            else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        
        // 预计算 Linear -> sRGB 查找表
        val linearToSrgbLut = FloatArray(4097) { i ->
            val x = i / 4096f
            if (x <= 0.0031308f) x * 12.92f
            else (1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f)
        }
        
        // 使用 Thread 并行处理（密集计算场景下比协程更高效）
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
                    
                    // 1. sRGB → Linear
                    var r = srgbToLinearLut[ri]
                    var g = srgbToLinearLut[gi]
                    var b = srgbToLinearLut[bi]
                    
                    // 2. 白平衡
                    r *= wbR
                    g *= wbG
                    b *= wbB
                    
                    // 3. 曝光
                    r *= exposureGain
                    g *= exposureGain
                    b *= exposureGain
                    
                    // 4. 饱和度（OKLab 空间）
                    val lms_l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
                    val lms_m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
                    val lms_s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
                    
                    val lCbrt = kotlin.math.cbrt(lms_l.toDouble()).toFloat()
                    val mCbrt = kotlin.math.cbrt(lms_m.toDouble()).toFloat()
                    val sCbrt = kotlin.math.cbrt(lms_s.toDouble()).toFloat()
                    
                    val okL = 0.2104542553f * lCbrt + 0.7936177850f * mCbrt - 0.0040720468f * sCbrt
                    val okA = 1.9779984951f * lCbrt - 2.4285922050f * mCbrt + 0.4505937099f * sCbrt
                    val okB = 0.0259040371f * lCbrt + 0.7827717662f * mCbrt - 0.8086757660f * sCbrt
                    
                    val C = kotlin.math.sqrt((okA * okA + okB * okB).toDouble()).toFloat()
                    var adjA = okA
                    var adjB = okB
                    
                    if (C >= 0.0001f) {
                        var t = (okL - 0.7f) / 0.3f
                        if (t < 0f) t = 0f else if (t > 1f) t = 1f
                        val highlightProtection = 1f - t * t * (3f - 2f * t)
                        
                        t = okL / 0.2f
                        if (t < 0f) t = 0f else if (t > 1f) t = 1f
                        val shadowProtection = t * t * (3f - 2f * t)
                        
                        val effectiveSatAdjust = saturationAdjust * highlightProtection * shadowProtection
                        var newC = C * (1f + effectiveSatAdjust)
                        
                        if (newC > 0.4f) {
                            newC = 0.4f + (newC - 0.4f) / (1f + (newC - 0.4f))
                        }
                        if (newC < 0f) newC = 0f else if (newC > 0.5f) newC = 0.5f
                        
                        val scale = newC / C
                        adjA = okA * scale
                        adjB = okB * scale
                    }
                    
                    val lCbrt2 = okL + 0.3963377774f * adjA + 0.2158037573f * adjB
                    val mCbrt2 = okL - 0.1055613458f * adjA - 0.0638541728f * adjB
                    val sCbrt2 = okL - 0.0894841775f * adjA - 1.2914855480f * adjB
                    
                    val ll = lCbrt2 * lCbrt2 * lCbrt2
                    val mm = mCbrt2 * mCbrt2 * mCbrt2
                    val ss = sCbrt2 * sCbrt2 * sCbrt2
                    
                    r = 4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss
                    g = -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss
                    b = -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss
                    
                    // 5. Linear → sRGB
                    var rIdx = (r * 4096f).toInt()
                    var gIdx = (g * 4096f).toInt()
                    var bIdx = (b * 4096f).toInt()
                    if (rIdx < 0) rIdx = 0 else if (rIdx > 4096) rIdx = 4096
                    if (gIdx < 0) gIdx = 0 else if (gIdx > 4096) gIdx = 4096
                    if (bIdx < 0) bIdx = 0 else if (bIdx > 4096) bIdx = 4096
                    
                    val rSrgb = linearToSrgbLut[rIdx]
                    val gSrgb = linearToSrgbLut[gIdx]
                    val bSrgb = linearToSrgbLut[bIdx]
                    
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
        
        // 启动并等待所有线程完成
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        
        input.recycle()
        return output
    }
    
    /**
     * 计算白平衡乘数（与 GPU Shader kelvinToRgbMultipliers 完全一致）
     */
    private fun calculateWhiteBalanceMultipliers(kelvin: Float): FloatArray {
        val temp = kelvin / 100f
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
        
        // 归一化（以 5500K 为基准）—— 与 GPU Shader 一致
        val baseRgb = floatArrayOf(1f, 0.94f, 0.91f) // 5500K 近似值
        return floatArrayOf(
            baseRgb[0] / rgb[0].coerceAtLeast(0.001f),
            baseRgb[1] / rgb[1].coerceAtLeast(0.001f),
            baseRgb[2] / rgb[2].coerceAtLeast(0.001f)
        )
    }
    
    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
        processingChannel.close()
    }
}
