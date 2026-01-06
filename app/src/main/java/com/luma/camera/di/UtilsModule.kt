package com.luma.camera.di

import com.luma.camera.crash.CrashReporter
import com.luma.camera.utils.FeedbackHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 工具模块
 *
 * 提供崩溃报告和用户反馈相关的依赖
 */
@Module
@InstallIn(SingletonComponent::class)
object UtilsModule {

    @Provides
    @Singleton
    fun provideCrashReporter(): CrashReporter {
        return CrashReporter()
    }

    @Provides
    @Singleton
    fun provideFeedbackHelper(): FeedbackHelper {
        return FeedbackHelper()
    }
}
