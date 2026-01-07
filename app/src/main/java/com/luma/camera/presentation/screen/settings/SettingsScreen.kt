package com.luma.camera.presentation.screen.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.luma.camera.domain.model.*
import com.luma.camera.presentation.theme.LumaGold
import com.luma.camera.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToVersion: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val gridTypeOptions = remember { GridType.entries.filter { it != GridType.NONE } }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            viewModel.updateSettings(settings.copy(geotagEnabled = false))
        }
    }

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
            item { SettingsSection(title = "图片质量") }

            item {
                SettingsItemDropdown(
                    icon = Icons.Outlined.HighQuality,
                    title = "输出格式",
                    value = settings.outputFormat.toLabel(),
                    options = OutputFormat.entries.map { it.toLabel() },
                    onOptionSelected = { index ->
                        viewModel.updateSettings(
                            settings.copy(outputFormat = OutputFormat.entries[index])
                        )
                    }
                )
            }

            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.HdrOn,
                    title = "LumaRaw 输出",
                    subtitle = "输出更多细节，便于后期调色",
                    checked = settings.lumaLogEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(lumaLogEnabled = it))
                    }
                )
            }

            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.Badge,
                    title = "添加水印",
                    subtitle = "在照片底部显示 Luma 水印",
                    checked = settings.watermarkEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(watermarkEnabled = it))
                    }
                )
            }

            item {
                AnimatedVisibility(
                    visible = settings.watermarkEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SettingsItemDropdown(
                        icon = Icons.Outlined.Place,
                        title = "水印位置",
                        value = settings.watermarkPosition.toLabel(),
                        options = WatermarkPosition.entries.map { it.toLabel() },
                        onOptionSelected = { index ->
                            viewModel.updateSettings(
                                settings.copy(watermarkPosition = WatermarkPosition.entries[index])
                            )
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "拍摄辅助")
            }

            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.GridOn,
                    title = "网格线",
                    checked = settings.showGrid,
                    onCheckedChange = {
                        val nextGridType = if (it && settings.gridType == GridType.NONE) {
                            GridType.RULE_OF_THIRDS
                        } else {
                            settings.gridType
                        }
                        viewModel.updateSettings(settings.copy(showGrid = it, gridType = nextGridType))
                    }
                )
            }

            item {
                AnimatedVisibility(
                    visible = settings.showGrid,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val displayGridType = if (settings.gridType == GridType.NONE) {
                        GridType.RULE_OF_THIRDS
                    } else {
                        settings.gridType
                    }
                    SettingsItemDropdown(
                        icon = Icons.Outlined.Grid3x3,
                        title = "网格类型",
                        value = displayGridType.toLabel(),
                        options = gridTypeOptions.map { it.toLabel() },
                        onOptionSelected = { index ->
                            viewModel.updateSettings(
                                settings.copy(gridType = gridTypeOptions[index])
                            )
                        }
                    )
                }
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

            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.CenterFocusWeak,
                    title = "峰值对焦",
                    subtitle = "高亮边缘以辅助对焦",
                    checked = settings.focusPeakingEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(focusPeakingEnabled = it))
                    }
                )
            }

            item {
                AnimatedVisibility(
                    visible = settings.focusPeakingEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SettingsItemDropdown(
                        icon = Icons.Outlined.Palette,
                        title = "峰值对焦颜色",
                        value = settings.focusPeakingColor.replaceFirstChar { it.uppercase() },
                        options = listOf("Gold", "Red", "Green", "Blue", "White"),
                        onOptionSelected = { index ->
                            val color = listOf("gold", "red", "green", "blue", "white")[index]
                            viewModel.updateSettings(settings.copy(focusPeakingColor = color))
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "隐私设置")
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

            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.LocationOn,
                    title = "地理位置标签",
                    subtitle = "记录拍摄地点信息",
                    checked = settings.geotagEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                                viewModel.updateSettings(settings.copy(geotagEnabled = false))
                            } else {
                                viewModel.updateSettings(settings.copy(geotagEnabled = true))
                            }
                        } else {
                            viewModel.updateSettings(settings.copy(geotagEnabled = false))
                        }
                    }
                )
            }

            item {
                SettingsItemSwitch(
                    icon = Icons.Outlined.Info,
                    title = "详细信息",
                    subtitle = "记录拍摄参数与 LUT 信息",
                    checked = settings.saveExifParams,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings(
                            settings.copy(
                                saveExifParams = enabled,
                                saveExifFilter = enabled
                            )
                        )
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "关于")
            }

            item {
                SettingsItemNavigate(
                    icon = Icons.Outlined.Info,
                    title = "关于 LumaCamera",
                    onClick = onNavigateToVersion
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

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

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp
            )
            Text(
                text = value,
                color = LumaGold,
                fontSize = 13.sp
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onOptionSelected(index)
                    }
                )
            }
        }
    }
}

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
            .clickable { onClick() }
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
            fontSize = 16.sp
        )
    }
}

private fun OutputFormat.toLabel(): String = when (this) {
    OutputFormat.JPEG -> "JPEG"
    OutputFormat.HEIF -> "HEIF"
    OutputFormat.RAW_DNG -> "RAW DNG"
    OutputFormat.RAW_JPEG -> "RAW+JPEG"
}

private fun GridType.toLabel(): String = when (this) {
    GridType.NONE -> "\u65e0"
    GridType.RULE_OF_THIRDS -> "\u4e09\u5206\u7ebf"
    GridType.GRID_4X4 -> "4x4 \u7f51\u683c"
    GridType.GOLDEN_RATIO -> "\u9ec4\u91d1\u5206\u5272"
    GridType.DIAGONAL -> "\u5bf9\u89d2\u7ebf"
    GridType.CENTER_CROSS -> "\u4e2d\u5fc3\u5341\u5b57"
}

private fun WatermarkPosition.toLabel(): String = when (this) {
    WatermarkPosition.BOTTOM_LEFT -> "\u5de6\u4e0b"
    WatermarkPosition.BOTTOM_CENTER -> "\u5e95\u90e8\u5c45\u4e2d"
    WatermarkPosition.BOTTOM_RIGHT -> "\u53f3\u4e0b"
}
