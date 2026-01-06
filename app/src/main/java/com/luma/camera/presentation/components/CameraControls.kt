package com.luma.camera.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.camera.domain.model.FocalLength
import com.luma.camera.presentation.theme.LumaGold

/**
 * 焦段选择器
 * 
 * 支持 0.5x / 1x / 3x / 6x 切换
 * 点击切换，附带过渡动画
 */
@Composable
fun FocalLengthSelector(
    currentFocalLength: FocalLength,
    availableFocalLengths: List<FocalLength> = UNIQUE_FOCAL_LENGTHS,
    onFocalLengthSelected: (FocalLength) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        availableFocalLengths.forEach { focalLength ->
            FocalLengthChip(
                focalLength = focalLength,
                isSelected = focalLength == currentFocalLength || 
                    // 处理相同倍率的焦段匹配
                    (focalLength.displayName == currentFocalLength.displayName),
                onClick = { onFocalLengthSelected(focalLength) }
            )
        }
    }
}

/**
 * 唯一的焦段列表（不重复的 0.5x, 1x, 3x, 6x）
 */
private val UNIQUE_FOCAL_LENGTHS = listOf(
    FocalLength.ULTRA_WIDE,   // 0.5x
    FocalLength.WIDE,         // 1x
    FocalLength.TELEPHOTO_3X, // 3x
    FocalLength.TELEPHOTO_6X  // 6x
)

/**
 * 单个焦段芯片
 */
@Composable
private fun FocalLengthChip(
    focalLength: FocalLength,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) LumaGold else Color.Transparent,
        animationSpec = tween(200),
        label = "chipBackground"
    )
    
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.Black else Color.White,
        animationSpec = tween(200),
        label = "chipText"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "chipScale"
    )
    
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = focalLength.displayName,
            color = textColor,
            fontSize = if (isSelected) 14.sp else 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * FocalLength 扩展 - 显示名称
 */
val FocalLength.displayName: String
    get() = when (this) {
        FocalLength.ULTRA_WIDE -> "0.5x"
        FocalLength.WIDE, FocalLength.MAIN -> "1x"
        FocalLength.TELEPHOTO, FocalLength.TELEPHOTO_3X -> "3x"
        FocalLength.TELEPHOTO_6X, FocalLength.PERISCOPE -> "6x"
    }

/**
 * 快门按钮
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    isCapturing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ringScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "shutterScale"
    )
    
    Box(
        modifier = modifier
            .size(72.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // 外圈
        Box(
            modifier = Modifier
                .size(72.dp * ringScale)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f))
        )
        
        // 内圈
        Box(
            modifier = Modifier
                .size(60.dp * ringScale)
                .clip(CircleShape)
                .background(Color.White)
        )
        
        // 拍摄中显示
        if (isCapturing) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Red)
            )
        }
    }
}

/**
 * 模式切换器
 */
@Composable
fun ModeSelector(
    modes: List<String>,
    selectedIndex: Int,
    onModeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEachIndexed { index, mode ->
            val isSelected = index == selectedIndex
            
            val textColor by animateColorAsState(
                targetValue = if (isSelected) LumaGold else Color.White.copy(alpha = 0.6f),
                animationSpec = tween(200),
                label = "modeColor"
            )
            
            Text(
                text = mode,
                color = textColor,
                fontSize = if (isSelected) 16.sp else 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { onModeSelected(index) }
            )
        }
    }
}

/**
 * 顶部工具栏按钮
 */
@Composable
fun ToolbarIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isActive) LumaGold.copy(alpha = 0.2f) 
                else Color.Transparent
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

/**
 * 曝光指示器
 */
@Composable
fun ExposureIndicator(
    exposureValue: Float,
    modifier: Modifier = Modifier
) {
    val evText = when {
        exposureValue > 0 -> "+%.1f".format(exposureValue)
        exposureValue < 0 -> "%.1f".format(exposureValue)
        else -> "±0"
    }
    
    Box(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "EV $evText",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * ISO/快门速度显示
 */
@Composable
fun ExposureInfoDisplay(
    iso: Int?,
    shutterSpeed: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ISO
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ISO ",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
            Text(
                text = iso?.toString() ?: "AUTO",
                color = if (iso != null) LumaGold else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        // 快门速度
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "S ",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
            Text(
                text = shutterSpeed ?: "AUTO",
                color = if (shutterSpeed != null) LumaGold else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
