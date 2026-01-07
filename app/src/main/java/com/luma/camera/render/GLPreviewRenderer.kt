package com.luma.camera.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenGL ES 3.0 预览渲染�?
 *
 * 核心功能�?
 * - 接收相机 SurfaceTexture �?
 * - 实时应用 LUT 滤镜
 * - 可选叠加峰值对�?
 * - 120fps 渲染输出
 *
 * 渲染管线�?
 * Camera OES Texture �?LUT Shader �?(Optional) Focus Peaking �?Output Surface
 */
@Singleton
class GLPreviewRenderer @Inject constructor(
    private val textureManager: TextureManager,
    private val lutShader: LutShaderProgram,
    private val passthroughShader: PassthroughShaderProgram,
    private val focusPeakingShader: FocusPeakingShader,
    private val colorPaletteShader: ColorPaletteShaderProgram,
    private val colorPalette2DShader: ColorPalette2DShaderProgram
) {
    // ==================== EGL 相关 ====================
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    // ==================== 纹理和缓�?====================
    private var cameraTextureId: Int = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cachedCameraSurface: Surface? = null  // 缓存 Surface 避免泄漏
    private var vertexBuffer: FloatBuffer? = null
    
    // 纹理变换矩阵
    private val textureMatrix = FloatArray(16)
    private val adjustedTextureMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    
    // 中间 FBO（用于多 Pass 渲染�?
    private var intermediateFbo: Int = 0
    private var intermediateTexture: Int = 0

    // ==================== 渲染线程 ====================
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    
    // ==================== 状�?====================
    private val isInitialized = AtomicBoolean(false)
    private val isRendering = AtomicBoolean(false)
    
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var cameraBufferWidth: Int = 0
    private var cameraBufferHeight: Int = 0
    
    // ==================== 渲染配置 ====================
    private var currentLutTextureId: Int = 0
    private var currentLutSize: Int = 33
    private var lutIntensity: Float = 1.0f
    private var isLutEnabled: Boolean = false
    private var isFocusPeakingEnabled: Boolean = false
    
    // LUT 纹理缓存（在 GL 线程创建�?
    private val lutTextureCache = mutableMapOf<String, Int>()
    
    // ==================== 调色盘配�?====================
    private var isColorPaletteEnabled: Boolean = false
    private var colorPaletteTemperatureRgb = floatArrayOf(1f, 1f, 1f)
    private var colorPaletteSaturation: Float = 1f
    private var colorPaletteTone: Float = 0f
    
    // 第二个中�?FBO（用�?ColorPalette + LUT �?Pass�?
    private var intermediateFbo2: Int = 0
    private var intermediateTexture2: Int = 0

    // ==================== 状态流 ====================
    private val _rendererState = MutableStateFlow<RendererState>(RendererState.Idle)
    val rendererState: StateFlow<RendererState> = _rendererState.asStateFlow()
    
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    // 帧数据回调（用于直方图分析等�?
    private var frameDataCallback: ((ByteArray, Int, Int) -> Unit)? = null
    private var frameDataSampleCounter = 0
    private val frameDataSampleInterval = 15  // �?5帧采样一�?

    // 帧率计算
    private var frameCount = 0
    private var lastFpsTime = System.nanoTime()
    
    /**
     * 设置帧数据回调（用于直方图分析）
     * 
     * 注意：回调频率较低（�?8fps）以避免性能问题
     */
    fun setFrameDataCallback(callback: ((ByteArray, Int, Int) -> Unit)?) {
        frameDataCallback = callback
    }

    /**
     * 初始化渲染器
     * 
     * @param outputSurface 输出 Surface（来�?TextureView �?SurfaceView�?
     * @param width 渲染宽度
     * @param height 渲染高度
     * @param onInitialized 初始化完成回调，返回相机应输出到�?Surface
     */
    fun initialize(
        outputSurface: Surface, 
        width: Int, 
        height: Int,
        onInitialized: ((Surface) -> Unit)? = null
    ) {
        // 如果已经初始化，先释放资源再重新初始�?
        if (isInitialized.get()) {
            Timber.d("Renderer already initialized, reinitializing...")
            releaseSync()
        }

        surfaceWidth = width
        surfaceHeight = height

        // 创建 GL 线程
        renderThread = HandlerThread("GLRenderThread").apply { start() }
        renderHandler = Handler(renderThread!!.looper)

        renderHandler?.post {
            try {
                initEGL(outputSurface)
                initGL()
                isInitialized.set(true)
                _rendererState.value = RendererState.Ready
                Timber.d("GLPreviewRenderer initialized: ${width}x${height}")
                
                // �?GL 线程初始化完成后，回调通知相机 Surface 已就�?
                onInitialized?.let { callback ->
                    getCameraSurface()?.let { surface ->
                        // 切换到主线程执行回调
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            callback(surface)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize renderer")
                _rendererState.value = RendererState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 获取相机 SurfaceTexture
     * 相机应该将预览帧输出到这�?SurfaceTexture
     */
    fun getCameraSurfaceTexture(): SurfaceTexture? {
        return cameraSurfaceTexture
    }

    /**
     * 获取相机 Surface（使用缓存避免泄漏）
     * 
     * 重要：返回的 Surface �?GLPreviewRenderer 管理其生命周期，
     * 调用者不需要也不应该调�?release()
     */
    fun getCameraSurface(): Surface? {
        val surfaceTexture = cameraSurfaceTexture ?: return null
        
        // 如果缓存�?Surface 有效，直接返�?
        cachedCameraSurface?.let { surface ->
            if (surface.isValid) {
                return surface
            }
            // Surface 已失效，释放并重新创�?
            surface.release()
        }
        
        // 创建新的 Surface 并缓�?
        val newSurface = Surface(surfaceTexture)
        cachedCameraSurface = newSurface
        return newSurface
    }

    /**
     * 释放缓存�?Camera Surface
     */
    private fun releaseCachedCameraSurface() {
        cachedCameraSurface?.let { surface ->
            if (surface.isValid) {
                surface.release()
            }
        }
        cachedCameraSurface = null
    }

    /**
     * 设置 LUT 纹理（使用纹�?ID�?
     * @deprecated 使用 setLutData �?GL 线程上创建纹�?
     */
    fun setLutTexture(textureId: Int, size: Int) {
        renderHandler?.post {
            currentLutTextureId = textureId
            currentLutSize = size
            isLutEnabled = textureId != 0
            Timber.d("LUT texture set: $textureId, size: $size, enabled: $isLutEnabled")
        }
    }
    
    /**
     * 设置 LUT 数据（在 GL 线程上创建纹理）
     * 
     * 这是推荐的方式，确保纹理在正确的 GL 线程上创�?
     * 
     * @param lutId LUT 唯一标识
     * @param size LUT 尺寸（通常 17, 33, �?65�?
     * @param data LUT RGB 数据（FloatArray，大�?= size³ * 3�?
     */
    fun setLutData(lutId: String?, size: Int, data: FloatArray?) {
        renderHandler?.post {
            if (lutId == null || data == null) {
                // 清除 LUT
                currentLutTextureId = 0
                currentLutSize = 33
                isLutEnabled = false
                Timber.d("LUT cleared")
                return@post
            }
            
            // 检查缓�?
            val cachedTextureId = lutTextureCache[lutId]
            if (cachedTextureId != null) {
                currentLutTextureId = cachedTextureId
                currentLutSize = size
                isLutEnabled = true
                Timber.d("Using cached LUT texture: $lutId, textureId=$cachedTextureId")
                return@post
            }
            
            // �?GL 线程上创建纹�?
            val textureId = textureManager.createLutTexture(lutId, size, data)
            lutTextureCache[lutId] = textureId
            
            currentLutTextureId = textureId
            currentLutSize = size
            isLutEnabled = textureId != 0
            Timber.d("Created LUT texture on GL thread: $lutId, textureId=$textureId, size=$size")
        }
    }
    
    /**
     * 清除 LUT 缓存
     */
    fun clearLutCache() {
        renderHandler?.post {
            lutTextureCache.values.forEach { textureId ->
                textureManager.deleteTexture(textureId)
            }
            lutTextureCache.clear()
            Timber.d("LUT cache cleared")
        }
    }

    /**
     * 设置 LUT 强度
     */
    fun setLutIntensity(intensity: Float) {
        lutIntensity = intensity.coerceIn(0f, 1f)
    }

    /**
     * 设置峰值对�?
     */
    fun setFocusPeakingEnabled(enabled: Boolean) {
        isFocusPeakingEnabled = enabled
    }
    
    // ==================== 调色盘控�?====================
    
    /**
     * 设置调色盘启用状�?
     */
    fun setColorPaletteEnabled(enabled: Boolean) {
        isColorPaletteEnabled = enabled
    }
    
    /**
     * 设置色温 RGB 乘数
     * @param rgbMultipliers FloatArray(3) 表示 R, G, B 通道乘数
     */
    fun setColorPaletteTemperature(rgbMultipliers: FloatArray) {
        if (rgbMultipliers.size >= 3) {
            colorPaletteTemperatureRgb = rgbMultipliers.copyOf(3)
        }
    }
    
    /**
     * 设置调色盘饱和度
     * @param saturation 饱和度系�?(0.65 ~ 1.35)
     */
    fun setColorPaletteSaturation(saturation: Float) {
        colorPaletteSaturation = saturation.coerceIn(0.65f, 1.35f)
    }
    
    /**
     * 设置调色盘光�?对比�?
     * @param tone 光影系数 (-1 ~ 1)
     */
    fun setColorPaletteTone(tone: Float) {
        colorPaletteTone = tone.coerceIn(-1f, 1f)
    }
    
    /**
     * 一次性设置所有调色盘参数
     */
    fun setColorPaletteParams(
        enabled: Boolean,
        temperatureRgb: FloatArray,
        saturation: Float,
        tone: Float
    ) {
        isColorPaletteEnabled = enabled
        if (temperatureRgb.size >= 3) {
            colorPaletteTemperatureRgb = temperatureRgb.copyOf(3)
        }
        colorPaletteSaturation = saturation.coerceIn(0.65f, 1.35f)
        colorPaletteTone = tone.coerceIn(-1f, 1f)
    }
    
    /**
     * 获取调色盘是否启�?
     */
    fun isColorPaletteEnabled(): Boolean = isColorPaletteEnabled
    
    /**
     * 获取当前色温 RGB 乘数
     */
    fun getColorPaletteTemperatureRgb(): FloatArray = colorPaletteTemperatureRgb.copyOf()
    
    /**
     * 获取当前饱和度系�?
     */
    fun getColorPaletteSaturation(): Float = colorPaletteSaturation
    
    /**
     * 获取当前光影系数
     */
    fun getColorPaletteTone(): Float = colorPaletteTone
    
    /**
     * 更新调色盘参数（�?ViewModel 调用�?
     * 
     * @param temperatureKelvin 色温（开尔文 2500-9000�?
     * @param saturation 饱和度（-0.35 ~ +0.35�?
     * @param tone 光影�?1 ~ +1�?
     */
    fun updateColorPalette(temperatureKelvin: Float, saturation: Float, tone: Float) {
        renderHandler?.post {
            // 转换色温�?RGB 乘数
            val rgbMultipliers = kelvinToRgbMultipliers(temperatureKelvin)
            colorPaletteTemperatureRgb = rgbMultipliers
            
            // 饱和度：-0.35~+0.35 转换�?0.65~1.35
            colorPaletteSaturation = 1f + saturation
            
            // 光影直接使用
            colorPaletteTone = tone
            
            // 检查是否需要启用调色盘
            val isDefault = temperatureKelvin == 5500f && saturation == 0f && tone == 0f
            isColorPaletteEnabled = !isDefault
            
            // 更新着色器参数
            colorPaletteShader.applyParams(rgbMultipliers, colorPaletteSaturation, tone, isColorPaletteEnabled)
            
            Timber.d("ColorPalette updated: temp=${temperatureKelvin}K, sat=$saturation, tone=$tone, enabled=$isColorPaletteEnabled")
        }
    }
    
    /**
     * 将色温（开尔文）转换为 RGB 乘数
     */
    private fun kelvinToRgbMultipliers(kelvin: Float): FloatArray {
        val temp = kelvin / 100f
        val rgb = FloatArray(3)
        
        // 红色分量
        rgb[0] = if (temp <= 66f) {
            1f
        } else {
            (1.292936186f * Math.pow((temp - 60.0).toDouble(), -0.1332047592).toFloat()).coerceIn(0f, 1f)
        }
        
        // 绿色分量
        rgb[1] = if (temp <= 66f) {
            (0.3900815788f * Math.log(temp.toDouble()).toFloat() - 0.6318414438f).coerceIn(0f, 1f)
        } else {
            (1.129890861f * Math.pow((temp - 60.0).toDouble(), -0.0755148492).toFloat()).coerceIn(0f, 1f)
        }
        
        // 蓝色分量
        rgb[2] = if (temp >= 66f) {
            1f
        } else if (temp <= 19f) {
            0f
        } else {
            (0.5432067891f * Math.log((temp - 10f).toDouble()).toFloat() - 1.1962540892f).coerceIn(0f, 1f)
        }
        
        // 归一化（�?5500K 为基准）
        val baseRgb = floatArrayOf(1f, 0.94f, 0.91f) // 5500K 近似�?
        return floatArrayOf(
            baseRgb[0] / rgb[0].coerceAtLeast(0.001f),
            baseRgb[1] / rgb[1].coerceAtLeast(0.001f),
            baseRgb[2] / rgb[2].coerceAtLeast(0.001f)
        )
    }

    /**
     * 设置峰值对焦颜�?
     */
    fun setFocusPeakingColor(color: FocusPeakingShader.PeakingColor) {
        renderHandler?.post {
            focusPeakingShader.setPeakingColor(color)
        }
    }

    /**
     * 设置峰值对焦阈�?
     */
    fun setFocusPeakingThreshold(threshold: Float) {
        renderHandler?.post {
            focusPeakingShader.setThreshold(threshold)
        }
    }

    /**
     * 请求渲染一�?
     * �?SurfaceTexture.OnFrameAvailableListener 调用
     */
    fun requestRender() {
        if (!isInitialized.get() || !isRendering.get()) return

        renderHandler?.post {
            drawFrame()
        }
    }

    /**
     * 开始渲�?
     */
    fun startRendering() {
        if (!isInitialized.get()) {
            Timber.w("Renderer not initialized")
            return
        }
        
        isRendering.set(true)
        _rendererState.value = RendererState.Rendering
        
        // 设置帧可用回�?
        cameraSurfaceTexture?.setOnFrameAvailableListener({ 
            requestRender() 
        }, renderHandler)
        
        Timber.d("Rendering started")
    }

    /**
     * 停止渲染
     */
    fun stopRendering() {
        isRendering.set(false)
        cameraSurfaceTexture?.setOnFrameAvailableListener(null)
        _rendererState.value = RendererState.Ready
        Timber.d("Rendering stopped")
    }
    
    /**
     * 暂停渲染器（保留 EGL 上下文，只停止渲染）
     * �?Activity onPause 时调�?
     */
    fun onPause() {
        Timber.d("GLPreviewRenderer onPause")
        stopRendering()
        // 保持 EGL 上下文和资源，只停止渲染循环
        // 这样�?onResume 时可以快速恢�?
    }
    
    // 需要重新初始化的回�?
    private var reinitializeCallback: ((Surface) -> Unit)? = null
    private var pendingOutputSurface: Surface? = null
    
    /**
     * 设置重新初始化回�?
     * 当渲染器需要重新初始化时会调用此回�?
     */
    fun setReinitializeCallback(callback: (Surface) -> Unit) {
        reinitializeCallback = callback
    }
    
    /**
     * 检查是否需要重新初始化
     */
    fun needsReinitialization(): Boolean {
        return !isInitialized.get() && pendingOutputSurface != null
    }
    
    /**
     * 恢复渲染�?
     * �?Activity onResume 时调�?
     * 
     * @param outputSurface 可选的输出 Surface，用于在需要时重新初始�?
     */
    fun onResume(outputSurface: Surface? = null) {
        Timber.d("GLPreviewRenderer onResume, isInitialized=${isInitialized.get()}")
        
        // 保存输出 Surface 以便重新初始�?
        if (outputSurface != null) {
}
        
        if (isInitialized.get()) {
            // 如果已经初始化，确保 EGL 上下文是当前�?
            renderHandler?.post {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY && 
                    eglSurface != EGL14.EGL_NO_SURFACE && 
                    eglContext != EGL14.EGL_NO_CONTEXT) {
                    // 检�?EGL Surface 是否仍然有效
                    val width = IntArray(1)
                    val height = IntArray(1)
                    val surfaceValid = EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0) &&
                                       EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0) &&
                                       width[0] > 0 && height[0] > 0
                    
                    if (surfaceValid) {
                        // 重新绑定 EGL 上下�?
                        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                            Timber.e("Failed to make EGL context current on resume, error: ${EGL14.eglGetError()}")
                            // EGL 上下文绑定失败，需要重新初始化
                            triggerReinitialization()
                        } else {
                            Timber.d("EGL context rebound successfully")
                            _rendererState.value = RendererState.Ready
                            // 立即开始渲�?
                            startRendering()
                        }
                    } else {
                        Timber.w("EGL Surface is invalid, triggering reinitialization")
                        // EGL Surface 失效，触发重新初始化
                        triggerReinitialization()
                    }
                } else {
                    Timber.w("EGL not properly initialized, triggering reinitialization")
                    triggerReinitialization()
                }
            }
        } else {
            // 未初始化，检查是否有待处理的 Surface
            if (pendingOutputSurface != null && pendingOutputSurface!!.isValid) {
                Timber.d("Reinitializing with pending surface")
                initialize(pendingOutputSurface!!, surfaceWidth, surfaceHeight) { cameraSurface ->
                    reinitializeCallback?.invoke(cameraSurface)
                }
            }
        }
    }
    
    /**
     * 触发重新初始化流�?
     * 当检测到 EGL Surface 失效时，可以调用此方法重新初始化渲染�?
     */
    fun triggerReinitialization() {
        Timber.d("Triggering reinitialization")
        
        // 先释放旧资源
        releaseGL()
        releaseEGL()
        isInitialized.set(false)
        _rendererState.value = RendererState.Idle
        
        // 如果有待处理�?Surface，重新初始化
        val surface = pendingOutputSurface
        if (surface != null && surface.isValid) {
            Timber.d("Reinitializing with pending surface: ${surfaceWidth}x${surfaceHeight}")
            // 在主线程通知需要重新初始化
            Handler(Looper.getMainLooper()).post {
                initialize(surface, surfaceWidth, surfaceHeight) { cameraSurface ->
                    reinitializeCallback?.invoke(cameraSurface)
                }
            }
        } else {
            Timber.w("No valid pending surface for reinitialization")
        }
    }
    
    /**
     * 检查渲染器是否已初始化且就�?
     */
    fun isReady(): Boolean = isInitialized.get() && cameraSurfaceTexture != null
    fun getOutputSurfaceRatio(): Float? {
        return if (surfaceWidth > 0 && surfaceHeight > 0) {
            surfaceHeight.toFloat() / surfaceWidth.toFloat()
        } else {
            null
        }
    }

    /**
     * 更新输出 Surface 尺寸
     */
    fun updateSurfaceSize(width: Int, height: Int) {
        if (width == surfaceWidth && height == surfaceHeight) return
        
        surfaceWidth = width
        surfaceHeight = height
        renderHandler?.post {
            GLES30.glViewport(0, 0, width, height)
            updateIntermediateFbo()
            updateIntermediateFbo2()
            Timber.d("Surface size updated: ${width}x${height}")
        }
    }

    /**
     * 更新相机输出缓冲尺寸（必须与相机预览尺寸一致）
     */
    fun setCameraBufferSize(width: Int, height: Int) {
        cameraBufferWidth = width
        cameraBufferHeight = height
        val handler = renderHandler
        if (handler != null) {
            handler.post {
                cameraSurfaceTexture?.setDefaultBufferSize(width, height)
                Timber.d("Camera buffer size set: ${width}x${height}")
            }
        } else {
            cameraSurfaceTexture?.setDefaultBufferSize(width, height)
            Timber.d("Camera buffer size set (no handler): ${width}x${height}")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopRendering()
        
        renderHandler?.post {
            releaseGL()
            releaseEGL()
            isInitialized.set(false)
            _rendererState.value = RendererState.Idle
        }

        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        
        Timber.d("GLPreviewRenderer released")
    }
    
    /**
     * 为重新初始化释放资源（同步）
     * 
     * �?Surface 被销毁后恢复时调用，需要完全释�?EGL 资源
     * 以便使用新的 Surface 重新初始�?
     */
    fun releaseForReinit() {
        Timber.d("Releasing for reinitialization...")
        releaseSync()
        Timber.d("GLPreviewRenderer released for reinit")
    }
    
    /**
     * 同步释放资源（用于重新初始化前）
     */
    private fun releaseSync() {
        stopRendering()
        
        // 如果没有 renderHandler，说明还没初始化过，直接返回
        if (renderHandler == null || renderThread == null) {
            Timber.d("No render handler, skipping release")
            isInitialized.set(false)
            _rendererState.value = RendererState.Idle
            return
        }
        
        val latch = java.util.concurrent.CountDownLatch(1)
        renderHandler?.post {
            try {
                releaseGL()
                releaseEGL()
                isInitialized.set(false)
                _rendererState.value = RendererState.Idle
            } finally {
                latch.countDown()
            }
        }
        
        // 等待释放完成，最多等�?500ms
        try {
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Timber.w("Interrupted while waiting for renderer release")
        }
        
        renderThread?.quitSafely()
        try {
            renderThread?.join(200)
        } catch (e: InterruptedException) {
            Timber.w("Interrupted while waiting for render thread to quit")
        }
        renderThread = null
        renderHandler = null
        
        // 清除 LUT 纹理缓存（因�?GL context 已销毁）
        lutTextureCache.clear()
        currentLutTextureId = 0
        isLutEnabled = false
        
        Timber.d("GLPreviewRenderer released synchronously")
    }

    // ==================== 私有方法 ====================

    private fun initEGL(outputSurface: Surface) {
        // 获取 EGL Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Failed to get EGL display")
        }

        // 初始�?EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Failed to initialize EGL")
        }
        Timber.d("EGL version: ${version[0]}.${version[1]}")

        // 选择 EGL 配置
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("Failed to choose EGL config")
        }
        eglConfig = configs[0] ?: throw RuntimeException("No suitable EGL config found")

        // 创建 EGL Context (OpenGL ES 3.0)
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        // 创建 EGL Surface
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGL surface")
        }

        // 绑定 Context
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Failed to make EGL context current")
        }

        Timber.d("EGL initialized successfully")
    }

    private fun initGL() {
        // 打印 GL 信息
        Timber.d("GL_VENDOR: ${GLES30.glGetString(GLES30.GL_VENDOR)}")
        Timber.d("GL_RENDERER: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
        Timber.d("GL_VERSION: ${GLES30.glGetString(GLES30.GL_VERSION)}")

        // 设置视口
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // 禁用不需要的功能
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // 初始�?Shader
        lutShader.initialize()
        passthroughShader.initialize()
        focusPeakingShader.initialize()
        colorPaletteShader.initialize()
        colorPalette2DShader.initialize()

        // 创建相机纹理
        cameraTextureId = textureManager.createOesTexture()
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(surfaceWidth, surfaceHeight)
        }

        // 创建顶点缓冲 - 使用标准纹理坐标，让 SurfaceTexture 的变换矩阵处理方�?
        // 不要预先翻转纹理坐标，因�?textureMatrix 会处理正确的变换
        vertexBuffer = textureManager.createFullscreenQuadBuffer()

        // 创建中间 FBO（用于多 Pass�?
        createIntermediateFbo()
        createIntermediateFbo2()

        // 初始化纹理矩阵为单位矩阵
        Matrix.setIdentityM(textureMatrix, 0)
        Matrix.setIdentityM(adjustedTextureMatrix, 0)
        Matrix.setIdentityM(cropMatrix, 0)
        cameraBufferWidth = surfaceWidth
        cameraBufferHeight = surfaceHeight

        Timber.d("GL initialized successfully")
    }

    private fun createIntermediateFbo() {
        // 创建 FBO
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        intermediateFbo = fbos[0]

        // 创建纹理
        intermediateTexture = textureManager.create2DTexture(surfaceWidth, surfaceHeight)

        // 绑定纹理�?FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            intermediateTexture,
            0
        )

        // 检�?FBO 完整�?
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Timber.e("Framebuffer incomplete: $status")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun updateIntermediateFbo() {
        // 删除旧的
        if (intermediateTexture != 0) {
            textureManager.deleteTexture(intermediateTexture)
        }
        if (intermediateFbo != 0) {
            val fbos = intArrayOf(intermediateFbo)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
        }

        // 重新创建
        createIntermediateFbo()
    }
    
    private fun createIntermediateFbo2() {
        // 创建 FBO
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        intermediateFbo2 = fbos[0]

        // 创建纹理
        intermediateTexture2 = textureManager.create2DTexture(surfaceWidth, surfaceHeight)

        // 绑定纹理�?FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFbo2)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            intermediateTexture2,
            0
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Timber.e("Framebuffer2 incomplete: $status")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
    
    private fun updateIntermediateFbo2() {
        if (intermediateTexture2 != 0) {
            textureManager.deleteTexture(intermediateTexture2)
        }
        if (intermediateFbo2 != 0) {
            val fbos = intArrayOf(intermediateFbo2)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
        }
        createIntermediateFbo2()
    }

    private fun drawFrame() {
        if (!isInitialized.get()) return

        try {
            // 更新相机纹理
            cameraSurfaceTexture?.updateTexImage()
            cameraSurfaceTexture?.getTransformMatrix(textureMatrix)
            updateAdjustedTextureMatrix()

            // 清屏
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            
            // 确定需要哪种渲染流�?
            val needsColorPalette = isColorPaletteEnabled
            val needsLut = isLutEnabled && currentLutTextureId != 0
            val needsFocusPeaking = isFocusPeakingEnabled
            
            when {
                // 最复杂：LUT + ColorPalette + FocusPeaking
                needsColorPalette && needsLut && needsFocusPeaking -> {
                    drawLutToFbo(intermediateFbo)
                    drawColorPalette2DToFbo(intermediateTexture, intermediateFbo2)
                    drawFocusPeakingFromFbo(intermediateTexture2)
                }
                // LUT + ColorPalette
                needsColorPalette && needsLut -> {
                    drawLutToFbo(intermediateFbo)
                    drawColorPalette2DFromFboToScreen(intermediateTexture)
                }
                // ColorPalette + FocusPeaking
                needsColorPalette && needsFocusPeaking -> {
                    drawColorPaletteToFbo(intermediateFbo, intermediateTexture)
                    drawFocusPeakingFromFbo(intermediateTexture)
                }
                // LUT + FocusPeaking
                needsLut && needsFocusPeaking -> {
                    drawToIntermediateFbo()
                    drawWithFocusPeaking()
                }
                // �?ColorPalette
                needsColorPalette -> {
                    drawColorPaletteToScreen()
                }
                // �?LUT
                needsLut -> {
                    drawDirectToScreen()
                }
                // �?FocusPeaking
                needsFocusPeaking -> {
                    drawToIntermediateFbo()
                    drawWithFocusPeaking()
                }
                // 无效果，直接输出
                else -> {
                    drawPassthroughToScreen()
                }
            }

            // 交换缓冲�?
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            // 更新帧率统计
            updateFps()
            
            // 帧数据采样（用于直方图分析）
            frameDataCallback?.let { callback ->
                frameDataSampleCounter++
                if (frameDataSampleCounter >= frameDataSampleInterval) {
                    frameDataSampleCounter = 0
                    sampleFrameData(callback)
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error drawing frame")
        }
    }

    private fun updateAdjustedTextureMatrix() {
        if (cameraBufferWidth <= 0 || cameraBufferHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            System.arraycopy(textureMatrix, 0, adjustedTextureMatrix, 0, textureMatrix.size)
            return
        }

        val surfaceRatio = surfaceHeight.toFloat() / surfaceWidth.toFloat()
        // SurfaceTexture 已包�?90° 旋转，因此这里使用反向比例做裁切
        val bufferRatio = cameraBufferWidth.toFloat() / cameraBufferHeight.toFloat()
        var scaleX = 1f
        var scaleY = 1f

        if (surfaceRatio > bufferRatio) {
            scaleY = bufferRatio / surfaceRatio
        } else if (surfaceRatio < bufferRatio) {
            scaleX = surfaceRatio / bufferRatio
        }

        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1f)
        Matrix.translateM(cropMatrix, 0, -0.5f, -0.5f, 0f)

        Matrix.multiplyMM(adjustedTextureMatrix, 0, textureMatrix, 0, cropMatrix, 0)
    }
    
    /**
     * 采样帧数据用于直方图分析
     */
    private fun sampleFrameData(callback: (ByteArray, Int, Int) -> Unit) {
        try {
            // 使用较小的采样尺寸以提高性能
            val sampleWidth = 160
            val sampleHeight = 90
            val buffer = java.nio.ByteBuffer.allocateDirect(sampleWidth * sampleHeight * 4)
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            
            // 读取帧缓冲数据（下采样）
            GLES30.glReadPixels(
                (surfaceWidth - sampleWidth) / 2,
                (surfaceHeight - sampleHeight) / 2,
                sampleWidth,
                sampleHeight,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                buffer
            )
            
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            callback(bytes, sampleWidth, sampleHeight)
        } catch (e: Exception) {
            Timber.w(e, "Failed to sample frame data")
        }
    }

    private fun drawDirectToScreen() {
        setupVertexAttributes(
            if (isLutEnabled) lutShader.getPositionAttributeLocation() else passthroughShader.getPositionAttributeLocation(),
            if (isLutEnabled) lutShader.getTexCoordAttributeLocation() else passthroughShader.getTexCoordAttributeLocation()
        )

        if (isLutEnabled && currentLutTextureId != 0) {
            lutShader.use()
            lutShader.setCameraTexture(cameraTextureId)
            lutShader.setLutTexture(currentLutTextureId, currentLutSize)
            lutShader.setIntensity(lutIntensity)
            lutShader.setTextureMatrix(adjustedTextureMatrix)
        } else {
            passthroughShader.use()
            passthroughShader.setCameraTexture(cameraTextureId)
            passthroughShader.setTextureMatrix(adjustedTextureMatrix)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawToIntermediateFbo() {
        // 绑定 FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFbo)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // 渲染相机帧（�?LUT�?
        setupVertexAttributes(
            if (isLutEnabled) lutShader.getPositionAttributeLocation() else passthroughShader.getPositionAttributeLocation(),
            if (isLutEnabled) lutShader.getTexCoordAttributeLocation() else passthroughShader.getTexCoordAttributeLocation()
        )

        if (isLutEnabled && currentLutTextureId != 0) {
            lutShader.use()
            lutShader.setCameraTexture(cameraTextureId)
            lutShader.setLutTexture(currentLutTextureId, currentLutSize)
            lutShader.setIntensity(lutIntensity)
            lutShader.setTextureMatrix(adjustedTextureMatrix)
        } else {
            passthroughShader.use()
            passthroughShader.setCameraTexture(cameraTextureId)
            passthroughShader.setTextureMatrix(adjustedTextureMatrix)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 解绑 FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun drawWithFocusPeaking() {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)

        // 使用全屏四边形（不需要纹理变换）
        val fullscreenBuffer = textureManager.createFullscreenQuadBuffer()
        setupVertexAttributes(
            focusPeakingShader.getPositionAttributeLocation(),
            focusPeakingShader.getTexCoordAttributeLocation(),
            fullscreenBuffer
        )

        focusPeakingShader.use()
        focusPeakingShader.setTexture(intermediateTexture)
        focusPeakingShader.setTextureSize(surfaceWidth, surfaceHeight)
        focusPeakingShader.setEnabled(true)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    
    // ==================== ColorPalette 渲染方法 ====================
    
    /**
     * 仅直通渲染到屏幕（无任何效果�?
     */
    private fun drawPassthroughToScreen() {
        setupVertexAttributes(
            passthroughShader.getPositionAttributeLocation(),
            passthroughShader.getTexCoordAttributeLocation()
        )
        
        passthroughShader.use()
        passthroughShader.setCameraTexture(cameraTextureId)
        passthroughShader.setTextureMatrix(adjustedTextureMatrix)
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    
    /**
     * ColorPalette 渲染到屏�?
     */
    private fun drawColorPaletteToScreen() {
        setupVertexAttributes(
            colorPaletteShader.getPositionAttributeLocation(),
            colorPaletteShader.getTexCoordAttributeLocation()
        )
        
        colorPaletteShader.use()
        colorPaletteShader.setCameraTexture(cameraTextureId)
        colorPaletteShader.setTextureMatrix(adjustedTextureMatrix)
        colorPaletteShader.applyParams(
            colorPaletteTemperatureRgb,
            colorPaletteSaturation,
            colorPaletteTone,
            true
        )
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    
    /**
     * ColorPalette 渲染�?FBO
     */
    private fun drawColorPaletteToFbo(fbo: Int, texture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        
        setupVertexAttributes(
            colorPaletteShader.getPositionAttributeLocation(),
            colorPaletteShader.getTexCoordAttributeLocation()
        )
        
        colorPaletteShader.use()
        colorPaletteShader.setCameraTexture(cameraTextureId)
        colorPaletteShader.setTextureMatrix(adjustedTextureMatrix)
        colorPaletteShader.applyParams(
            colorPaletteTemperatureRgb,
            colorPaletteSaturation,
            colorPaletteTone,
            true
        )
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * 先应�?LUT 到中�?FBO（OES 相机输入�?     */
    private fun drawLutToFbo(outputFbo: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        setupVertexAttributes(
            lutShader.getPositionAttributeLocation(),
            lutShader.getTexCoordAttributeLocation()
        )

        lutShader.use()
        lutShader.setCameraTexture(cameraTextureId)
        lutShader.setLutTexture(currentLutTextureId, currentLutSize)
        lutShader.setIntensity(lutIntensity)
        lutShader.setTextureMatrix(adjustedTextureMatrix)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * 使用 2D 纹理应用调色盘到屏幕
     */
    private fun drawColorPalette2DFromFboToScreen(inputTexture: Int) {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)

        val fullscreenBuffer = textureManager.createFullscreenQuadBuffer()
        setupVertexAttributes(
            colorPalette2DShader.getPositionAttributeLocation(),
            colorPalette2DShader.getTexCoordAttributeLocation(),
            fullscreenBuffer
        )

        colorPalette2DShader.use()
        colorPalette2DShader.setTexture(inputTexture)
        colorPalette2DShader.applyParams(
            colorPaletteTemperatureRgb,
            colorPaletteSaturation,
            colorPaletteTone,
            true
        )

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 使用 2D 纹理应用调色盘到 FBO
     */
    private fun drawColorPalette2DToFbo(inputTexture: Int, outputFbo: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val fullscreenBuffer = textureManager.createFullscreenQuadBuffer()
        setupVertexAttributes(
            colorPalette2DShader.getPositionAttributeLocation(),
            colorPalette2DShader.getTexCoordAttributeLocation(),
            fullscreenBuffer
        )

        colorPalette2DShader.use()
        colorPalette2DShader.setTexture(inputTexture)
        colorPalette2DShader.applyParams(
            colorPaletteTemperatureRgb,
            colorPaletteSaturation,
            colorPaletteTone,
            true
        )

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
    
    /**
     * �?FBO 应用 LUT 渲染到屏�?
     */
    private fun drawLutFromFboToScreen(inputTexture: Int) {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        
        // 使用全屏四边形（2D 纹理不需要变换矩阵）
        val fullscreenBuffer = textureManager.createFullscreenQuadBuffer()
        setupVertexAttributes(
            lutShader.getPositionAttributeLocation(),
            lutShader.getTexCoordAttributeLocation(),
            fullscreenBuffer
        )
        
        lutShader.use()
        // 注意：这里需要使�?2D 纹理而不�?OES 纹理
        // 但由�?LutShader 设计为接�?OES，我们使用单位矩阵并直接渲染
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        lutShader.setLutTexture(currentLutTextureId, currentLutSize)
        lutShader.setIntensity(lutIntensity)
        
        // 使用单位矩阵
        val identityMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identityMatrix, 0)
        lutShader.setTextureMatrix(identityMatrix)
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    
    /**
     * �?FBO 应用 LUT 渲染到另一�?FBO
     */
    private fun drawLutFromFboToFbo(inputTexture: Int, outputFbo: Int, outputTexture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        
        val fullscreenBuffer = textureManager.createFullscreenQuadBuffer()
        setupVertexAttributes(
            lutShader.getPositionAttributeLocation(),
            lutShader.getTexCoordAttributeLocation(),
            fullscreenBuffer
        )
        
        lutShader.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        lutShader.setLutTexture(currentLutTextureId, currentLutSize)
        lutShader.setIntensity(lutIntensity)
        
        val identityMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identityMatrix, 0)
        lutShader.setTextureMatrix(identityMatrix)
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
    
    /**
     * �?FBO 应用峰值对焦渲染到屏幕
     */
    private fun drawFocusPeakingFromFbo(inputTexture: Int) {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        
        val fullscreenBuffer = textureManager.createFullscreenQuadBuffer()
        setupVertexAttributes(
            focusPeakingShader.getPositionAttributeLocation(),
            focusPeakingShader.getTexCoordAttributeLocation(),
            fullscreenBuffer
        )
        
        focusPeakingShader.use()
        focusPeakingShader.setTexture(inputTexture)
        focusPeakingShader.setTextureSize(surfaceWidth, surfaceHeight)
        focusPeakingShader.setEnabled(true)
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupVertexAttributes(
        positionLocation: Int,
        texCoordLocation: Int,
        buffer: FloatBuffer? = vertexBuffer
    ) {
        buffer?.let { vb ->
            vb.position(0)
            GLES30.glVertexAttribPointer(
                positionLocation,
                TextureManager.COORDS_PER_VERTEX,
                GLES30.GL_FLOAT,
                false,
                TextureManager.STRIDE,
                vb
            )
            GLES30.glEnableVertexAttribArray(positionLocation)

            vb.position(TextureManager.COORDS_PER_VERTEX)
            GLES30.glVertexAttribPointer(
                texCoordLocation,
                TextureManager.TEX_COORDS_PER_VERTEX,
                GLES30.GL_FLOAT,
                false,
                TextureManager.STRIDE,
                vb
            )
            GLES30.glEnableVertexAttribArray(texCoordLocation)
        }
    }

    private fun updateFps() {
        frameCount++
        val currentTime = System.nanoTime()
        val elapsed = (currentTime - lastFpsTime) / 1_000_000_000f

        if (elapsed >= 1f) {
            _fps.value = frameCount / elapsed
            frameCount = 0
            lastFpsTime = currentTime
        }
    }

    private fun releaseGL() {
        lutShader.release()
        passthroughShader.release()
        focusPeakingShader.release()
        colorPaletteShader.release()
        colorPalette2DShader.release()

        // 先释放缓存的 Surface，再释放 SurfaceTexture
        releaseCachedCameraSurface()
        
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null

        // 清理 LUT 纹理缓存
        lutTextureCache.values.forEach { textureId ->
            textureManager.deleteTexture(textureId)
        }
        lutTextureCache.clear()
        currentLutTextureId = 0
        isLutEnabled = false
        
        // 重置 ColorPalette 状�?
        isColorPaletteEnabled = false
        colorPaletteTemperatureRgb = floatArrayOf(1f, 1f, 1f)
        colorPaletteSaturation = 1f
        colorPaletteTone = 0f

        if (intermediateTexture != 0) {
            textureManager.deleteTexture(intermediateTexture)
            intermediateTexture = 0
        }
        if (intermediateTexture2 != 0) {
            textureManager.deleteTexture(intermediateTexture2)
            intermediateTexture2 = 0
        }
        if (cameraTextureId != 0) {
            textureManager.deleteTexture(cameraTextureId)
            cameraTextureId = 0
        }
        if (intermediateFbo != 0) {
            val fbos = intArrayOf(intermediateFbo)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
            intermediateFbo = 0
        }
        if (intermediateFbo2 != 0) {
            val fbos = intArrayOf(intermediateFbo2)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
            intermediateFbo2 = 0
        }

        textureManager.releaseAll()
    }

    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    /**
     * 渲染器状�?
     */
    sealed class RendererState {
        data object Idle : RendererState()
        data object Ready : RendererState()
        data object Rendering : RendererState()
        data class Error(val message: String) : RendererState()
    }

    companion object {
        /** OpenGL ES 3.0 �?EGL 常量（Android SDK 中未直接暴露�?*/
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    }
}





