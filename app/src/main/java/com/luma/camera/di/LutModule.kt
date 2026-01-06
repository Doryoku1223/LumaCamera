package com.luma.camera.di

import android.content.Context
import com.luma.camera.lut.GpuLutRenderer
import com.luma.camera.lut.LutManager
import com.luma.camera.lut.LutParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * LUT 模块 DI 配置
 *
 * 提供 LUT 处理相关的依赖：
 * - LutParser: LUT 文件解析器
 * - GpuLutRenderer: GPU LUT 渲染器
 * - LutManager: LUT 管理器
 * 
 * 注意：所有组件都使用 @Inject constructor 自动绑定，
 * 此模块主要用于提供配置和组织结构文档。
 */
@Module
@InstallIn(SingletonComponent::class)
object LutModule {

    /**
     * 提供 LUT 配置
     */
    @Provides
    @Singleton
    fun provideLutConfig(): LutConfig {
        return LutConfig(
            maxLutSize = 65,           // 最大支持 65x65x65 3D LUT
            defaultIntensity = 1.0f,
            assetsLutDir = "luts",
            supportedFormats = listOf(".cube", ".3dl"),
            enableGpuProcessing = true
        )
    }
}

/**
 * LUT 配置
 */
data class LutConfig(
    val maxLutSize: Int,
    val defaultIntensity: Float,
    val assetsLutDir: String,
    val supportedFormats: List<String>,
    val enableGpuProcessing: Boolean
)
