package com.luma.camera.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.camera.domain.model.ColorPalette
import com.luma.camera.domain.model.ColorPreset
import kotlin.math.roundToInt

/**
 * 调色盘面板 - 参考华为 XMAGE 风格设计
 * 
 * 功能：
 * - 2D 调色盘：X轴色温(冷->暖)，Y轴饱和度(低->高)
 * - 右侧光影条：上下滑动调整光影
 * - 顶部数值显示
 * - 预设模板横滑列表
 */
@Composable
fun ColorPalettePanel(
    visible: Boolean,
    palette: ColorPalette,
    presets: List<ColorPreset>,
    selectedPresetId: String?,
    onPaletteChange: (ColorPalette) -> Unit,
    onPresetSelect: (ColorPreset) -> Unit,
    onSaveAsPreset: (String) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var showTempSlider by remember { mutableStateOf(false) }
    var showSatSlider by remember { mutableStateOf(false) }
    var showToneSlider by remember { mutableStateOf(false) }
    
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Surface(
            color = Color(0xE6303030),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 顶部标题和关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 数值显示区
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ValueLabel(
                            label = "色温",
                            value = "${palette.tempUI.roundToInt()}",
                            unit = "",
                            onClick = { showTempSlider = !showTempSlider }
                        )
                        ValueLabel(
                            label = "饱和度",
                            value = "${palette.satUI.roundToInt()}",
                            unit = "%",
                            onClick = { showSatSlider = !showSatSlider }
                        )
                        ValueLabel(
                            label = "曝光",
                            value = "${palette.expUI.roundToInt()}",
                            unit = "",
                            onClick = { showToneSlider = !showToneSlider }
                        )
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 重置按钮
                        IconButton(
                            onClick = onResetAll,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重置",
                                tint = Color.White
                            )
                        }
                        // 关闭按钮
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = Color.White
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 精细滑条区域（点击数值时显示）
                AnimatedVisibility(visible = showTempSlider) {
                    PrecisionSlider(
                        label = "色温",
                        value = palette.tempUI,
                        valueRange = ColorPalette.UI_MIN..ColorPalette.UI_MAX,
                        onValueChange = { onPaletteChange(palette.copy(tempUI = it)) },
                        valueFormatter = { "${it.roundToInt()}" }
                    )
                }
                AnimatedVisibility(visible = showSatSlider) {
                    PrecisionSlider(
                        label = "饱和度",
                        value = palette.satUI,
                        valueRange = ColorPalette.UI_MIN..ColorPalette.UI_MAX,
                        onValueChange = { onPaletteChange(palette.copy(satUI = it)) },
                        valueFormatter = { "${it.roundToInt()}" }
                    )
                }
                AnimatedVisibility(visible = showToneSlider) {
                    PrecisionSlider(
                        label = "曝光",
                        value = palette.expUI,
                        valueRange = ColorPalette.UI_MIN..ColorPalette.UI_MAX,
                        onValueChange = { onPaletteChange(palette.copy(expUI = it)) },
                        valueFormatter = { "${it.roundToInt()}" }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 主调色区域：2D调色盘 + 光影条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 2D 调色盘
                    ColorPad2D(
                        temperature = palette.temperatureNormalized,
                        saturation = palette.saturationNormalized,
                        onValueChange = { tempNorm, satNorm ->
                            // 将归一化值 (0~1) 转回 UI 值 (-100~+100)
                            val newTempUI = (tempNorm * 2f - 1f) * 100f
                            val newSatUI = (satNorm * 2f - 1f) * 100f
                            onPaletteChange(
                                palette.copy(
                                    tempUI = newTempUI,
                                    satUI = newSatUI
                                )
                            )
                        },
                        onDoubleClick = {
                            onPaletteChange(
                                palette.copy(
                                    tempUI = ColorPalette.DEFAULT.tempUI,
                                    satUI = ColorPalette.DEFAULT.satUI
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    
                    // 曝光条（原光影条）
                    ToneSlider(
                        value = palette.toneNormalized,
                        onValueChange = { expNorm ->
                            // 将归一化值 (0~1) 转回 UI 值 (-100~+100)
                            val newExpUI = (expNorm * 2f - 1f) * 100f
                            onPaletteChange(
                                palette.copy(expUI = newExpUI)
                            )
                        },
                        onDoubleClick = {
                            onPaletteChange(palette.copy(expUI = ColorPalette.DEFAULT.expUI))
                        },
                        modifier = Modifier
                            .width(40.dp)
                            .fillMaxHeight()
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 预设标签
                Text(
                    text = "胶片",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 预设模板横滑列表
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    presets.forEach { preset ->
                        PresetChip(
                            preset = preset,
                            isSelected = preset.id == selectedPresetId,
                            onClick = { onPresetSelect(preset) }
                        )
                    }
                    
                    // 添加预设按钮
                    AddPresetButton(
                        onClick = { showSaveDialog = true }
                    )
                }
            }
        }
    }
    
    // 保存预设对话框
    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                onSaveAsPreset(name)
                showSaveDialog = false
            }
        )
    }
}

/**
 * 数值标签组件
 */
@Composable
private fun ValueLabel(
    label: String,
    value: String,
    unit: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

/**
 * 精细调节滑条
 */
@Composable
private fun PrecisionSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueFormatter: (Float) -> String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.width(50.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFFD4A574),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Text(
            text = valueFormatter(value),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }
}

/**
 * 2D 调色盘
 * X轴：色温（冷->暖）
 * Y轴：饱和度（低->高，注意UI上是上低下高）
 */
@Composable
private fun ColorPad2D(
    temperature: Float, // 0~1, 0=冷, 1=暖
    saturation: Float,  // 0~1, 0=低, 1=高
    onValueChange: (Float, Float) -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF404040))
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleClick() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                    // Y轴反转：UI上方为低饱和度，下方为高饱和度
                    onValueChange(x, 1f - y)
                }
            }
    ) {
        // 渐变背景 - 模拟 XMAGE 风格
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 创建色温渐变（冷->暖）
            val horizontalGradient = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF8EC5E8), // 冷色
                    Color(0xFFE8D4C0), // 中性
                    Color(0xFFE8B88C)  // 暖色
                )
            )
            drawRect(brush = horizontalGradient)
            
            // 叠加饱和度渐变（上低下高）
            val verticalGradient = Brush.verticalGradient(
                colors = listOf(
                    Color.Gray.copy(alpha = 0.5f),
                    Color.Transparent
                )
            )
            drawRect(brush = verticalGradient)
            
            // 绘制网格点阵（类似 XMAGE）
            val gridSpacing = 20.dp.toPx()
            val dotRadius = 2.dp.toPx()
            val dotColor = Color.White.copy(alpha = 0.15f)
            
            var x = gridSpacing
            while (x < size.width) {
                var y = gridSpacing
                while (y < size.height) {
                    drawCircle(
                        color = dotColor,
                        radius = dotRadius,
                        center = Offset(x, y)
                    )
                    y += gridSpacing
                }
                x += gridSpacing
            }
        }
        
        // 控制点
        val controlPointX = temperature
        val controlPointY = (1f - saturation)
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width * controlPointX
            val centerY = size.height * controlPointY
            
            // 外圈
            drawCircle(
                color = Color.White,
                radius = 14.dp.toPx(),
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
            // 内圈
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(centerX, centerY)
            )
        }
    }
}

/**
 * 光影条（竖向滑块）
 */
@Composable
private fun ToneSlider(
    value: Float, // 0~1, 0=淡, 1=浓
    onValueChange: (Float) -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF404040))
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleClick() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                    // Y轴反转：上方为浓，下方为淡
                    onValueChange(1f - y)
                }
            }
    ) {
        // 渐变背景（上浓下淡）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF5A4A3A), // 浓
                    Color(0xFFC0B0A0)  // 淡
                )
            )
            drawRoundRect(
                brush = gradient,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
            )
        }
        
        // 控制点
        val controlY = (1f - value)
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height * controlY
            val handleWidth = size.width * 0.7f
            val handleHeight = 24.dp.toPx()
            
            // 绘制滑块手柄
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(centerX - handleWidth / 2, centerY - handleHeight / 2),
                size = androidx.compose.ui.geometry.Size(handleWidth, handleHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            )
        }
    }
}

/**
 * 预设芯片
 */
@Composable
private fun PresetChip(
    preset: ColorPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        Color(0xFFD4A574)
    } else {
        preset.previewColor
    }
    
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (preset.name == "Original") {
            // 原始预设显示一半一半的样式
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = true
                )
            }
        }
    }
}

/**
 * 添加预设按钮
 */
@Composable
private fun AddPresetButton(
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "添加预设",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 保存预设对话框
 */
@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var presetName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("保存预设", color = Color.White)
        },
        text = {
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                label = { Text("预设名称") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFD4A574),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    focusedLabelColor = Color(0xFFD4A574),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(presetName) },
                enabled = presetName.isNotBlank()
            ) {
                Text("保存", color = Color(0xFFD4A574))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.White.copy(alpha = 0.7f))
            }
        },
        containerColor = Color(0xFF404040)
    )
}
