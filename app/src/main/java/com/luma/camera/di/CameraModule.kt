package com.luma.camera.di

import android.content.Context
import android.hardware.camera2.CameraManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 相机模块 DI
 *
 * 提供相机相关的依赖：
 * - CameraManager: 系统相机管理器
 * - MeteringManager: 测光管理（通过 @Inject 自动绑定）
 * - SensorInfoManager: 传感器信息管理（通过 @Inject 自动绑定）
 * 
 * 注意：MeteringManager 和 SensorInfoManager 使用 @Singleton @Inject constructor
 * 自动被 Hilt 管理，无需在此显式提供。
 */
@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    @Provides
    @Singleton
    fun provideCameraManager(
        @ApplicationContext context: Context
    ): CameraManager {
        return context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /**
     * 提供相机配置
     */
    @Provides
    @Singleton
    fun provideCameraConfig(): CameraConfig {
        return CameraConfig(
            preferredPreviewFps = 120,
            enableZsl = true,
            meteringAreas = 9,
            aeLockSupported = true,
            afLockSupported = true
        )
    }
}

/**
 * 相机配置
 */
data class CameraConfig(
    val preferredPreviewFps: Int,
    val enableZsl: Boolean,
    val meteringAreas: Int,
    val aeLockSupported: Boolean,
    val afLockSupported: Boolean
)
