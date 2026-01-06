package com.luma.camera.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.luma.camera.BuildConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户反馈工具类
 *
 * 功能:
 * - 发送邮件反馈
 * - 收集设备信息
 * - 收集应用日志
 */
@Singleton
class FeedbackHelper @Inject constructor() {

    companion object {
        private const val FEEDBACK_EMAIL = "feedback@lumacamera.app"
        private const val SUBJECT_PREFIX = "Luma Camera 反馈"
        private const val SUBJECT_PREFIX_EN = "Luma Camera Feedback"
    }

    /**
     * 发送反馈邮件
     */
    fun sendFeedback(context: Context, useEnglish: Boolean = false) {
        val subject = if (useEnglish) SUBJECT_PREFIX_EN else SUBJECT_PREFIX
        val body = buildFeedbackBody(useEnglish)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            context.startActivity(Intent.createChooser(intent, if (useEnglish) "Send Feedback" else "发送反馈"))
        } catch (e: ActivityNotFoundException) {
            Timber.e(e, "No email client found")
            // 可以显示 Toast 提示用户
        }
    }

    /**
     * 发送 Bug 报告
     */
    fun sendBugReport(context: Context, errorMessage: String? = null, useEnglish: Boolean = false) {
        val subject = if (useEnglish) "Luma Camera Bug Report" else "Luma Camera Bug 报告"
        val body = buildBugReportBody(errorMessage, useEnglish)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            context.startActivity(Intent.createChooser(intent, if (useEnglish) "Report Bug" else "报告问题"))
        } catch (e: ActivityNotFoundException) {
            Timber.e(e, "No email client found")
        }
    }

    /**
     * 发送功能建议
     */
    fun sendFeatureRequest(context: Context, useEnglish: Boolean = false) {
        val subject = if (useEnglish) "Luma Camera Feature Request" else "Luma Camera 功能建议"
        val body = buildFeatureRequestBody(useEnglish)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            context.startActivity(Intent.createChooser(intent, if (useEnglish) "Request Feature" else "提出建议"))
        } catch (e: ActivityNotFoundException) {
            Timber.e(e, "No email client found")
        }
    }

    /**
     * 构建反馈邮件正文
     */
    private fun buildFeedbackBody(useEnglish: Boolean): String {
        val deviceInfo = getDeviceInfo(useEnglish)
        
        return if (useEnglish) {
            """
            |
            |--- Please describe your feedback below ---
            |
            |
            |
            |
            |--- Device Information (Do not modify) ---
            |$deviceInfo
            """.trimMargin()
        } else {
            """
            |
            |--- 请在下方描述您的反馈 ---
            |
            |
            |
            |
            |--- 设备信息 (请勿修改) ---
            |$deviceInfo
            """.trimMargin()
        }
    }

    /**
     * 构建 Bug 报告正文
     */
    private fun buildBugReportBody(errorMessage: String?, useEnglish: Boolean): String {
        val deviceInfo = getDeviceInfo(useEnglish)

        return if (useEnglish) {
            """
            |
            |--- Please describe the bug ---
            |What happened:
            |
            |What you expected:
            |
            |Steps to reproduce:
            |1. 
            |2. 
            |3. 
            |
            |${if (errorMessage != null) "--- Error Message ---\n$errorMessage\n" else ""}
            |--- Device Information (Do not modify) ---
            |$deviceInfo
            """.trimMargin()
        } else {
            """
            |
            |--- 请描述遇到的问题 ---
            |发生了什么:
            |
            |期望的结果:
            |
            |复现步骤:
            |1. 
            |2. 
            |3. 
            |
            |${if (errorMessage != null) "--- 错误信息 ---\n$errorMessage\n" else ""}
            |--- 设备信息 (请勿修改) ---
            |$deviceInfo
            """.trimMargin()
        }
    }

    /**
     * 构建功能建议正文
     */
    private fun buildFeatureRequestBody(useEnglish: Boolean): String {
        val deviceInfo = getDeviceInfo(useEnglish)

        return if (useEnglish) {
            """
            |
            |--- Feature Description ---
            |What feature would you like to see:
            |
            |Why this feature would be useful:
            |
            |
            |--- Device Information (Do not modify) ---
            |$deviceInfo
            """.trimMargin()
        } else {
            """
            |
            |--- 功能描述 ---
            |您希望添加的功能:
            |
            |这个功能的用途:
            |
            |
            |--- 设备信息 (请勿修改) ---
            |$deviceInfo
            """.trimMargin()
        }
    }

    /**
     * 获取设备信息
     */
    private fun getDeviceInfo(useEnglish: Boolean): String {
        return if (useEnglish) {
            """
            |Device: ${Build.MANUFACTURER} ${Build.MODEL}
            |Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            |App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |Build Type: ${BuildConfig.BUILD_TYPE}
            """.trimMargin()
        } else {
            """
            |设备型号: ${Build.MANUFACTURER} ${Build.MODEL}
            |系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            |应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |构建类型: ${BuildConfig.BUILD_TYPE}
            """.trimMargin()
        }
    }

    /**
     * 获取完整的设备信息 (用于崩溃报告)
     */
    fun getFullDeviceInfo(): Map<String, String> {
        return mapOf(
            "device_manufacturer" to Build.MANUFACTURER,
            "device_model" to Build.MODEL,
            "device_brand" to Build.BRAND,
            "device_product" to Build.PRODUCT,
            "android_version" to Build.VERSION.RELEASE,
            "android_sdk" to Build.VERSION.SDK_INT.toString(),
            "app_version_name" to BuildConfig.VERSION_NAME,
            "app_version_code" to BuildConfig.VERSION_CODE.toString(),
            "build_type" to BuildConfig.BUILD_TYPE,
            "supported_abis" to Build.SUPPORTED_ABIS.joinToString(","),
            "display" to Build.DISPLAY,
            "hardware" to Build.HARDWARE
        )
    }

    /**
     * 打开应用商店评分页面
     */
    fun openPlayStoreForRating(context: Context) {
        val packageName = context.packageName
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            // 如果没有安装商店应用，打开网页版
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                )
            )
        }
    }

    /**
     * 打开 GitHub Issues 页面
     */
    fun openGitHubIssues(context: Context) {
        val url = "https://github.com/lumacamera/luma-camera-android/issues"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Timber.e(e, "Cannot open URL")
        }
    }
}
