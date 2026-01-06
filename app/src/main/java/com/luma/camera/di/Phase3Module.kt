package com.luma.camera.di

import android.app.Application
import android.content.Context
import com.luma.camera.livephoto.KeyFrameSelector
import com.luma.camera.livephoto.LivePhotoEncoder
import com.luma.camera.livephoto.LivePhotoLutProcessor
import com.luma.camera.livephoto.LivePhotoManager
import com.luma.camera.mode.CameraModeManager
import com.luma.camera.mode.LongExposureProcessor
import com.luma.camera.mode.NightModeProcessor
import com.luma.camera.mode.PortraitModeProcessor
import com.luma.camera.mode.TimerShootingController
import com.luma.camera.mode.AebProcessor
import com.luma.camera.imaging.ColorFidelity
import com.luma.camera.imaging.DetailPreserver
import com.luma.camera.imaging.DynamicRangeOptimizer
import com.luma.camera.lut.LutManager
import com.luma.camera.startup.BaselineProfileManager
import com.luma.camera.startup.MemoryOptimizer
import com.luma.camera.startup.StartupOptimizer
import com.luma.camera.startup.WarmupManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * Phase 3 DI Module
 * 
 * 第三阶段依赖注入模块:
 * - LivePhoto 完善 (HEIC Encoder, KeyFrame Selector, LUT Processor)
 * - 夜景模式 (NightModeProcessor)
 * - 人像模式 (PortraitModeProcessor)
 * - 长曝光模式 (LongExposureProcessor)
 * - 定时拍摄 (TimerController, AEB)
 * - 启动优化 (StartupOptimizer, WarmupManager, BaselineProfileManager)
 * - 模式管理 (CameraModeManager)
 */
@Module
@InstallIn(SingletonComponent::class)
object Phase3Module {
    
    // ==================== LivePhoto 模块 ====================
    
    @Provides
    @Singleton
    fun provideLivePhotoEncoder(
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): LivePhotoEncoder {
        return LivePhotoEncoder(ioDispatcher)
    }
    
    @Provides
    @Singleton
    fun provideKeyFrameSelector(
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): KeyFrameSelector {
        return KeyFrameSelector(ioDispatcher)
    }
    
    @Provides
    @Singleton
    fun provideLivePhotoLutProcessor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        lutManager: LutManager
    ): LivePhotoLutProcessor {
        return LivePhotoLutProcessor(ioDispatcher, lutManager)
    }
    
    // ==================== 拍摄模式模块 ====================
    
    @Provides
    @Singleton
    fun provideNightModeProcessor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        dynamicRangeOptimizer: DynamicRangeOptimizer,
        detailPreserver: DetailPreserver,
        colorFidelity: ColorFidelity
    ): NightModeProcessor {
        return NightModeProcessor(
            ioDispatcher,
            dynamicRangeOptimizer,
            detailPreserver,
            colorFidelity
        )
    }
    
    @Provides
    @Singleton
    fun providePortraitModeProcessor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        colorFidelity: ColorFidelity
    ): PortraitModeProcessor {
        return PortraitModeProcessor(ioDispatcher, colorFidelity)
    }
    
    @Provides
    @Singleton
    fun provideLongExposureProcessor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        dynamicRangeOptimizer: DynamicRangeOptimizer,
        colorFidelity: ColorFidelity
    ): LongExposureProcessor {
        return LongExposureProcessor(
            ioDispatcher,
            dynamicRangeOptimizer,
            colorFidelity
        )
    }
    
    // ==================== 定时拍摄模块 ====================
    
    @Provides
    @Singleton
    fun provideTimerShootingController(
        @ApplicationContext context: Context
    ): TimerShootingController {
        return TimerShootingController(context)
    }
    
    @Provides
    @Singleton
    fun provideAebProcessor(): AebProcessor {
        return AebProcessor()
    }
    
    // ==================== 启动优化模块 ====================
    // Note: WarmupManager and MemoryOptimizer use @Inject constructor
    // and are automatically provided by Hilt
    
    @Provides
    @Singleton
    fun provideStartupOptimizer(
        application: Application
    ): StartupOptimizer {
        return StartupOptimizer(application)
    }
    
    @Provides
    @Singleton
    fun provideBaselineProfileManager(
        @ApplicationContext context: Context
    ): BaselineProfileManager {
        return BaselineProfileManager(context)
    }
    
    // Note: MemoryOptimizer uses @Inject constructor and is auto-provided by Hilt
    
    // ==================== 模式管理模块 ====================
    
    @Provides
    @Singleton
    fun provideCameraModeManager(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        nightModeProcessor: NightModeProcessor,
        portraitModeProcessor: PortraitModeProcessor,
        longExposureProcessor: LongExposureProcessor,
        timerController: TimerShootingController,
        livePhotoManager: LivePhotoManager,
        livePhotoEncoder: LivePhotoEncoder,
        keyFrameSelector: KeyFrameSelector,
        livePhotoLutProcessor: LivePhotoLutProcessor
    ): CameraModeManager {
        return CameraModeManager(
            ioDispatcher,
            nightModeProcessor,
            portraitModeProcessor,
            longExposureProcessor,
            timerController,
            livePhotoManager,
            livePhotoEncoder,
            keyFrameSelector,
            livePhotoLutProcessor
        )
    }
}
