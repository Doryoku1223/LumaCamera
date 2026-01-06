package com.luma.camera.di

import com.luma.camera.imaging.ColorFidelity
import com.luma.camera.imaging.DetailPreserver
import com.luma.camera.imaging.DynamicRangeOptimizer
import com.luma.camera.imaging.FlatProfileGenerator
import com.luma.camera.imaging.HistogramAnalyzer
import com.luma.camera.imaging.ImageQualityAnalyzer
import com.luma.camera.imaging.LumaImagingEngine
import com.luma.camera.imaging.LumaLogCurve
import com.luma.camera.imaging.RawProcessor
import com.luma.camera.imaging.WaveformMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 成像模块 DI 配置
 *
 * 提供 Luma 成像引擎相关的依赖注入。
 * 
 * 包含组件：
 * - RawProcessor: RAW 图像处理和 Bayer demosaic
 * - ColorFidelity: 色彩保真度和白平衡
 * - DynamicRangeOptimizer: 动态范围优化和 HDR
 * - DetailPreserver: 细节保留和降噪
 * - HistogramAnalyzer: 直方图分析
 * - WaveformMonitor: 波形监视器和向量示波器
 * - LumaImagingEngine: 成像管线主引擎
 * 
 * 注意：所有组件都使用 @Inject constructor 自动绑定，
 * 此模块主要用于组织结构和提供绑定文档。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImagingModule {

    /**
     * 提供成像管线配置
     */
    @Provides
    @Singleton
    fun provideImagingPipelineConfig(): ImagingPipelineConfig {
        return ImagingPipelineConfig(
            maxRawProcessingTimeMs = 300,
            maxColorProcessingTimeMs = 150,
            maxDroProcessingTimeMs = 200,
            maxDetailProcessingTimeMs = 200,
            enableGpuAcceleration = true,
            histogramSampleStep = 4,
            waveformColumns = 256
        )
    }
}

/**
 * 成像管线配置
 */
data class ImagingPipelineConfig(
    val maxRawProcessingTimeMs: Int,
    val maxColorProcessingTimeMs: Int,
    val maxDroProcessingTimeMs: Int,
    val maxDetailProcessingTimeMs: Int,
    val enableGpuAcceleration: Boolean,
    val histogramSampleStep: Int,
    val waveformColumns: Int
)
