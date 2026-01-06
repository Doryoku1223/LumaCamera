package com.luma.camera.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Luma Camera 颜色定义
 *
 * 设计规范：
 * - 深色主题，纯黑背景
 * - 强调色：金黄色 #FFD700
 */

// 主色调 - 金黄色
val LumaGold = Color(0xFFFFD700)
val LumaGoldLight = Color(0xFFFFE44D)
val LumaGoldDark = Color(0xFFCCAB00)

// 背景色 - 纯黑
val LumaBlack = Color(0xFF000000)
val LumaSurface = Color(0xFF0D0D0D)
val LumaSurfaceVariant = Color(0xFF1A1A1A)

// 文字颜色
val LumaOnBackground = Color(0xFFFFFFFF)
val LumaOnBackgroundSecondary = Color(0xB3FFFFFF) // 70% 白色
val LumaOnBackgroundTertiary = Color(0x80FFFFFF)  // 50% 白色

// 控件颜色
val LumaOutline = Color(0xFF333333)
val LumaOutlineVariant = Color(0xFF262626)

// 状态颜色
val LumaError = Color(0xFFFF4444)
val LumaSuccess = Color(0xFF44FF44)
val LumaWarning = Color(0xFFFFAA00)

// 半透明叠加层
val LumaOverlay = Color(0x80000000)       // 50% 黑色
val LumaOverlayLight = Color(0x40000000)  // 25% 黑色
val LumaOverlayDark = Color(0xCC000000)   // 80% 黑色

// 峰值对焦颜色选项
val FocusPeakingRed = Color(0xFFFF0000)
val FocusPeakingYellow = Color(0xFFFFFF00)
val FocusPeakingBlue = Color(0xFF0088FF)
val FocusPeakingWhite = Color(0xFFFFFFFF)
