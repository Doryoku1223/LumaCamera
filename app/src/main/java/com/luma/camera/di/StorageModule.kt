package com.luma.camera.di

import android.content.Context
import com.luma.camera.storage.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 存储模块 Hilt 依赖注入配置
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideMediaStoreHelper(
        @ApplicationContext context: Context
    ): MediaStoreHelper {
        return MediaStoreHelper(context)
    }

    @Provides
    @Singleton
    fun provideExifWriter(
        @ApplicationContext context: Context
    ): ExifWriter {
        return ExifWriter(context)
    }

    @Provides
    @Singleton
    fun provideDngWriter(
        @ApplicationContext context: Context
    ): DngWriter {
        return DngWriter(context)
    }

    @Provides
    @Singleton
    fun provideHeicEncoder(
        @ApplicationContext context: Context
    ): HeicEncoder {
        return HeicEncoder(context)
    }

    @Provides
    @Singleton
    fun provideImageSaver(
        @ApplicationContext context: Context,
        mediaStoreHelper: MediaStoreHelper,
        exifWriter: ExifWriter,
        dngWriter: DngWriter,
        heicEncoder: HeicEncoder
    ): ImageSaver {
        return ImageSaver(
            context,
            mediaStoreHelper,
            exifWriter,
            dngWriter,
            heicEncoder
        )
    }
}
