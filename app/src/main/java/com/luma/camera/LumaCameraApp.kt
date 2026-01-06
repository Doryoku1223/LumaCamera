package com.luma.camera

import android.app.Application
import android.os.StrictMode
import com.luma.camera.crash.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Luma Camera Application
 *
 * 专业级相机应用，面向摄影爱好者
 * 核心特性：
 * - Luma Imaging Engine 影像处理引擎
 * - 120fps 实时预览
 * - LUT 滤镜系统
 * - 实况照片 (Live Photo)
 * - Pro 手动模式
 */
@HiltAndroidApp
class LumaCameraApp : Application() {

    @Inject
    lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        initializeTimber()
        initializeStrictMode()
        initializeCrashReporter()
        initializeComponents()
    }

    private fun initializeTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.d("Luma Camera Application started")
    }

    /**
     * 初始化 StrictMode
     *
     * 在 Debug 模式下检测:
     * - 主线程磁盘读写
     * - 主线程网络操作
     * - 资源泄漏
     * - Activity 泄漏
     */
    private fun initializeStrictMode() {
        if (BuildConfig.DEBUG) {
            // 线程策略: 检测主线程上的违规操作
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()      // 检测磁盘读取
                    .detectDiskWrites()     // 检测磁盘写入
                    .detectNetwork()        // 检测网络操作
                    .detectCustomSlowCalls() // 检测自定义慢调用
                    .penaltyLog()           // 日志记录
                    // 移除 penaltyFlashScreen() - 会导致红色边框闪烁影响用户体验
                    .build()
            )

            // VM 策略: 检测内存泄漏和对象使用
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()  // 检测 SQLite 泄漏
                    .detectLeakedClosableObjects() // 检测可关闭对象泄漏
                    .detectActivityLeaks()         // 检测 Activity 泄漏
                    .detectLeakedRegistrationObjects() // 检测注册对象泄漏
                    .detectFileUriExposure()       // 检测文件 URI 暴露
                    .detectContentUriWithoutPermission() // 检测无权限 ContentUri
                    .penaltyLog()                  // 日志记录
                    .build()
            )

            Timber.d("StrictMode enabled for debug build")
        }
    }

    /**
     * 初始化崩溃报告
     */
    private fun initializeCrashReporter() {
        crashReporter.initialize(this)
        
        // 检查是否有未处理的崩溃报告
        if (crashReporter.hasPendingCrashReports(this)) {
            Timber.w("Found pending crash reports from previous session")
            // TODO: 提示用户发送崩溃报告
        }
        
        Timber.d("CrashReporter initialized")
    }

    private fun initializeComponents() {
        // 预热相机服务
        // 预加载 LUT 到 GPU 显存
        // 初始化 OpenGL 上下文
    }

    companion object {
        @Volatile
        private var instance: LumaCameraApp? = null

        fun getInstance(): LumaCameraApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
