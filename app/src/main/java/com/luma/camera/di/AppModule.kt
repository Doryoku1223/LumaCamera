package com.luma.camera.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.luma.camera.data.repository.ColorPaletteRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * 应用级别 DI 模块
 */

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "luma_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.dataStore
    }
    
    @Provides
    @Singleton
    @Named("colorPalettePrefs")
    fun provideColorPaletteSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return context.getSharedPreferences("color_palette_presets", Context.MODE_PRIVATE)
    }
    
    @Provides
    @Singleton
    fun provideColorPaletteRepository(
        @Named("colorPalettePrefs") sharedPreferences: SharedPreferences
    ): ColorPaletteRepository {
        return ColorPaletteRepository(sharedPreferences)
    }
}
