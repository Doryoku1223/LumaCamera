package com.luma.camera.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Luma Camera 主题
 *
 * 固定使用深色主题，纯黑背景，金黄色强调
 */

private val LumaDarkColorScheme = darkColorScheme(
    // 主色
    primary = LumaGold,
    onPrimary = LumaBlack,
    primaryContainer = LumaGoldDark,
    onPrimaryContainer = LumaOnBackground,

    // 次要色
    secondary = LumaGoldLight,
    onSecondary = LumaBlack,
    secondaryContainer = LumaSurfaceVariant,
    onSecondaryContainer = LumaOnBackground,

    // 第三色
    tertiary = LumaGold,
    onTertiary = LumaBlack,
    tertiaryContainer = LumaSurfaceVariant,
    onTertiaryContainer = LumaOnBackground,

    // 错误色
    error = LumaError,
    onError = LumaBlack,
    errorContainer = LumaError.copy(alpha = 0.3f),
    onErrorContainer = LumaOnBackground,

    // 背景
    background = LumaBlack,
    onBackground = LumaOnBackground,

    // 表面
    surface = LumaBlack,
    onSurface = LumaOnBackground,
    surfaceVariant = LumaSurfaceVariant,
    onSurfaceVariant = LumaOnBackgroundSecondary,

    // 轮廓
    outline = LumaOutline,
    outlineVariant = LumaOutlineVariant,

    // 反色
    inverseSurface = LumaOnBackground,
    inverseOnSurface = LumaBlack,
    inversePrimary = LumaGoldDark
)

@Composable
fun LumaCameraTheme(
    content: @Composable () -> Unit
) {
    // Luma Camera 固定使用深色主题
    MaterialTheme(
        colorScheme = LumaDarkColorScheme,
        typography = LumaTypography,
        content = content
    )
}
