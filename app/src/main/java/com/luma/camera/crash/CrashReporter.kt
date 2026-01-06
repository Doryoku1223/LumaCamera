package com.luma.camera.crash

import android.content.Context
import android.os.Build
import com.luma.camera.BuildConfig
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃报告管理器
 *
 * 功能:
 * - 捕获未处理异常
 * - 收集崩溃上下文
 * - 集成 Firebase Crashlytics (如已配置)
 * - 本地崩溃日志保存
 */
@Singleton
class CrashReporter @Inject constructor() {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    // 崩溃上下文信息
    private val customKeys = mutableMapOf<String, String>()
    private var currentCameraMode: String? = null
    private var currentLut: String? = null
    private var lastAction: String? = null

    /**
     * 初始化崩溃报告
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        
        // 保存默认的异常处理器
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        // 设置自定义异常处理器
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(context, thread, throwable)
        }

        // 设置基本自定义键
        setCustomKey("app_version", BuildConfig.VERSION_NAME)
        setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        setCustomKey("device_model", Build.MODEL)
        setCustomKey("android_version", Build.VERSION.RELEASE)

        isInitialized = true
        Timber.d("CrashReporter initialized")
    }

    /**
     * 设置自定义键值 (用于崩溃上下文)
     */
    fun setCustomKey(key: String, value: String) {
        customKeys[key] = value
        
        // 如果 Firebase Crashlytics 可用，也设置到 Crashlytics
        // FirebaseCrashlytics.getInstance().setCustomKey(key, value)
    }

    /**
     * 设置当前相机模式
     */
    fun setCameraMode(mode: String) {
        currentCameraMode = mode
        setCustomKey("camera_mode", mode)
    }

    /**
     * 设置当前 LUT
     */
    fun setCurrentLut(lutName: String?) {
        currentLut = lutName
        setCustomKey("current_lut", lutName ?: "none")
    }

    /**
     * 记录最后的操作 (用于定位问题)
     */
    fun setLastAction(action: String) {
        lastAction = action
        setCustomKey("last_action", action)
    }

    /**
     * 记录非致命异常
     */
    fun recordException(throwable: Throwable, message: String? = null) {
        val fullMessage = buildString {
            message?.let { append("$it: ") }
            append(throwable.message ?: throwable::class.simpleName)
        }
        
        Timber.e(throwable, fullMessage)
        
        // 如果 Firebase Crashlytics 可用
        // FirebaseCrashlytics.getInstance().recordException(throwable)
    }

    /**
     * 记录日志消息
     */
    fun log(message: String) {
        Timber.d(message)
        
        // 如果 Firebase Crashlytics 可用
        // FirebaseCrashlytics.getInstance().log(message)
    }

    /**
     * 处理未捕获异常
     */
    private fun handleUncaughtException(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ) {
        try {
            // 收集崩溃信息
            val crashReport = buildCrashReport(thread, throwable)
            
            // 保存到本地 (异步保存可能来不及，所以这里用同步)
            saveCrashReportToFile(context, crashReport)
            
            Timber.e(throwable, "Uncaught exception in thread ${thread.name}")
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling crash")
        } finally {
            // 调用默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 构建崩溃报告
     */
    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().also {
            throwable.printStackTrace(PrintWriter(it))
        }.toString()

        return buildString {
            appendLine("=== Luma Camera Crash Report ===")
            appendLine()
            appendLine("--- Timestamp ---")
            appendLine(java.util.Date().toString())
            appendLine()
            appendLine("--- Device Info ---")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
            appendLine()
            appendLine("--- App State ---")
            appendLine("Camera Mode: ${currentCameraMode ?: "unknown"}")
            appendLine("Current LUT: ${currentLut ?: "none"}")
            appendLine("Last Action: ${lastAction ?: "unknown"}")
            appendLine()
            appendLine("--- Custom Keys ---")
            customKeys.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            appendLine()
            appendLine("--- Thread Info ---")
            appendLine("Thread: ${thread.name} (id: ${thread.id})")
            appendLine()
            appendLine("--- Exception ---")
            appendLine("Type: ${throwable::class.qualifiedName}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("--- Stack Trace ---")
            appendLine(stackTrace)
        }
    }

    /**
     * 保存崩溃报告到文件
     */
    private fun saveCrashReportToFile(context: Context, report: String) {
        try {
            val fileName = "crash_${System.currentTimeMillis()}.txt"
            val file = context.getFileStreamPath(fileName)
            file.writeText(report)
            Timber.d("Crash report saved to $fileName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save crash report")
        }
    }

    /**
     * 获取保存的崩溃报告列表
     */
    fun getSavedCrashReports(context: Context): List<String> {
        return context.fileList()
            .filter { it.startsWith("crash_") && it.endsWith(".txt") }
            .sorted()
            .reversed()
    }

    /**
     * 读取崩溃报告内容
     */
    fun readCrashReport(context: Context, fileName: String): String? {
        return try {
            context.openFileInput(fileName).bufferedReader().readText()
        } catch (e: Exception) {
            Timber.e(e, "Failed to read crash report: $fileName")
            null
        }
    }

    /**
     * 删除崩溃报告
     */
    fun deleteCrashReport(context: Context, fileName: String): Boolean {
        return try {
            context.deleteFile(fileName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete crash report: $fileName")
            false
        }
    }

    /**
     * 删除所有崩溃报告
     */
    fun deleteAllCrashReports(context: Context) {
        getSavedCrashReports(context).forEach { fileName ->
            deleteCrashReport(context, fileName)
        }
    }

    /**
     * 检查是否有未发送的崩溃报告
     */
    fun hasPendingCrashReports(context: Context): Boolean {
        return getSavedCrashReports(context).isNotEmpty()
    }
}
