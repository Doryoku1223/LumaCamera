package com.luma.camera.di

import android.content.Context
import com.luma.camera.render.ColorPaletteShaderProgram
import com.luma.camera.render.ColorPalette2DShaderProgram
import com.luma.camera.render.FocusPeakingShader
import com.luma.camera.render.GLPreviewRenderer
import com.luma.camera.render.LutShaderProgram
import com.luma.camera.render.PassthroughShaderProgram
import com.luma.camera.render.TextureManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 渲染模块 DI 配置
 *
 * 提供 GPU 渲染相关的依赖注入
 */
@Module
@InstallIn(SingletonComponent::class)
object RenderModule {

    @Provides
    @Singleton
    fun provideTextureManager(): TextureManager {
        return TextureManager()
    }

    @Provides
    @Singleton
    fun provideLutShaderProgram(
        @ApplicationContext context: Context
    ): LutShaderProgram {
        return LutShaderProgram(context)
    }

    @Provides
    @Singleton
    fun providePassthroughShaderProgram(
        @ApplicationContext context: Context
    ): PassthroughShaderProgram {
        return PassthroughShaderProgram(context)
    }

    @Provides
    @Singleton
    fun provideFocusPeakingShader(
        @ApplicationContext context: Context
    ): FocusPeakingShader {
        return FocusPeakingShader(context)
    }

    @Provides
    @Singleton
    fun provideColorPaletteShaderProgram(
        @ApplicationContext context: Context
    ): ColorPaletteShaderProgram {
        return ColorPaletteShaderProgram(context)
    }
    
    @Provides
    @Singleton
    fun provideColorPalette2DShaderProgram(
        @ApplicationContext context: Context
    ): ColorPalette2DShaderProgram {
        return ColorPalette2DShaderProgram(context)
    }

    @Provides
    @Singleton
    fun provideGLPreviewRenderer(
        textureManager: TextureManager,
        lutShader: LutShaderProgram,
        passthroughShader: PassthroughShaderProgram,
        focusPeakingShader: FocusPeakingShader,
        colorPaletteShader: ColorPaletteShaderProgram,
        colorPalette2DShader: ColorPalette2DShaderProgram
    ): GLPreviewRenderer {
        return GLPreviewRenderer(
            textureManager,
            lutShader,
            passthroughShader,
            focusPeakingShader,
            colorPaletteShader,
            colorPalette2DShader
        )
    }
}
