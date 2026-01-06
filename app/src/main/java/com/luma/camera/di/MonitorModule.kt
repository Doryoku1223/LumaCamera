package com.luma.camera.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 监视器模块 DI 配置
 *
 * 提供专业监视工具相关的依赖：
 * - HistogramAnalyzer: 直方图分析器
 * - WaveformMonitor: 波形监视器/向量示波器
 * 
 * 注意：这些组件使用 @Inject constructor 自动绑定，
 * 此模块主要用于提供配置。
 */
@Module
@InstallIn(SingletonComponent::class)
object MonitorModule {

    /**
     * 提供监视器配置
     */
    @Provides
    @Singleton
    fun provideMonitorConfig(): MonitorConfig {
        return MonitorConfig(
            histogramBins = 256,
            waveformHeight = 256,
            waveformColumns = 256,
            vectorscopeSize = 256,
            sampleStep = 4,
            refreshRateHz = 30,
            clippingThreshold = 0.01f
        )
    }
}

/**
 * 监视器配置
 */
data class MonitorConfig(
    val histogramBins: Int,
    val waveformHeight: Int,
    val waveformColumns: Int,
    val vectorscopeSize: Int,
    val sampleStep: Int,
    val refreshRateHz: Int,
    val clippingThreshold: Float
)
