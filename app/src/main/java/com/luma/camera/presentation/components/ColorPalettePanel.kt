package com.luma.camera.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.camera.domain.model.ColorPalette
import com.luma.camera.domain.model.LutFilter
import kotlin.math.roundToInt
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 调色盘面板- 参考华为 XMAGE 风格设计
 *
 * 功能：
 * - 2D 调色盘：X轴色温(冷->暖)，Y轴饱和度(低->高)
 * - 右侧光影条：上下滑动调整光影
 * - 顶部数值显示
 * - LUT 滤镜列表
 */
@Composable
fun ColorPalettePanel(
    visible: Boolean,
    palette: ColorPalette,
    lutFilters: List<LutFilter>,
    currentLut: LutFilter?,
    lutIntensity: Float,
    onPaletteChange: (ColorPalette) -> Unit,
    onResetAll: () -> Unit,
    onLutSelected: (LutFilter?) -> Unit,
    onLutIntensityChange: (Float) -> Unit,
    onImportLut: () -> Unit,
    onManageLuts: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showTempSlider by remember { mutableStateOf(false) }
    var showSatSlider by remember { mutableStateOf(false) }
    var showToneSlider by remember { mutableStateOf(false) }
    val dismissInteraction = remember { MutableInteractionSource() }
    val surfaceInteraction = remember { MutableInteractionSource() }
    var originalThumb by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        originalThumb = withContext(Dispatchers.IO) {
            try {
                context.assets.open("lut_thumbs/original.png").use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            Surface(
                color = Color(0xE6303030),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clickable(
                        interactionSource = surfaceInteraction,
                        indication = null,
                        onClick = {}
                    )
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

                        // 光影条（原光影条）
                        ToneSlider(
                            value = palette.toneNormalized,
                            onValueChange = { expNorm ->
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

                    // LUT 区域
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LUT 滤镜",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        IconButton(
                            onClick = onManageLuts,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "管理 LUT",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            LutPreviewThumbnail(
                                lutName = "原图",
                                previewBitmap = originalThumb,
                                isSelected = currentLut == null,
                                onClick = { onLutSelected(null) }
                            )
                        }
                        items(lutFilters) { lut ->
                            var thumbnailBitmap by remember(lut.thumbnailPath) { mutableStateOf<ImageBitmap?>(null) }
                            LaunchedEffect(lut.thumbnailPath) {
                                val path = lut.thumbnailPath?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                thumbnailBitmap = withContext(Dispatchers.IO) {
                                    if (path == null) {
                                        null
                                    } else {
                                        val file = java.io.File(path)
                                        if (file.exists()) {
                                            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }
                            LutPreviewThumbnail(
                                lutName = lut.name,
                                previewBitmap = thumbnailBitmap,
                                isSelected = currentLut?.id == lut.id,
                                onClick = { onLutSelected(lut) }
                            )
                        }
                        item {
                            LutActionButton(
                                label = "导入",
                                onClick = onImportLut
                            )
                        }
                    }

                    if (currentLut != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        FilterIntensitySlider(
                            intensity = lutIntensity,
                            onIntensityChange = onLutIntensityChange,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
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
    temperature: Float,
    saturation: Float,
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
                    onValueChange(x, 1f - y)
                }
            }
    ) {
        // 渐变背景 - 模拟 XMAGE 风格
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizontalGradient = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF8EC5E8),
                    Color(0xFFE8D4C0),
                    Color(0xFFE8B88C)
                )
            )
            drawRect(brush = horizontalGradient)

            val verticalGradient = Brush.verticalGradient(
                colors = listOf(
                    Color.Gray.copy(alpha = 0.5f),
                    Color.Transparent
                )
            )
            drawRect(brush = verticalGradient)

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

        val controlPointX = temperature
        val controlPointY = (1f - saturation)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width * controlPointX
            val centerY = size.height * controlPointY

            drawCircle(
                color = Color.White,
                radius = 14.dp.toPx(),
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
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
    value: Float,
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
                    onValueChange(1f - y)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF5A4A3A),
                    Color(0xFFC0B0A0)
                )
            )
            drawRoundRect(
                brush = gradient,
                cornerRadius = CornerRadius(12.dp.toPx())
            )
        }

        val controlY = (1f - value)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height * controlY
            val handleWidth = size.width * 0.7f
            val handleHeight = 24.dp.toPx()

            drawRoundRect(
                color = Color.White,
                topLeft = Offset(centerX - handleWidth / 2, centerY - handleHeight / 2),
                size = Size(handleWidth, handleHeight),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
        }
    }
}

/**
 * LUT 操作按钮
 */
@Composable
private fun LutActionButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp, 96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
