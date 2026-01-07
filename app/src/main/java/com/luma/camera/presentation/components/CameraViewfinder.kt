package com.luma.camera.presentation.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.luma.camera.domain.model.AspectRatio
import com.luma.camera.domain.model.GridType
import com.luma.camera.presentation.theme.LumaGold
import com.luma.camera.render.GLPreviewRenderer
import kotlinx.coroutines.delay

/**
 * 相机取景器组件
 * 
 * 功能：
 * - 120fps 预览渲染
 * - 实时 LUT 滤镜预览
 * - 峰值对焦叠加
 * - 触摸对焦
 * - 网格叠加
 * - 水平仪
 */
@Composable
fun CameraViewfinder(
    modifier: Modifier = Modifier,
    aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    gridType: GridType = GridType.NONE,
    showLevel: Boolean = false,
    levelAngle: Float = 0f,
    glRenderer: GLPreviewRenderer? = null,
    onSurfaceReady: (Surface) -> Unit,
    onGLRendererSurfaceReady: ((Surface) -> Unit)? = null,
    onTouchFocus: (Float, Float, Int, Int) -> Unit,
    onSurfaceDestroyed: (() -> Unit)? = null,
    onPinchZoom: ((Float) -> Unit)? = null
) {
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    
    // 判断是否使用 GL 渲染（始终为 true，除非 glRenderer 为 null）
    val useGLRendering = glRenderer != null && onGLRendererSurfaceReady != null
    
    // 仅当 aspectRatio 改变时强制重组，避免渲染模式切换导致的问题
    key(aspectRatio) {
        Box(
            modifier = modifier
                .aspectRatio(aspectRatio.toFloat())
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        showFocusIndicator = true
                        if (viewWidth > 0 && viewHeight > 0) {
                            onTouchFocus(offset.x, offset.y, viewWidth, viewHeight)
                        }
                    }
                }
        ) {
            // 根据是否有 GLRenderer 决定使用哪种预览模式
            if (useGLRendering) {
                // 使用 GL 渲染的预览（支持实时 LUT 预览和直方图数据）
                GLPreviewTextureView(
                    glRenderer = glRenderer!!,
                    onScreenSurfaceReady = { screenSurface, width, height ->
                        viewWidth = width
                        viewHeight = height
                        // 初始化 GL 渲染器，并在初始化完成后获取相机 Surface
                        glRenderer.initialize(screenSurface, width, height) { cameraSurface ->
                            // 这个回调在 GL 初始化完成后执行
                            glRenderer.startRendering()
                            onGLRendererSurfaceReady!!(cameraSurface)
                        }
                    },
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 直接输出模式（不带 LUT 预览）- 仅作为后备
                AndroidView(
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    viewWidth = width
                                    viewHeight = height
                                    val surface = Surface(surfaceTexture)
                                    onSurfaceReady(surface)
                                }
                                
                                override fun onSurfaceTextureSizeChanged(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    viewWidth = width
                                    viewHeight = height
                                    val surface = Surface(surfaceTexture)
                                    onSurfaceReady(surface)
                                }
                                
                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    onSurfaceDestroyed?.invoke()
                                    return true
                                }
                                
                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                                    // 每帧更新
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        
        // 网格叠加层
        if (gridType != GridType.NONE) {
            GridOverlay(
                gridType = gridType,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // 水平仪
        if (showLevel) {
            LevelIndicator(
                angle = levelAngle,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(200.dp)
            )
        }
        
        // 对焦指示器
        if (showFocusIndicator) {
            focusPoint?.let { point ->
                FocusIndicator(
                    position = point,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // 自动隐藏
            LaunchedEffect(focusPoint) {
                delay(1500)
                showFocusIndicator = false
            }
        }
    }
    } // key(aspectRatio) 结束
}

/**
 * 网格叠加层
 */
@Composable
private fun GridOverlay(
    gridType: GridType,
    modifier: Modifier = Modifier
) {
    val gridColor = Color.White.copy(alpha = 0.4f)
    val strokeWidth = 1f
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        when (gridType) {
            GridType.RULE_OF_THIRDS -> {
                // 三分法网格
                val thirdX = width / 3
                val thirdY = height / 3
                
                // 垂直线
                drawLine(gridColor, Offset(thirdX, 0f), Offset(thirdX, height), strokeWidth)
                drawLine(gridColor, Offset(thirdX * 2, 0f), Offset(thirdX * 2, height), strokeWidth)
                
                // 水平线
                drawLine(gridColor, Offset(0f, thirdY), Offset(width, thirdY), strokeWidth)
                drawLine(gridColor, Offset(0f, thirdY * 2), Offset(width, thirdY * 2), strokeWidth)
            }
            
            GridType.GRID_4X4 -> {
                val cellX = width / 4
                val cellY = height / 4
                
                for (i in 1..3) {
                    drawLine(gridColor, Offset(cellX * i, 0f), Offset(cellX * i, height), strokeWidth)
                    drawLine(gridColor, Offset(0f, cellY * i), Offset(width, cellY * i), strokeWidth)
                }
            }
            
            GridType.GOLDEN_RATIO -> {
                // 黄金分割 (1:1.618)
                val phi = 1.618f
                val goldenX1 = width / (1 + phi)
                val goldenX2 = width - goldenX1
                val goldenY1 = height / (1 + phi)
                val goldenY2 = height - goldenY1
                
                drawLine(gridColor, Offset(goldenX1, 0f), Offset(goldenX1, height), strokeWidth)
                drawLine(gridColor, Offset(goldenX2, 0f), Offset(goldenX2, height), strokeWidth)
                drawLine(gridColor, Offset(0f, goldenY1), Offset(width, goldenY1), strokeWidth)
                drawLine(gridColor, Offset(0f, goldenY2), Offset(width, goldenY2), strokeWidth)
            }
            
            GridType.DIAGONAL -> {
                // 对角线
                drawLine(gridColor, Offset(0f, 0f), Offset(width, height), strokeWidth)
                drawLine(gridColor, Offset(width, 0f), Offset(0f, height), strokeWidth)
            }
            
            GridType.CENTER_CROSS -> {
                // 中心十字
                val centerX = width / 2
                val centerY = height / 2
                
                drawLine(gridColor, Offset(centerX, 0f), Offset(centerX, height), strokeWidth)
                drawLine(gridColor, Offset(0f, centerY), Offset(width, centerY), strokeWidth)
            }
            
            GridType.NONE -> { /* 不绘制 */ }
        }
    }
}

/**
 * 对焦指示器
 */
@Composable
private fun FocusIndicator(
    position: Offset,
    modifier: Modifier = Modifier
) {
    val indicatorSize = with(LocalDensity.current) { 70.dp.toPx() }
    
    Canvas(modifier = modifier) {
        // 外圈
        drawCircle(
            color = LumaGold,
            radius = indicatorSize / 2,
            center = position,
            style = Stroke(width = 2f)
        )
        
        // 内圈
        drawCircle(
            color = LumaGold,
            radius = indicatorSize / 4,
            center = position,
            style = Stroke(width = 1.5f)
        )
        
        // 中心点
        drawCircle(
            color = LumaGold,
            radius = 3f,
            center = position
        )
    }
}

/**
 * 水平仪指示器
 */
@Composable
private fun LevelIndicator(
    angle: Float,
    modifier: Modifier = Modifier
) {
    val isLevel = kotlin.math.abs(angle) < 1f
    val indicatorColor = if (isLevel) LumaGold else Color.White.copy(alpha = 0.6f)
    
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val lineLength = size.width / 3
        
        // 计算旋转后的端点
        val radians = Math.toRadians(angle.toDouble()).toFloat()
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        
        val startX = centerX - lineLength / 2 * cos
        val startY = centerY - lineLength / 2 * sin
        val endX = centerX + lineLength / 2 * cos
        val endY = centerY + lineLength / 2 * sin
        
        // 水平线
        drawLine(
            color = indicatorColor,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 2f
        )
        
        // 参考水平线
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(centerX - lineLength / 2, centerY),
            end = Offset(centerX + lineLength / 2, centerY),
            strokeWidth = 1f
        )
        
        // 中心指示器
        drawCircle(
            color = indicatorColor,
            radius = 6f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2f)
        )
    }
}

/**
 * AspectRatio 扩展 - 返回竖屏模式下的宽高比 (width/height)
 * 例如 16:9 在竖屏下是 9/16 = 0.5625
 */
private fun AspectRatio.toFloat(): Float = when (this) {
    AspectRatio.RATIO_16_9 -> 9f / 16f   // 竖屏 16:9
    AspectRatio.RATIO_4_3 -> 3f / 4f     // 竖屏 4:3
    AspectRatio.RATIO_1_1 -> 1f          // 正方形
    AspectRatio.RATIO_FULL -> 9f / 19.5f // 全屏接近手机屏幕
}

/**
 * 使用 GLPreviewRenderer 渲染的 TextureView
 */
@Composable
private fun GLPreviewTextureView(
    glRenderer: GLPreviewRenderer,
    onScreenSurfaceReady: (Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 保存 Surface 引用以便正确释放
    var screenSurface: Surface? by remember { mutableStateOf(null) }
    // 记录是否已经初始化过，避免重复初始化
    var hasInitialized by remember { mutableStateOf(false) }
    // 记录上次的尺寸
    var lastWidth by remember { mutableIntStateOf(0) }
    var lastHeight by remember { mutableIntStateOf(0) }
    
    DisposableEffect(Unit) {
        onDispose {
            // 在组件销毁时释放 Surface
            screenSurface?.let { surface ->
                if (surface.isValid) {
                    surface.release()
                }
            }
            screenSurface = null
            hasInitialized = false
        }
    }
    
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                // 确保 TextureView 可以接收 Surface 事件
                isOpaque = true
                
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        timber.log.Timber.d("onSurfaceTextureAvailable: ${width}x${height}, wasInitialized=$hasInitialized")
                        
                        // 先释放旧的 Surface（如果有）
                        screenSurface?.let { oldSurface ->
                            if (oldSurface.isValid) {
                                timber.log.Timber.d("Releasing old surface")
                                oldSurface.release()
                            }
                        }
                        
                        // 创建新的 Surface
                        val newSurface = Surface(surfaceTexture)
                        screenSurface = newSurface
                        lastWidth = width
                        lastHeight = height
                        
                        // 无论之前是否初始化过，都需要重新初始化
                        // 因为 Surface 被销毁后，之前的 EGL Surface 已失效
                        if (hasInitialized) {
                            // 曾经初始化过但 Surface 被销毁了，需要完全重新初始化
                            timber.log.Timber.d("Surface restored, triggering GL renderer reinitialization")
                            // 先同步释放旧的 GL 资源
                            glRenderer.releaseForReinit()
                        }
                        
                        // 初始化/重新初始化 GL 渲染器
                        timber.log.Timber.d("Initializing GL renderer with new surface")
                        onScreenSurfaceReady(newSurface, width, height)
                        hasInitialized = true
                    }
                    
                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        if (width != lastWidth || height != lastHeight) {
                            lastWidth = width
                            lastHeight = height
                            glRenderer.updateSurfaceSize(width, height)
                        }
                    }
                    
                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        timber.log.Timber.d("onSurfaceTextureDestroyed, pausing GL renderer")
                        onSurfaceDestroyed?.invoke()
                        // 暂停渲染但不完全释放，以便快速恢复
                        glRenderer.onPause()
                        
                        // 释放 Surface - 但保留 hasInitialized 状态以便恢复时知道需要重新初始化
                        screenSurface?.let { surface ->
                            if (surface.isValid) {
                                surface.release()
                            }
                        }
                        screenSurface = null
                        // 不要重置 hasInitialized，这样恢复时知道需要重新初始化
                        
                        // 返回 true 让系统释放 SurfaceTexture
                        return true
                    }
                    
                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        // GL 渲染器自己管理帧更新
                    }
                }
            }
        },
        modifier = modifier
    )
}
