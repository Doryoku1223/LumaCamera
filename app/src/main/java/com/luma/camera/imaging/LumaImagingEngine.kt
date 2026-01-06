package com.luma.camera.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Luma Imaging Engine - 影像处理核心
 *
 * 这是 Luma Camera 的核心竞争力
 *
 * 设计理念：
 * - 最大程度保留原始图像的所有信息
 * - 包括动态范围、暗部细节、图像解析力、原始色彩
 * - 通过专业算法将信息映射为类 Log 的灰片
 * - 最后应用 LUT 滤镜
 *
 * 处理管线：
 * RAW → 去马赛克 → 黑电平校正 → 细节保留处理
 *     → 动态范围优化 → 色彩保真处理 → Luma Log 曲线映射
 *     → 灰片生成 → LUT 颜色映射 → 最终成片
 *
 * 性能目标：
 * - 完整流程 < 1.5s
 * - 充分利用 GPU 加速
 */
@Singleton
class LumaImagingEngine @Inject constructor(
    private val rawProcessor: RawProcessor,
    private val detailPreserver: DetailPreserver,
    private val dynamicRangeOptimizer: DynamicRangeOptimizer,
    private val colorFidelity: ColorFidelity,
    private val lumaLogCurve: LumaLogCurve,
    private val flatProfileGenerator: FlatProfileGenerator,
    private val imageQualityAnalyzer: ImageQualityAnalyzer
) {
    /**
     * 处理进度
     */
    sealed class ProcessingProgress {
        data object Started : ProcessingProgress()
        data class Stage(val stageName: String, val progress: Float) : ProcessingProgress()
        data class Completed(val result: ProcessingResult) : ProcessingProgress()
        data class Error(val exception: Exception) : ProcessingProgress()
    }

    /**
     * 处理结果
     */
    data class ProcessingResult(
        val finalImage: Bitmap,           // LUT 处理后的成片
        val flatImage: Bitmap?,           // Luma 灰片 (可选)
        val logImage: Bitmap?,            // Log 编码照片 (可选)
        val processingTimeMs: Long,       // 处理耗时
        val qualityAnalysis: ImageQualityAnalyzer.QualityReport
    )

    /**
     * 处理选项
     */
    data class ProcessingOptions(
        val generateFlatImage: Boolean = false,   // 是否生成灰片
        val generateLogImage: Boolean = false,    // 是否生成 Log 照片
        val lutId: String? = null,                // 要应用的 LUT ID
        val lutIntensity: Float = 1.0f            // LUT 强度 0-1
    )

    /**
     * 处理 RAW 图像
     *
     * @param rawData RAW 图像数据
     * @param options 处理选项
     * @return 处理进度 Flow
     */
    fun processRaw(
        rawData: ByteArray,
        options: ProcessingOptions = ProcessingOptions()
    ): Flow<ProcessingProgress> = flow {
        val startTime = System.currentTimeMillis()

        try {
            emit(ProcessingProgress.Started)

            // 阶段 1: RAW 解码 (< 300ms)
            emit(ProcessingProgress.Stage("RAW 解码", 0.0f))
            val demosaicedImage = rawProcessor.process(rawData)
            emit(ProcessingProgress.Stage("RAW 解码", 1.0f))

            // 阶段 2: 细节保留处理 (< 200ms)
            emit(ProcessingProgress.Stage("细节保留", 0.0f))
            val detailPreserved = detailPreserver.process(demosaicedImage)
            emit(ProcessingProgress.Stage("细节保留", 1.0f))

            // 阶段 3: 动态范围优化 (< 200ms)
            emit(ProcessingProgress.Stage("动态范围优化", 0.0f))
            val dynamicRangeOptimized = dynamicRangeOptimizer.process(detailPreserved)
            emit(ProcessingProgress.Stage("动态范围优化", 1.0f))

            // 阶段 4: 色彩保真处理 (< 150ms)
            emit(ProcessingProgress.Stage("色彩处理", 0.0f))
            val colorResult = colorFidelity.process(dynamicRangeOptimized)
            val colorCorrected = colorResult.bitmap
            emit(ProcessingProgress.Stage("色彩处理", 1.0f))

            // 阶段 5: Log 曲线映射 (< 100ms)
            emit(ProcessingProgress.Stage("Log 曲线映射", 0.0f))
            val logMapped = lumaLogCurve.apply(colorCorrected)
            emit(ProcessingProgress.Stage("Log 曲线映射", 1.0f))

            // 阶段 6: 灰片生成
            emit(ProcessingProgress.Stage("灰片生成", 0.0f))
            val flatImage = flatProfileGenerator.generate(logMapped)
            emit(ProcessingProgress.Stage("灰片生成", 1.0f))

            // 阶段 7: LUT 应用 (< 50ms)
            emit(ProcessingProgress.Stage("滤镜应用", 0.0f))
            val finalImage = if (options.lutId != null) {
                // TODO: 调用 LUT 引擎应用滤镜
                flatImage // 暂时返回灰片
            } else {
                flatImage
            }
            emit(ProcessingProgress.Stage("滤镜应用", 1.0f))

            // 图像质量分析
            val qualityReport = imageQualityAnalyzer.analyze(finalImage)

            val processingTime = System.currentTimeMillis() - startTime

            val result = ProcessingResult(
                finalImage = finalImage,
                flatImage = if (options.generateFlatImage) flatImage else null,
                logImage = if (options.generateLogImage) logMapped else null,
                processingTimeMs = processingTime,
                qualityAnalysis = qualityReport
            )

            emit(ProcessingProgress.Completed(result))

        } catch (e: Exception) {
            emit(ProcessingProgress.Error(e))
        }
    }

    /**
     * 处理 JPEG 图像 (简化流程，跳过 RAW 处理)
     */
    fun processJpeg(
        jpegData: ByteArray,
        options: ProcessingOptions = ProcessingOptions()
    ): Flow<ProcessingProgress> = flow {
        val startTime = System.currentTimeMillis()

        try {
            emit(ProcessingProgress.Started)

            // 解码 JPEG
            emit(ProcessingProgress.Stage("解码", 0.0f))
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                jpegData, 0, jpegData.size
            ) ?: throw IllegalArgumentException("Failed to decode JPEG")
            emit(ProcessingProgress.Stage("解码", 1.0f))

            // 简化处理流程
            emit(ProcessingProgress.Stage("优化处理", 0.0f))
            val optimized = dynamicRangeOptimizer.processSimple(bitmap)
            emit(ProcessingProgress.Stage("优化处理", 0.5f))
            val colorCorrected = colorFidelity.processSimple(optimized)
            emit(ProcessingProgress.Stage("优化处理", 1.0f))

            // Log 曲线
            emit(ProcessingProgress.Stage("Log 曲线", 0.0f))
            val logMapped = lumaLogCurve.apply(colorCorrected)
            emit(ProcessingProgress.Stage("Log 曲线", 1.0f))

            // 灰片生成
            val flatImage = flatProfileGenerator.generate(logMapped)

            // LUT 应用
            emit(ProcessingProgress.Stage("滤镜应用", 0.0f))
            val finalImage = if (options.lutId != null) {
                flatImage // TODO: 应用 LUT
            } else {
                flatImage
            }
            emit(ProcessingProgress.Stage("滤镜应用", 1.0f))

            val qualityReport = imageQualityAnalyzer.analyze(finalImage)
            val processingTime = System.currentTimeMillis() - startTime

            emit(ProcessingProgress.Completed(
                ProcessingResult(
                    finalImage = finalImage,
                    flatImage = if (options.generateFlatImage) flatImage else null,
                    logImage = if (options.generateLogImage) logMapped else null,
                    processingTimeMs = processingTime,
                    qualityAnalysis = qualityReport
                )
            ))

        } catch (e: Exception) {
            emit(ProcessingProgress.Error(e))
        }
    }
}
