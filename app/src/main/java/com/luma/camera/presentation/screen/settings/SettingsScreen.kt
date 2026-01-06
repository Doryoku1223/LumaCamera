package com.luma.camera.presentation.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.luma.camera.domain.model.*
import com.luma.camera.domain.model.WatermarkPosition
import com.luma.camera.presentation.theme.LumaGold
import com.luma.camera.presentation.viewmodel.SettingsViewModel

/**
 * 设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 图像质量
            item {
                SettingsSection(title = "图像质量")
            }
            
            item {
                SettingsItemDropdown(
                    icon = Icons.Outlined.HighQuality,
                    title = "输出格式",
                    value = settings.outputFormat.displayName,
                    options = OutputFormat.entries.map { it.displayName },
                    onOptionSelected = { index ->
                        viewModel.updateSettings(
                            settings.copy(outputFormat = OutputFormat.entries[index])
                        )
                    }
                )
            }
            
            item {
                SettingsItemDropdown(
                    icon = Icons.Outlined.AspectRatio,
                    title = "默认比例",
                    value = settings.aspectRatio.displayName,
                    options = AspectRatio.entries.map { it.displayName },
                    onOptionSelected = { index ->
                        viewModel.updateSettings(
                            settings.copy(aspectRatio = AspectRatio.entries[index])
                        )
                    }
                )
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.HdrOn,
                    title = "Luma Log 输出",
                    subtitle = "生成低对比度灰片，适合后期调色",
                    checked = settings.lumaLogEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(lumaLogEnabled = it))
                    }
                )
            }
            
            // 水印
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "水印")
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.Badge,
                    title = "添加水印",
                    subtitle = "在照片上添加 Luma 水印",
                    checked = settings.watermarkEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(watermarkEnabled = it))
                    }
                )
            }
            
            item {
                SettingsItemDropdown(
                    icon = Icons.Outlined.Place,
                    title = "水印位置",
                    value = settings.watermarkPosition.displayName,
                    options = WatermarkPosition.entries.map { it.displayName },
                    onOptionSelected = { index ->
                        viewModel.updateSettings(
                            settings.copy(watermarkPosition = WatermarkPosition.entries[index])
                        )
                    }
                )
            }
            
            // 取景器
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "取景器")
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.GridOn,
                    title = "网格线",
                    checked = settings.showGrid,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(showGrid = it))
                    }
                )
            }
            
            item {
                SettingsItemDropdown(
                    icon = Icons.Outlined.Grid3x3,
                    title = "网格类型",
                    value = settings.gridType.displayName,
                    options = GridType.entries.map { it.displayName },
                    onOptionSelected = { index ->
                        viewModel.updateSettings(
                            settings.copy(gridType = GridType.entries[index])
                        )
                    }
                )
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.Straighten,
                    title = "水平仪",
                    checked = settings.showLevel,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(showLevel = it))
                    }
                )
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.BarChart,
                    title = "直方图",
                    checked = settings.showHistogram,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(showHistogram = it))
                    }
                )
            }
            
            // 对焦辅助
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "对焦辅助")
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.CenterFocusWeak,
                    title = "峰值对焦",
                    subtitle = "高亮显示对焦清晰区域",
                    checked = settings.focusPeakingEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(focusPeakingEnabled = it))
                    }
                )
            }
            
            item {
                SettingsItemDropdown(
                    icon = Icons.Outlined.Palette,
                    title = "峰值对焦颜色",
                    value = settings.focusPeakingColor.capitalize(),
                    options = listOf("Gold", "Red", "Green", "Blue", "White"),
                    onOptionSelected = { index ->
                        val color = listOf("gold", "red", "green", "blue", "white")[index]
                        viewModel.updateSettings(settings.copy(focusPeakingColor = color))
                    }
                )
            }
            
            // 实况照片
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "实况照片")
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.PhotoCamera,
                    title = "实况照片",
                    subtitle = "拍照时同时录制 3 秒视频",
                    checked = settings.livePhotoEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(livePhotoEnabled = it))
                    }
                )
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.Mic,
                    title = "录制声音",
                    checked = settings.livePhotoAudioEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(livePhotoAudioEnabled = it))
                    }
                )
            }
            
            // 反馈
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "反馈")
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.Vibration,
                    title = "触觉反馈",
                    checked = settings.hapticEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(hapticEnabled = it))
                    }
                )
            }
            
            item {
                @Suppress("DEPRECATION")
                SettingsItemSwitch(
                    icon = Icons.Outlined.VolumeUp,
                    title = "快门声音",
                    checked = settings.shutterSoundEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(shutterSoundEnabled = it))
                    }
                )
            }
            
            // 存储
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "存储")
            }
            
            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.LocationOn,
                    title = "地理位置标签",
                    subtitle = "在照片中嵌入位置信息",
                    checked = settings.geotagEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(geotagEnabled = it))
                    }
                )
            }
            
            // 关于
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "关于")
            }
            
            item {
                SettingsItemInfo(
                    icon = Icons.Outlined.Info,
                    title = "版本",
                    value = "1.0.0"
                )
            }
            
            item {
                SettingsItemNavigate(
                    icon = Icons.Outlined.Description,
                    title = "开源许可",
                    onClick = { /* TODO */ }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 设置分组标题
 */
@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        color = LumaGold,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * 开关设置项
 */
@Composable
private fun SettingsItemSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LumaGold,
                checkedTrackColor = LumaGold.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
            )
        )
    }
}

/**
 * 下拉选择设置项
 */
@Composable
private fun SettingsItemDropdown(
    icon: ImageVector,
    title: String,
    value: String,
    options: List<String>,
    onOptionSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { expanded = true }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        
        Box {
            Text(
                text = value,
                color = LumaGold,
                fontSize = 14.sp
            )
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 信息展示设置项
 */
@Composable
private fun SettingsItemInfo(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp
        )
    }
}

/**
 * 导航设置项
 */
@Composable
private fun SettingsItemNavigate(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f)
        )
    }
}

/**
 * 扩展属性 - 显示名称
 */
private val OutputFormat.displayName: String
    get() = when (this) {
        OutputFormat.JPEG -> "JPEG"
        OutputFormat.HEIF -> "HEIF"
        OutputFormat.RAW_DNG -> "RAW (DNG)"
        OutputFormat.RAW_JPEG -> "RAW + JPEG"
    }

private val AspectRatio.displayName: String
    get() = when (this) {
        AspectRatio.RATIO_16_9 -> "16:9"
        AspectRatio.RATIO_4_3 -> "4:3"
        AspectRatio.RATIO_1_1 -> "1:1"
        AspectRatio.RATIO_FULL, AspectRatio.FULL -> "全屏"
    }

private val GridType.displayName: String
    get() = when (this) {
        GridType.NONE -> "无"
        GridType.RULE_OF_THIRDS -> "三分法"
        GridType.GRID_4X4 -> "4×4 网格"
        GridType.GOLDEN_RATIO -> "黄金分割"
        GridType.DIAGONAL -> "对角线"
        GridType.CENTER_CROSS -> "中心十字"
    }

private val WatermarkPosition.displayName: String
    get() = when (this) {
        WatermarkPosition.BOTTOM_LEFT -> "左下角"
        WatermarkPosition.BOTTOM_CENTER -> "底部居中"
        WatermarkPosition.BOTTOM_RIGHT -> "右下角"
    }

private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
