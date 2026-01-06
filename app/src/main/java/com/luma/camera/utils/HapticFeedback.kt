package com.luma.camera.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 触觉反馈管理器
 *
 * 为所有交互提供触觉反馈
 */
@Singleton
class HapticFeedback @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * 轻触反馈 - 用于普通点击
     */
    fun tick() {
        vibrate(VibrationEffect.EFFECT_TICK)
    }

    /**
     * 点击反馈 - 用于按钮点击
     */
    fun click() {
        vibrate(VibrationEffect.EFFECT_CLICK)
    }

    /**
     * 重击反馈 - 用于快门拍照
     */
    fun heavyClick() {
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    /**
     * 双击反馈 - 用于锁定等操作
     */
    fun doubleClick() {
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    /**
     * 成功反馈
     */
    fun success() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 50, 50, 50),
                    intArrayOf(0, 100, 0, 100),
                    -1
                )
            )
        } else {
            vibrate(VibrationEffect.EFFECT_CLICK)
        }
    }

    /**
     * 错误反馈
     */
    fun error() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 100, 100),
                    intArrayOf(0, 255, 0, 255),
                    -1
                )
            )
        } else {
            vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
        }
    }

    private fun vibrate(effect: Int) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createPredefined(effect))
        }
    }
}
