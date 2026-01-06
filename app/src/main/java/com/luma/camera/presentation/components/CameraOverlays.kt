package com.luma.camera.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.camera.presentation.theme.LumaGold

/**
 * 直方图显示组件
 * 
 * 实时显示 RGB 亮度分布
 * 自动适配深色/浅色主题
 */
@Composable
fun HistogramView(
    redChannel: FloatArray,
    greenChannel: FloatArray,
    blueChannel: FloatArray,
    luminance: FloatArray,
    modifier: Modifier = Modifier,
    showRGB: Boolean = true
) {
    // 根据主题自动调整背景颜色
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) {
        // 深色主题：使用带透明度的深灰色背景，与黑色页面形成对比
        Color(0xFF2A2A2A).copy(alpha = 0.85f)
    } else {
        // 浅色主题：使用带透明度的深色背景，确保直方图可见
        Color(0xFF1A1A1A).copy(alpha = 0.75f)
    }
    val borderColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.2f)
    } else {
        Color.White.copy(alpha = 0.3f)
    }
    
    Box(
        modifier = modifier
            .background(
                backgroundColor,
                RoundedCornerShape(8.dp)
            )
            .then(
                Modifier.drawBehind {
                    // 绘制细边框以增强可见性
                    drawRoundRect(
                        color = borderColor,
                        style = Stroke(width = 1.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                }
            )
            .padding(8.dp)
    ) {
        // 检查是否有有效的直方图数据
        val hasData = luminance.any { it > 0f } || 
                      redChannel.any { it > 0f } || 
                      greenChannel.any { it > 0f } || 
                      blueChannel.any { it > 0f }
        
        if (!hasData) {
            // 没有数据时显示占位提示
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "等待数据...",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                
                // 计算最大值用于归一化
                val maxValue = maxOf(
                    redChannel.maxOrNull() ?: 1f,
                    greenChannel.maxOrNull() ?: 1f,
                    blueChannel.maxOrNull() ?: 1f,
                    luminance.maxOrNull() ?: 1f
                ).coerceAtLeast(1f)
                
                // 绘制亮度直方图 (白色填充)
                drawHistogramPath(luminance, width, height, maxValue, Color.White.copy(alpha = 0.3f), fill = true)
                
                if (showRGB) {
                    // 绘制 RGB 通道 (描边)
                    drawHistogramPath(redChannel, width, height, maxValue, Color.Red.copy(alpha = 0.7f), fill = false)
                    drawHistogramPath(greenChannel, width, height, maxValue, Color.Green.copy(alpha = 0.7f), fill = false)
                    drawHistogramPath(blueChannel, width, height, maxValue, Color.Blue.copy(alpha = 0.7f), fill = false)
                }
            }
        }
    }
}

/**
 * 绘制直方图路径
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramPath(
    data: FloatArray,
    width: Float,
    height: Float,
    maxValue: Float,
    color: Color,
    fill: Boolean
) {
    if (data.isEmpty()) return
    
    val path = Path()
    val stepX = width / (data.size - 1)
    
    path.moveTo(0f, height)
    
    data.forEachIndexed { index, value ->
        val x = index * stepX
        val y = height - (value / maxValue * height)
        
        if (index == 0) {
            path.lineTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    
    if (fill) {
        path.lineTo(width, height)
        path.close()
        drawPath(path, color)
    } else {
        drawPath(path, color, style = Stroke(width = 1.5f))
    }
}

/**
 * 峰值对焦叠加层
 * 
 * 显示对焦清晰区域的边缘高亮
 */
@Composable
fun FocusPeakingOverlay(
    peakingData: Array<IntArray>?,
    color: Color = LumaGold,
    modifier: Modifier = Modifier
) {
    if (peakingData == null) return
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        peakingData.forEachIndexed { y, row ->
            row.forEachIndexed { x, intensity ->
                if (intensity > 0) {
                    val posX = x.toFloat() / peakingData[0].size * width
                    val posY = y.toFloat() / peakingData.size * height
                    val alpha = (intensity / 255f).coerceIn(0f, 1f)
                    
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = 2f,
                        center = Offset(posX, posY)
                    )
                }
            }
        }
    }
}

/**
 * 斑马纹叠加层
 * 
 * 显示过曝/欠曝区域
 */
@Composable
fun ZebraOverlay(
    overexposedAreas: List<Offset>?,
    underexposedAreas: List<Offset>?,
    viewWidth: Float,
    viewHeight: Float,
    modifier: Modifier = Modifier
) {
    // 动画相位
    val phase by rememberInfiniteTransition(label = "zebraPhase").animateFloat(
        initialValue = 0f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "zebraAnimation"
    )
    
    Canvas(modifier = modifier) {
        val stripeWidth = 4f
        val stripeSpacing = 8f
        
        // 过曝区域 - 红色斑马纹
        overexposedAreas?.forEach { point ->
            val x = point.x * size.width / viewWidth
            val y = point.y * size.height / viewHeight
            
            drawLine(
                color = Color.Red.copy(alpha = 0.7f),
                start = Offset(x - stripeWidth + phase % stripeSpacing, y - stripeWidth),
                end = Offset(x + stripeWidth + phase % stripeSpacing, y + stripeWidth),
                strokeWidth = 2f
            )
        }
        
        // 欠曝区域 - 蓝色斑马纹
        underexposedAreas?.forEach { point ->
            val x = point.x * size.width / viewWidth
            val y = point.y * size.height / viewHeight
            
            drawLine(
                color = Color.Blue.copy(alpha = 0.7f),
                start = Offset(x - stripeWidth + phase % stripeSpacing, y - stripeWidth),
                end = Offset(x + stripeWidth + phase % stripeSpacing, y + stripeWidth),
                strokeWidth = 2f
            )
        }
    }
}

/**
 * LUT 预览缩略图
 */
@Composable
fun LutPreviewThumbnail(
    lutName: String,
    previewBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                if (isSelected) LumaGold.copy(alpha = 0.2f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 预览图
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    Color.Gray.copy(alpha = 0.3f),
                    RoundedCornerShape(6.dp)
                )
        ) {
            previewBitmap?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = lutName,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // 选中边框
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .padding(2.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = LumaGold,
                            style = Stroke(width = 2f),
                            size = size
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // LUT 名称
        Text(
            text = lutName,
            color = if (isSelected) LumaGold else Color.White,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * 滤镜强度滑块
 */
@Composable
fun FilterIntensitySlider(
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "滤镜强度",
                color = Color.White,
                fontSize = 12.sp
            )
            Text(
                text = "${(intensity * 100).toInt()}%",
                color = LumaGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        androidx.compose.material3.Slider(
            value = intensity,
            onValueChange = onIntensityChange,
            valueRange = 0f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = LumaGold,
                activeTrackColor = LumaGold,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

/**
 * 拍摄信息叠加
 */
@Composable
fun CaptureInfoOverlay(
    remainingShots: Int?,
    storageRemaining: String?,
    batteryLevel: Int?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.4f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        remainingShots?.let {
            Text(
                text = "📷 $it",
                color = Color.White,
                fontSize = 11.sp
            )
        }
        
        storageRemaining?.let {
            Text(
                text = "💾 $it",
                color = Color.White,
                fontSize = 11.sp
            )
        }
        
        batteryLevel?.let {
            val batteryIcon = when {
                it > 80 -> "🔋"
                it > 50 -> "🔋"
                it > 20 -> "🪫"
                else -> "🪫"
            }
            Text(
                text = "$batteryIcon $it%",
                color = if (it < 20) Color.Red else Color.White,
                fontSize = 11.sp
            )
        }
    }
}
