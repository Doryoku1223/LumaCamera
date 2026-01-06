package com.luma.camera.startup

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.profileinstaller.ProfileInstaller
import androidx.profileinstaller.ProfileInstallerInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baseline Profile Manager
 * 
 * 管理 Baseline Profile 的安装和验证，实现启动路径的 AOT 编译。
 * 
 * Baseline Profile 优势:
 * - 首次启动性能提升 30-40%
 * - 减少 JIT 编译开销
 * - 降低内存使用
 * - 提升电池续航
 * 
 * 使用方法:
 * 1. 在 benchmark 模块中运行 BaselineProfileGenerator
 * 2. 将生成的 baseline-prof.txt 放入 app/src/main/
 * 3. 发布时 AGP 会自动包含到 APK
 */
@Singleton
class BaselineProfileManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "BaselineProfileManager"
    }
    
    private val scope = CoroutineScope(Dispatchers.Default)
    
    /**
     * Profile 安装状态
     */
    sealed class ProfileStatus {
        object NotInstalled : ProfileStatus()
        object Installing : ProfileStatus()
        object Installed : ProfileStatus()
        data class Error(val message: String) : ProfileStatus()
    }
    
    /**
     * 安装 Baseline Profile
     */
    fun installProfile(callback: (ProfileStatus) -> Unit) {
        scope.launch {
            try {
                ProfileInstaller.writeProfile(
                    context,
                    Executor { it.run() },
                    object : ProfileInstaller.DiagnosticsCallback {
                        override fun onDiagnosticReceived(code: Int, data: Any?) {
                            Log.d(TAG, "Profile diagnostic: code=$code, data=$data")
                            // Success codes are typically 1 (success) and 2 (already installed)
                            if (code <= 2) {
                                callback(ProfileStatus.Installed)
                            } else {
                                callback(ProfileStatus.Error("Install failed with code: $code"))
                            }
                        }
                        
                        override fun onResultReceived(code: Int, data: Any?) {
                            Log.d(TAG, "Profile result: code=$code, data=$data")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install profile", e)
                callback(ProfileStatus.Error(e.message ?: "Unknown error"))
            }
        }
    }
    
    /**
     * 检查 Profile 是否已编译
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun isProfileCompiled(): Boolean {
        return try {
            // 检查 APK 中是否包含编译的 profile
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, 0
            )
            
            // 检查 dex2oat 是否已处理
            val oatDir = File(appInfo.dataDir, "oat")
            oatDir.exists() && oatDir.listFiles()?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取 Profile 统计信息
     */
    fun getProfileStats(): ProfileStats {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, 0
            )
            
            val apkFile = File(appInfo.sourceDir)
            val profileFile = File(context.filesDir, "profileinstaller/primary.prof")
            
            ProfileStats(
                apkSizeBytes = apkFile.length(),
                profileExists = profileFile.exists(),
                profileSizeBytes = if (profileFile.exists()) profileFile.length() else 0
            )
        } catch (e: Exception) {
            ProfileStats(0, false, 0)
        }
    }
    
    /**
     * Profile 统计
     */
    data class ProfileStats(
        val apkSizeBytes: Long,
        val profileExists: Boolean,
        val profileSizeBytes: Long
    )
}

/**
 * Startup Trace Helper
 * 
 * 用于记录启动过程中的关键时间点，生成 Perfetto/Systrace 兼容的追踪数据
 */
object StartupTracer {
    private const val TAG = "StartupTracer"
    
    private val startTimes = mutableMapOf<String, Long>()
    private val durations = mutableMapOf<String, Long>()
    
    /**
     * 开始追踪
     */
    fun begin(name: String) {
        startTimes[name] = System.nanoTime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.os.Trace.beginAsyncSection(name, name.hashCode())
        }
    }
    
    /**
     * 结束追踪
     */
    fun end(name: String) {
        startTimes[name]?.let { startTime ->
            durations[name] = (System.nanoTime() - startTime) / 1_000_000 // ns to ms
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.os.Trace.endAsyncSection(name, name.hashCode())
        }
    }
    
    /**
     * 获取持续时间
     */
    fun getDuration(name: String): Long? = durations[name]
    
    /**
     * 获取所有追踪结果
     */
    fun getAllDurations(): Map<String, Long> = durations.toMap()
    
    /**
     * 清除追踪数据
     */
    fun clear() {
        startTimes.clear()
        durations.clear()
    }
    
    /**
     * 打印报告
     */
    fun printReport() {
        Log.d(TAG, "=== Startup Trace Report ===")
        durations.entries
            .sortedByDescending { it.value }
            .forEach { (name, duration) ->
                Log.d(TAG, "$name: ${duration}ms")
            }
        Log.d(TAG, "============================")
    }
}

/**
 * 启动关键路径记录器
 * 
 * 用于生成 Baseline Profile 的关键代码路径
 */
object CriticalPathRecorder {
    private val criticalClasses = mutableSetOf<String>()
    private val criticalMethods = mutableSetOf<String>()
    
    /**
     * 记录关键类
     */
    fun recordClass(clazz: Class<*>) {
        criticalClasses.add(clazz.name)
    }
    
    /**
     * 记录关键方法
     */
    fun recordMethod(className: String, methodName: String) {
        criticalMethods.add("$className.$methodName")
    }
    
    /**
     * 生成 Baseline Profile 规则
     * 
     * 输出格式遵循 Baseline Profile 规范:
     * - HSPLcom/package/Class;->method()V
     * - Lcom/package/Class;
     */
    fun generateProfileRules(): List<String> {
        val rules = mutableListOf<String>()
        
        // 添加类规则
        for (className in criticalClasses) {
            rules.add("L${className.replace('.', '/')};")
        }
        
        // 添加方法规则 (热点方法)
        for (method in criticalMethods) {
            val parts = method.split(".")
            if (parts.size >= 2) {
                val className = parts.dropLast(1).joinToString("/")
                val methodName = parts.last()
                rules.add("HSPLcom/luma/camera/${className};->${methodName}()V")
            }
        }
        
        return rules
    }
    
    /**
     * 获取推荐的 Baseline Profile 内容
     */
    fun getRecommendedProfile(): String {
        return """
            # Luma Camera Baseline Profile
            # Generated for startup optimization
            
            # Application entry points
            HSPLcom/luma/camera/LumaCameraApp;->onCreate()V
            HSPLcom/luma/camera/MainActivity;->onCreate(Landroid/os/Bundle;)V
            
            # Camera initialization
            HSPLcom/luma/camera/camera/CameraController;->initialize()V
            HSPLcom/luma/camera/camera/CameraController;->openCamera()V
            
            # Rendering pipeline
            HSPLcom/luma/camera/render/GpuRenderer;->initialize()V
            HSPLcom/luma/camera/render/GpuRenderer;->createShaderProgram()I
            
            # LUT loading
            HSPLcom/luma/camera/lut/LutManager;->loadBuiltInLuts()V
            HSPLcom/luma/camera/lut/LutManager;->parseCubeFile()V
            
            # UI components
            Lcom/luma/camera/presentation/CameraScreen;
            Lcom/luma/camera/presentation/CameraViewModel;
            Lcom/luma/camera/presentation/components/CameraPreview;
            
            # Imaging engine
            Lcom/luma/camera/imaging/LumaImagingEngine;
            Lcom/luma/camera/processing/ColorFidelityProcessor;
            Lcom/luma/camera/processing/DynamicRangeOptimizer;
            
            # Kotlin coroutines
            Lkotlinx/coroutines/CoroutineDispatcher;
            Lkotlinx/coroutines/Dispatchers;
            
            # Compose UI
            Landroidx/compose/ui/platform/AndroidComposeView;
            Landroidx/compose/runtime/Composer;
        """.trimIndent()
    }
    
    /**
     * 清除记录
     */
    fun clear() {
        criticalClasses.clear()
        criticalMethods.clear()
    }
}
