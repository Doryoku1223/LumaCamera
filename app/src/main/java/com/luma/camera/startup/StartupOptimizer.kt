package com.luma.camera.startup

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.Trace
import android.util.Log
import androidx.core.os.TraceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup Optimizer
 * 
 * 冷启动优化管理器，目标 < 400ms 完成首帧渲染。
 * 
 * 优化策略:
 * 1. 延迟初始化 - 非关键组件延后加载
 * 2. 并行初始化 - 独立组件并行加载
 * 3. 预热机制 - 预热 Camera2 API、GL 上下文
 * 4. 资源预加载 - 关键资源异步预加载
 * 5. Baseline Profile - 启动路径 AOT 编译
 */
@Singleton
class StartupOptimizer @Inject constructor(
    private val application: Application
) {
    companion object {
        private const val TAG = "StartupOptimizer"
        
        // 启动阶段超时
        private const val CRITICAL_INIT_TIMEOUT_MS = 100L
        private const val ASYNC_INIT_TIMEOUT_MS = 500L
        
        // 性能指标
        private const val TARGET_COLD_START_MS = 400L
        private const val TARGET_FIRST_FRAME_MS = 200L
    }
    
    // 初始化状态
    private val criticalInitCompleted = AtomicBoolean(false)
    private val asyncInitCompleted = AtomicBoolean(false)
    
    // 性能追踪
    private val startupStartTime = AtomicLong(0)
    private val criticalInitTime = AtomicLong(0)
    private val firstFrameTime = AtomicLong(0)
    
    // 协程作用域
    private val startupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 初始化任务列表
    private val criticalTasks = mutableListOf<InitTask>()
    private val asyncTasks = mutableListOf<InitTask>()
    private val idleTasks = mutableListOf<InitTask>()
    
    /**
     * 初始化任务
     */
    data class InitTask(
        val name: String,
        val priority: Priority,
        val dependencies: List<String> = emptyList(),
        val task: suspend () -> Unit
    )
    
    /**
     * 任务优先级
     */
    enum class Priority {
        CRITICAL,   // 阻塞启动，必须在 onCreate 完成前执行
        ASYNC,      // 异步执行，可在启动后并行
        IDLE        // 延迟执行，在空闲时执行
    }
    
    /**
     * 启动指标
     */
    data class StartupMetrics(
        val totalStartupMs: Long,
        val criticalInitMs: Long,
        val firstFrameMs: Long,
        val asyncInitMs: Long,
        val taskTimings: Map<String, Long>,
        val targetMet: Boolean
    )
    
    /**
     * 注册初始化任务
     */
    fun registerTask(task: InitTask) {
        when (task.priority) {
            Priority.CRITICAL -> criticalTasks.add(task)
            Priority.ASYNC -> asyncTasks.add(task)
            Priority.IDLE -> idleTasks.add(task)
        }
    }
    
    /**
     * 执行关键初始化 (同步)
     * 应在 Application.onCreate() 中调用
     */
    fun executeCriticalInit() {
        startupStartTime.set(System.currentTimeMillis())
        TraceCompat.beginSection("LumaCamera.criticalInit")
        
        try {
            // 按依赖顺序执行关键任务
            val sortedTasks = topologicalSort(criticalTasks)
            
            for (task in sortedTasks) {
                TraceCompat.beginSection("init.${task.name}")
                try {
                    // 使用 runBlocking 但设置超时
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(CRITICAL_INIT_TIMEOUT_MS) {
                            task.task()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Critical init failed: ${task.name}", e)
                } finally {
                    TraceCompat.endSection()
                }
            }
            
            criticalInitCompleted.set(true)
            criticalInitTime.set(System.currentTimeMillis() - startupStartTime.get())
            
        } finally {
            TraceCompat.endSection()
        }
        
        // 启动异步初始化
        startAsyncInit()
    }
    
    /**
     * 启动异步初始化
     */
    private fun startAsyncInit() {
        startupScope.launch {
            TraceCompat.beginSection("LumaCamera.asyncInit")
            
            try {
                // 并行执行异步任务
                val jobs = asyncTasks.map { task ->
                    async {
                        TraceCompat.beginSection("async.${task.name}")
                        try {
                            task.task()
                        } catch (e: Exception) {
                            Log.e(TAG, "Async init failed: ${task.name}", e)
                        } finally {
                            TraceCompat.endSection()
                        }
                    }
                }
                
                // 等待所有任务完成 (带超时)
                kotlinx.coroutines.withTimeoutOrNull(ASYNC_INIT_TIMEOUT_MS) {
                    jobs.forEach { it.await() }
                }
                
                asyncInitCompleted.set(true)
                
            } finally {
                TraceCompat.endSection()
            }
            
            // 调度空闲任务
            scheduleIdleTasks()
        }
    }
    
    /**
     * 调度空闲任务
     */
    private fun scheduleIdleTasks() {
        val handler = Handler(Looper.getMainLooper())
        
        // 使用 postDelayed 在主线程空闲时执行
        handler.postDelayed({
            startupScope.launch(Dispatchers.Default) {
                for (task in idleTasks) {
                    try {
                        task.task()
                    } catch (e: Exception) {
                        Log.e(TAG, "Idle init failed: ${task.name}", e)
                    }
                }
            }
        }, 1000) // 启动后 1 秒执行
    }
    
    /**
     * 记录首帧渲染完成
     */
    fun onFirstFrameRendered() {
        firstFrameTime.set(System.currentTimeMillis() - startupStartTime.get())
        Log.d(TAG, "First frame rendered in ${firstFrameTime.get()}ms")
    }
    
    /**
     * 获取启动指标
     */
    fun getStartupMetrics(): StartupMetrics {
        val totalTime = System.currentTimeMillis() - startupStartTime.get()
        
        return StartupMetrics(
            totalStartupMs = totalTime,
            criticalInitMs = criticalInitTime.get(),
            firstFrameMs = firstFrameTime.get(),
            asyncInitMs = totalTime - criticalInitTime.get(),
            taskTimings = emptyMap(), // TODO: 记录每个任务的耗时
            targetMet = firstFrameTime.get() > 0 && firstFrameTime.get() <= TARGET_COLD_START_MS
        )
    }
    
    /**
     * 拓扑排序 (处理依赖关系)
     */
    private fun topologicalSort(tasks: List<InitTask>): List<InitTask> {
        val sorted = mutableListOf<InitTask>()
        val visited = mutableSetOf<String>()
        val taskMap = tasks.associateBy { it.name }
        
        fun visit(task: InitTask) {
            if (task.name in visited) return
            visited.add(task.name)
            
            for (dep in task.dependencies) {
                taskMap[dep]?.let { visit(it) }
            }
            
            sorted.add(task)
        }
        
        for (task in tasks) {
            visit(task)
        }
        
        return sorted
    }
    
    /**
     * 检查是否完成关键初始化
     */
    fun isCriticalInitCompleted(): Boolean = criticalInitCompleted.get()
    
    /**
     * 检查是否完成全部初始化
     */
    fun isFullyInitialized(): Boolean = asyncInitCompleted.get()
}

/**
 * 预热管理器
 * 
 * 预热关键系统组件以减少首次使用延迟
 */
@Singleton
class WarmupManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "WarmupManager"
    }
    
    /**
     * 预热 Camera2 API
     */
    suspend fun warmupCamera() {
        TraceCompat.beginSection("warmup.camera")
        try {
            // 获取 CameraManager 实例 (触发初始化)
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) 
                as android.hardware.camera2.CameraManager
            
            // 枚举相机 (触发 HAL 初始化)
            val cameraIds = cameraManager.cameraIdList
            
            // 获取第一个后置相机的特性 (预加载)
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                )
                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera warmup failed", e)
        } finally {
            TraceCompat.endSection()
        }
    }
    
    /**
     * 预热 OpenGL
     */
    suspend fun warmupOpenGL() {
        TraceCompat.beginSection("warmup.opengl")
        try {
            // 创建临时 EGL 上下文
            val eglDisplay = android.opengl.EGL14.eglGetDisplay(
                android.opengl.EGL14.EGL_DEFAULT_DISPLAY
            )
            
            val version = IntArray(2)
            android.opengl.EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
            
            // 清理
            android.opengl.EGL14.eglTerminate(eglDisplay)
            
        } catch (e: Exception) {
            Log.e(TAG, "OpenGL warmup failed", e)
        } finally {
            TraceCompat.endSection()
        }
    }
    
    /**
     * 预加载关键资源
     */
    suspend fun preloadResources() {
        TraceCompat.beginSection("warmup.resources")
        try {
            // 预加载 UI 图标
            // 预加载默认 LUT
            // 预加载字体
        } finally {
            TraceCompat.endSection()
        }
    }
    
    /**
     * 预热 JIT 编译
     */
    suspend fun warmupJit() {
        TraceCompat.beginSection("warmup.jit")
        try {
            // 触发关键代码路径的 JIT 编译
            // 这些操作会被 Baseline Profile 记录
            
            // 模拟常见操作
            val buffer = java.nio.ByteBuffer.allocateDirect(1024)
            buffer.clear()
            
            // 触发常用数学运算
            var sum = 0.0
            for (i in 0 until 1000) {
                sum += kotlin.math.sin(i.toDouble())
            }
            
        } finally {
            TraceCompat.endSection()
        }
    }
}

/**
 * 内存优化管理器
 */
@Singleton
class MemoryOptimizer @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "MemoryOptimizer"
        
        // 内存阈值
        private const val LOW_MEMORY_THRESHOLD_MB = 100
        private const val CRITICAL_MEMORY_THRESHOLD_MB = 50
    }
    
    /**
     * 获取可用内存 (MB)
     */
    fun getAvailableMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return maxMemory - usedMemory
    }
    
    /**
     * 检查是否低内存
     */
    fun isLowMemory(): Boolean {
        return getAvailableMemoryMb() < LOW_MEMORY_THRESHOLD_MB
    }
    
    /**
     * 检查是否临界低内存
     */
    fun isCriticalMemory(): Boolean {
        return getAvailableMemoryMb() < CRITICAL_MEMORY_THRESHOLD_MB
    }
    
    /**
     * 请求垃圾回收
     */
    fun requestGc() {
        System.gc()
    }
    
    /**
     * 清理缓存
     */
    fun clearCaches() {
        // 清理图像缓存
        // 清理 LUT 缓存
        // 清理临时文件
    }
    
    /**
     * 获取内存使用报告
     */
    fun getMemoryReport(): MemoryReport {
        val runtime = Runtime.getRuntime()
        
        return MemoryReport(
            maxMemoryMb = runtime.maxMemory() / (1024 * 1024),
            totalMemoryMb = runtime.totalMemory() / (1024 * 1024),
            freeMemoryMb = runtime.freeMemory() / (1024 * 1024),
            usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
            nativeHeapMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        )
    }
    
    /**
     * 内存报告
     */
    data class MemoryReport(
        val maxMemoryMb: Long,
        val totalMemoryMb: Long,
        val freeMemoryMb: Long,
        val usedMemoryMb: Long,
        val nativeHeapMb: Long
    )
}
