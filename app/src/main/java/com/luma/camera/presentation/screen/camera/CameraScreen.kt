package com.luma.camera.presentation.screen.camera

import android.Manifest
import android.content.Intent
import android.provider.MediaStore
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.pm.PackageManager
import com.luma.camera.domain.model.*
import com.luma.camera.presentation.components.*
import com.luma.camera.presentation.theme.LumaGold
import com.luma.camera.presentation.viewmodel.CameraViewModel

/**
 * 相机主界面
 * 
 * 完整的相机界面实现，包括：
 * - 120fps 取景器
 * - 焦段切换
 * - 模式切换
 * - Pro 模式参数控制
 * - LUT 滤镜选择
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToLutManager: () -> Unit = {},
    viewModel: CameraViewModel = hiltViewModel()
) {
    val cameraState by viewModel.cameraState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val lutFilters by viewModel.lutFilters.collectAsState()
    val levelAngle by viewModel.levelAngle.collectAsState()
    val colorPresets by viewModel.colorPresets.collectAsState()
    val isProMode = cameraState.currentMode == CameraMode.PRO
    var showLutSelector by remember { mutableStateOf(false) }
    var showProControls by remember { mutableStateOf(false) }
    
    // 触觉反馈
    val context = LocalContext.current
    
    // 权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // 权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }
    
    // LUT 文件选择器
    val lutFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importLut(it)
        }
    }
    
    // 首次启动时请求权限
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }
    
    // 生命周期处理 - 修复从相册返回后相机卡顿
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 从后台或其他 Activity 返回时恢复相机
                    viewModel.resumeCamera()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // 进入后台或切换到其他 Activity 时暂停相机
                    viewModel.pauseCamera()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 如果没有相机权限，显示权限请求界面
    if (!hasCameraPermission) {
        PermissionRequestScreen(
            onRequestPermission = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            }
        )
        return
    }
    
    // 检查是否需要使用 GL 渲染（有选中的 LUT 或启用峰值对焦）
    // 始终使用 GL 渲染器以避免运行时切换导致的 Surface 问题
    // LUT 和峰值对焦效果由 GL 渲染器内部控制开关
    val useGLRendering = true
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 取景器 - 始终使用 GL 渲染
        CameraViewfinder(
            aspectRatio = cameraState.aspectRatio,
            gridType = if (settings.showGrid) GridType.RULE_OF_THIRDS else GridType.NONE,
            showLevel = settings.showLevel,
            levelAngle = levelAngle,
            glRenderer = viewModel.glPreviewRenderer,
            onSurfaceReady = { surface ->
                viewModel.onPreviewSurfaceReady(surface)
            },
            onGLRendererSurfaceReady = { surface ->
                viewModel.onGLPreviewSurfaceReady(surface)
            },
            onTouchFocus = { x, y ->
                // 点击取景器时收缩 Pro 控制面板和 LUT 选择器
                if (showProControls) {
                    showProControls = false
                } else if (showLutSelector) {
                    showLutSelector = false
                } else {
                    // 正常的触摸对焦
                    viewModel.onTouchFocus(x, y)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
        )
        
        // 峰值对焦由 GL 渲染器处理，不需要单独的叠加层
        
        // 直方图
        if (settings.showHistogram) {
            HistogramView(
                redChannel = cameraState.histogramRed,
                greenChannel = cameraState.histogramGreen,
                blueChannel = cameraState.histogramBlue,
                luminance = cameraState.histogramLuminance,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(120.dp, 80.dp)
            )
        }
        
        // 顶部工具栏
        CameraTopBar(
            flashMode = cameraState.flashMode,
            onFlashModeChange = { viewModel.setFlashMode(it) },
            onSettingsClick = onNavigateToSettings,
            aspectRatio = cameraState.aspectRatio,
            onAspectRatioChange = { viewModel.setAspectRatio(it) },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )
        
        // 曝光信息显示 (Pro 模式)
        if (isProMode) {
            ExposureInfoDisplay(
                iso = cameraState.manualParameters.iso,
                shutterSpeed = cameraState.manualParameters.shutterSpeed?.let { "1/${it}s" },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            )
        }
        
        // 底部控制区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // LUT 选择器
            AnimatedVisibility(
                visible = showLutSelector,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                LutSelectorPanel(
                    lutFilters = lutFilters,
                    currentLut = cameraState.selectedLut,
                    onLutSelected = { lut ->
                        viewModel.selectLut(lut)
                    },
                    onIntensityChange = { intensity ->
                        // 滑块返回 0-1 的 Float，转换为 0-100 的 Int
                        viewModel.setLutIntensity((intensity * 100).toInt())
                    },
                    onImportLut = {
                        lutFilePicker.launch("*/*")
                    },
                    onManageLuts = {
                        showLutSelector = false
                        onNavigateToLutManager()
                    },
                    // 将 0-100 的 Int 转换为 0-1 的 Float
                    intensity = cameraState.lutIntensity / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Pro 模式控制面板
            AnimatedVisibility(
                visible = isProMode && showProControls,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                ProModeControlPanel(
                    manualParameters = cameraState.manualParameters,
                    onParametersChange = { params ->
                        viewModel.updateManualParameters { params }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 焦段选择器
            FocalLengthSelector(
                currentFocalLength = cameraState.currentFocalLength,
                onFocalLengthSelected = { focalLength ->
                    viewModel.switchFocalLength(focalLength)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 模式选择器
            ModeSelector(
                modes = listOf("Auto", "Pro"),
                selectedIndex = when (cameraState.currentMode) {
                    CameraMode.PHOTO -> 0
                    CameraMode.PRO -> 1
                    else -> 0
                },
                onModeSelected = { index ->
                    val mode = when (index) {
                        0 -> CameraMode.PHOTO
                        1 -> CameraMode.PRO
                        else -> CameraMode.PHOTO
                    }
                    // 如果已经是 Pro 模式，再次点击切换控制面板显示
                    if (mode == CameraMode.PRO && cameraState.currentMode == CameraMode.PRO) {
                        showProControls = !showProControls
                    } else {
                        viewModel.switchMode(mode)
                        showProControls = mode == CameraMode.PRO
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 底部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 相册入口
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.3f))
                        .clickable {
                            // 打开系统相册
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                type = "image/*"
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Photo,
                        contentDescription = "相册",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // 调色盘按钮
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (!cameraState.colorPalette.isDefault()) Color(0xFFD4A574).copy(alpha = 0.3f)
                            else Color.Gray.copy(alpha = 0.3f)
                        )
                        .clickable { viewModel.openColorPalettePanel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = "调色盘",
                        tint = if (!cameraState.colorPalette.isDefault()) Color(0xFFD4A574) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // 快门按钮
                ShutterButton(
                    onClick = {
                        viewModel.capturePhoto()
                    },
                    isCapturing = cameraState.isCapturing
                )
                
                // LUT 滤镜按钮
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (cameraState.selectedLut != null) LumaGold.copy(alpha = 0.3f)
                            else Color.Gray.copy(alpha = 0.3f)
                        )
                        .clickable { showLutSelector = !showLutSelector },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FilterVintage,
                        contentDescription = "滤镜",
                        tint = if (cameraState.selectedLut != null) LumaGold else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // Live Photo 按钮
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (cameraState.isLivePhotoEnabled) LumaGold.copy(alpha = 0.3f)
                            else Color.Gray.copy(alpha = 0.3f)
                        )
                        .clickable { viewModel.toggleLivePhoto() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (cameraState.isLivePhotoEnabled) Icons.Filled.MotionPhotosOn else Icons.Outlined.MotionPhotosOff,
                        contentDescription = "实况",
                        tint = if (cameraState.isLivePhotoEnabled) LumaGold else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        // 调色盘面板
        ColorPalettePanel(
            visible = cameraState.isColorPalettePanelOpen,
            palette = cameraState.colorPalette,
            presets = colorPresets,
            selectedPresetId = cameraState.selectedPresetId,
            onPaletteChange = { palette ->
                viewModel.updateColorPalette(palette)
            },
            onPresetSelect = { preset ->
                viewModel.selectColorPreset(preset)
            },
            onSaveAsPreset = { name ->
                viewModel.saveCurrentAsPreset(name)
            },
            onResetAll = {
                viewModel.resetColorPalette()
            },
            onDismiss = {
                viewModel.closeColorPalettePanel()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )
    }
}

/**
 * 顶部工具栏
 */
@Composable
private fun CameraTopBar(
    flashMode: FlashMode,
    onFlashModeChange: (FlashMode) -> Unit,
    onSettingsClick: () -> Unit,
    aspectRatio: AspectRatio,
    onAspectRatioChange: (AspectRatio) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAspectRatioMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 闪光灯
        Box {
            IconButton(onClick = {
                val nextMode = when (flashMode) {
                    FlashMode.OFF -> FlashMode.AUTO
                    FlashMode.AUTO -> FlashMode.ON
                    FlashMode.ON -> FlashMode.TORCH
                    FlashMode.TORCH -> FlashMode.OFF
                }
                onFlashModeChange(nextMode)
            }) {
                Icon(
                    imageVector = when (flashMode) {
                        FlashMode.OFF -> Icons.Outlined.FlashOff
                        FlashMode.AUTO -> Icons.Outlined.FlashAuto
                        FlashMode.ON -> Icons.Filled.FlashOn
                        FlashMode.TORCH -> Icons.Filled.Highlight
                    },
                    contentDescription = "闪光灯",
                    tint = when (flashMode) {
                        FlashMode.OFF -> Color.White
                        else -> LumaGold
                    }
                )
            }
        }
        
        // 比例选择
        Box {
            TextButton(onClick = { showAspectRatioMenu = true }) {
                Text(
                    text = when (aspectRatio) {
                        AspectRatio.RATIO_16_9 -> "16:9"
                        AspectRatio.RATIO_4_3 -> "4:3"
                        AspectRatio.RATIO_1_1 -> "1:1"
                        AspectRatio.RATIO_FULL -> "全屏"
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            
            DropdownMenu(
                expanded = showAspectRatioMenu,
                onDismissRequest = { showAspectRatioMenu = false }
            ) {
                // 只显示 4 个比例选项，不包含别名
                listOf(
                    AspectRatio.RATIO_4_3,
                    AspectRatio.RATIO_16_9,
                    AspectRatio.RATIO_1_1,
                    AspectRatio.RATIO_FULL
                ).forEach { ratio ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (ratio) {
                                    AspectRatio.RATIO_16_9 -> "16:9"
                                    AspectRatio.RATIO_4_3 -> "4:3"
                                    AspectRatio.RATIO_1_1 -> "1:1"
                                    AspectRatio.RATIO_FULL -> "全屏"
                                }
                            )
                        },
                        onClick = {
                            onAspectRatioChange(ratio)
                            showAspectRatioMenu = false
                        }
                    )
                }
            }
        }
        
        // 设置
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = Color.White
            )
        }
    }
}

/**
 * LUT 选择面板
 */
@Composable
private fun LutSelectorPanel(
    lutFilters: List<LutFilter>,
    currentLut: LutFilter?,
    onLutSelected: (LutFilter?) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onImportLut: () -> Unit,
    onManageLuts: () -> Unit,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    // LUT 文件名到中文显示名称的映射
    val lutDisplayNames = mapOf(
        "eterna" to "电影质感",
        "eterna_bb" to "电影漂白",
        "classic_chrome" to "经典铬色",
        "classic_neg" to "经典负片",
        "astia" to "柔和人像",
        "pro_neg_std" to "专业人像",
        "provia" to "标准鲜艳",
        "velvia" to "风光鲜艳",
        "cold" to "冷色调",
        "warm" to "暖色调",
        "hasselblad_portrait" to "哈苏人像",
        "forest_green" to "森系绿调",
        "warm_skin" to "暖调肤色",
        "beach_portrait" to "海边人像",
        "sunset" to "夕阳暖调",
        "snow_portrait" to "雪景清冷"
    )
    
    // 获取 LUT 的显示名称
    fun getLutDisplayName(lut: LutFilter): String {
        // 从 ID 或文件路径中提取文件名
        val fileName = lut.filePath
            .substringAfterLast("/")
            .substringBeforeLast(".")
            .lowercase()
        return lutDisplayNames[fileName] ?: lut.name
    }
    
    Column(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.8f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        // 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LUT 滤镜 (${lutFilters.size})",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            
            Row {
                // 管理按钮
                IconButton(
                    onClick = onManageLuts,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "管理 LUT",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                if (currentLut != null) {
                    TextButton(onClick = { onLutSelected(null) }) {
                        Text(
                            text = "清除",
                            color = LumaGold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // LUT 列表 - 从 LutManager 获取真实列表
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 无滤镜
            item {
                LutPreviewThumbnail(
                    lutName = "原图",
                    previewBitmap = null,
                    isSelected = currentLut == null,
                    onClick = { onLutSelected(null) }
                )
            }
            
            // 显示所有 LUT 滤镜
            items(lutFilters) { lut ->
                LutPreviewThumbnail(
                    lutName = getLutDisplayName(lut),
                    previewBitmap = null,
                    isSelected = currentLut?.id == lut.id,
                    onClick = { onLutSelected(lut) }
                )
            }
            
            // 导入/管理 LUT 按钮
            item {
                LutImportButton(
                    onClick = onImportLut
                )
            }
        }
        
        // 强度滑块
        if (currentLut != null) {
            Spacer(modifier = Modifier.height(16.dp))
            
            FilterIntensitySlider(
                intensity = intensity,
                onIntensityChange = onIntensityChange
            )
        }
    }
}

/**
 * Pro 模式控制面板
 */
@Composable
private fun ProModeControlPanel(
    manualParameters: ManualParameters,
    onParametersChange: (ManualParameters) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.8f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        // ISO 控制
        ParameterRow(
            label = "ISO",
            value = manualParameters.iso?.toString() ?: "AUTO",
            values = listOf("AUTO", "50", "100", "200", "400", "800", "1600", "3200"),
            onValueChange = { value ->
                val iso = if (value == "AUTO") null else value.toIntOrNull()
                onParametersChange(manualParameters.copy(iso = iso))
            }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 快门速度
        ParameterRow(
            label = "快门",
            value = manualParameters.shutterSpeed?.let { "1/${it.toInt()}s" } ?: "AUTO",
            values = listOf("AUTO", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30"),
            onValueChange = { value ->
                val speed: Double? = if (value == "AUTO") null else {
                    value.removePrefix("1/").removeSuffix("s").toDoubleOrNull()
                }
                onParametersChange(manualParameters.copy(shutterSpeed = speed))
            }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 白平衡
        ParameterRow(
            label = "白平衡",
            value = when(manualParameters.whiteBalanceMode) {
                WhiteBalanceMode.MANUAL -> "${manualParameters.whiteBalanceKelvin}K"
                else -> manualParameters.whiteBalanceMode.name
            },
            values = WhiteBalanceMode.entries.map { it.name },
            onValueChange = { value ->
                val mode = WhiteBalanceMode.entries.find { it.name == value } ?: WhiteBalanceMode.AUTO
                onParametersChange(manualParameters.copy(whiteBalanceMode = mode))
            }
        )
        
        // 手动白平衡色温滑块（仅当 MANUAL 模式时显示）
        if (manualParameters.whiteBalanceMode == WhiteBalanceMode.MANUAL) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "色温",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                
                Slider(
                    value = manualParameters.whiteBalanceKelvin.toFloat(),
                    onValueChange = { kelvin ->
                        onParametersChange(manualParameters.copy(
                            whiteBalanceKelvin = kelvin.toInt()
                        ))
                    },
                    valueRange = ManualParameters.WB_KELVIN_MIN.toFloat()..ManualParameters.WB_KELVIN_MAX.toFloat(),
                    steps = 39, // 每200K一个档
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = LumaGold,
                        activeTrackColor = LumaGold
                    )
                )
                
                Text(
                    text = "${manualParameters.whiteBalanceKelvin}K",
                    color = LumaGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 曝光补偿
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EV",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            
            Slider(
                value = manualParameters.exposureCompensation ?: 0f,
                onValueChange = { ev ->
                    onParametersChange(manualParameters.copy(exposureCompensation = ev))
                },
                valueRange = -3f..3f,
                steps = 12,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                colors = SliderDefaults.colors(
                    thumbColor = LumaGold,
                    activeTrackColor = LumaGold
                )
            )
            
            Text(
                text = "%.1f".format(manualParameters.exposureCompensation ?: 0f),
                color = LumaGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 参数行
 */
@Composable
private fun ParameterRow(
    label: String,
    value: String,
    values: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
        
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = value,
                    color = if (value == "AUTO") Color.White else LumaGold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                values.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v) },
                        onClick = {
                            onValueChange(v)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 权限请求界面
 */
@Composable
private fun PermissionRequestScreen(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = LumaGold,
                modifier = Modifier.size(80.dp)
            )
            
            Text(
                text = "需要相机权限",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Luma Camera 需要访问您的相机才能拍摄照片和视频",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LumaGold,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "授予权限",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * LUT 导入按钮
 */
@Composable
private fun LutImportButton(
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
                imageVector = Icons.Outlined.Add,
                contentDescription = "导入 LUT",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "导入",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
