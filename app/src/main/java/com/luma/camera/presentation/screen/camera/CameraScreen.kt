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
 * 鐩告満涓荤晫闈? * 
 * 瀹屾暣鐨勭浉鏈虹晫闈㈠疄鐜帮紝鍖呮嫭锛? * - 120fps 鍙栨櫙鍣? * - 鐒︽鍒囨崲
 * - 妯″紡鍒囨崲
 * - Pro 妯″紡鍙傛暟鎺у埗
 * - LUT 婊ら暅閫夋嫨
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
    
    // 瑙﹁鍙嶉
    val context = LocalContext.current
    
    // 鏉冮檺鐘舵€?
var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // 鏉冮檺璇锋眰鍚姩鍣?
val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }
    
    // LUT 鏂囦欢閫夋嫨鍣?
val lutFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importLut(it)
        }
    }
    
    // 棣栨鍚姩鏃惰姹傛潈闄?
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
    
    // 鐢熷懡鍛ㄦ湡澶勭悊 - 淇浠庣浉鍐岃繑鍥炲悗鐩告満鍗￠】
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 浠庡悗鍙版垨鍏朵粬 Activity 杩斿洖鏃舵仮澶嶇浉鏈?                    viewModel.resumeCamera()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // 杩涘叆鍚庡彴鎴栧垏鎹㈠埌鍏朵粬 Activity 鏃舵殏鍋滅浉鏈?                    viewModel.pauseCamera()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 濡傛灉娌℃湁鐩告満鏉冮檺锛屾樉绀烘潈闄愯姹傜晫闈?
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
    
    // 妫€鏌ユ槸鍚﹂渶瑕佷娇鐢?GL 娓叉煋锛堟湁閫変腑鐨?LUT 鎴栧惎鐢ㄥ嘲鍊煎鐒︼級
    // 濮嬬粓浣跨敤 GL 娓叉煋鍣ㄤ互閬垮厤杩愯鏃跺垏鎹㈠鑷寸殑 Surface 闂
    // LUT 鍜屽嘲鍊煎鐒︽晥鏋滅敱 GL 娓叉煋鍣ㄥ唴閮ㄦ帶鍒跺紑鍏?
val useGLRendering = true
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 鍙栨櫙鍣?- 濮嬬粓浣跨敤 GL 娓叉煋
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
            },            onTouchFocus = { x, y, viewWidth, viewHeight ->
                if (showProControls) {
                    showProControls = false
                } else if (showLutSelector) {
                    showLutSelector = false
                } else {
                    viewModel.onTouchFocus(x, y, viewWidth, viewHeight)
                }
            },
            onSurfaceDestroyed = {
                viewModel.onPreviewSurfaceDestroyed()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
        )
        
        // 宄板€煎鐒︾敱 GL 娓叉煋鍣ㄥ鐞嗭紝涓嶉渶瑕佸崟鐙殑鍙犲姞灞?        
        // 鐩存柟鍥?
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
        
        // 椤堕儴宸ュ叿鏍?
CameraTopBar(
            flashMode = cameraState.flashMode,
            onFlashModeChange = { viewModel.setFlashMode(it) },
            isLivePhotoEnabled = cameraState.isLivePhotoEnabled,
            onLivePhotoToggle = { viewModel.toggleLivePhoto() },
            onSettingsClick = onNavigateToSettings,
            aspectRatio = cameraState.aspectRatio,
            onAspectRatioChange = { viewModel.setAspectRatio(it) },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
        
        // 鏇濆厜淇℃伅鏄剧ず (Pro 妯″紡)
        if (isProMode) {
            ExposureInfoDisplay(
                iso = cameraState.manualParameters.iso,
                shutterSpeed = cameraState.manualParameters.shutterSpeed?.let { "1/${it}s" },
            modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            )
        }
        
        // 搴曢儴鎺у埗鍖?
Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // LUT 閫夋嫨鍣?
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
                        // 婊戝潡杩斿洖 0-1 鐨?Float锛岃浆鎹负 0-100 鐨?Int
                        viewModel.setLutIntensity((intensity * 100).toInt())
                    },
                    onImportLut = {
                        lutFilePicker.launch("*/*")
                    },
                    onManageLuts = {
                        showLutSelector = false
                        onNavigateToLutManager()
                    },
                    // 灏?0-100 鐨?Int 杞崲涓?0-1 鐨?Float
                    intensity = cameraState.lutIntensity / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Pro 妯″紡鎺у埗闈㈡澘
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
            
            // 鐒︽閫夋嫨鍣?
FocalLengthSelector(
                currentFocalLength = cameraState.currentFocalLength,
                onFocalLengthSelected = { focalLength ->
                    viewModel.switchFocalLength(focalLength)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 妯″紡閫夋嫨鍣?
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
                    // 濡傛灉宸茬粡鏄?Pro 妯″紡锛屽啀娆＄偣鍑诲垏鎹㈡帶鍒堕潰鏉挎樉绀?
if (mode == CameraMode.PRO && cameraState.currentMode == CameraMode.PRO) {
                        showProControls = !showProControls
                    } else {
                        viewModel.switchMode(mode)
                        showProControls = mode == CameraMode.PRO
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 搴曢儴鎿嶄綔鏍?
Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 鐩稿唽鍏ュ彛
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.3f))
                        .clickable {
                            // 鎵撳紑绯荤粺鐩稿唽
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
                        contentDescription = "鐩稿唽",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // 璋冭壊鐩樻寜閽?
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
                        contentDescription = null,
                        tint = if (!cameraState.colorPalette.isDefault()) Color(0xFFD4A574) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // 蹇棬鎸夐挳
                ShutterButton(
                    onClick = {
                        viewModel.capturePhoto()
                    },
                    isCapturing = cameraState.isCapturing
                )
                
                // LUT 婊ら暅鎸夐挳
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
                        contentDescription = "婊ら暅",
                        tint = if (cameraState.selectedLut != null) LumaGold else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
            }
        }
        
        // 璋冭壊鐩橀潰鏉?
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
 * 椤堕儴宸ュ叿鏍? */
@Composable
private fun CameraTopBar(
    flashMode: FlashMode,
    onFlashModeChange: (FlashMode) -> Unit,
    isLivePhotoEnabled: Boolean,
    onLivePhotoToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    aspectRatio: AspectRatio,
    onAspectRatioChange: (AspectRatio) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAspectRatioMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 闂厜鐏?
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
                    contentDescription = null,
                    tint = when (flashMode) {
                        FlashMode.OFF -> Color.White
                        else -> LumaGold
                    }
                )
            }
        }
        
        // 姣斾緥閫夋嫨

        Box {
            TextButton(onClick = { showAspectRatioMenu = true }) {
                Text(
                    text = when (aspectRatio) {
                        AspectRatio.RATIO_16_9 -> "16:9"
                        AspectRatio.RATIO_4_3 -> "4:3"
                        AspectRatio.RATIO_1_1 -> "1:1"
                        AspectRatio.RATIO_FULL -> "鍏ㄥ睆"
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            
            DropdownMenu(
                expanded = showAspectRatioMenu,
                onDismissRequest = { showAspectRatioMenu = false }
            ) {
                // 鍙樉绀?4 涓瘮渚嬮€夐」锛屼笉鍖呭惈鍒悕
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
                                    AspectRatio.RATIO_FULL -> "鍏ㄥ睆"
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
        
        // 璁剧疆
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "璁剧疆",
                tint = Color.White
            )
        }
    }
}

/**
 * LUT 閫夋嫨闈㈡澘
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
    // LUT 鏂囦欢鍚嶅埌涓枃鏄剧ず鍚嶇О鐨勬槧灏?
    val lutDisplayNames = mapOf(
        "eterna" to "Eterna",
        "eterna_bb" to "Eterna BB",
        "classic_chrome" to "Classic Chrome",
        "classic_neg" to "Classic Neg",
        "astia" to "Astia",
        "pro_neg_std" to "Pro Neg Std",
        "provia" to "Provia",
        "velvia" to "Velvia",
        "cold" to "Cool",
        "warm" to "Warm",
        "hasselblad_portrait" to "Hasselblad Portrait",
        "forest_green" to "Forest Green",
        "warm_skin" to "Warm Skin",
        "beach_portrait" to "Beach Portrait",
        "sunset" to "Sunset",
        "snow_portrait" to "Snow Portrait"
    )
    
    // 鑾峰彇 LUT 鐨勬樉绀哄悕绉?
fun getLutDisplayName(lut: LutFilter): String {
        // 浠?ID 鎴栨枃浠惰矾寰勪腑鎻愬彇鏂囦欢鍚?
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
        // 鏍囬
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LUT 婊ら暅 (${lutFilters.size})",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            
            Row {
                // 绠＄悊鎸夐挳
                IconButton(
                    onClick = onManageLuts,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "绠＄悊 LUT",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                if (currentLut != null) {
                    TextButton(onClick = { onLutSelected(null) }) {
                        Text(
                            text = "娓呴櫎",
                            color = LumaGold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // LUT 鍒楄〃 - 浠?LutManager 鑾峰彇鐪熷疄鍒楄〃
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 鏃犳护闀?
item {
                LutPreviewThumbnail(
                    lutName = "鍘熷浘",
                    previewBitmap = null,
                    isSelected = currentLut == null,
                    onClick = { onLutSelected(null) }
                )
            }
            
            // 鏄剧ず鎵€鏈?LUT 婊ら暅
            items(lutFilters) { lut ->
                LutPreviewThumbnail(
                    lutName = getLutDisplayName(lut),
                    previewBitmap = null,
                    isSelected = currentLut?.id == lut.id,
                    onClick = { onLutSelected(lut) }
                )
            }
            
            // 瀵煎叆/绠＄悊 LUT 鎸夐挳
            item {
                LutImportButton(
                    onClick = onImportLut
                )
            }
        }
        
        // 寮哄害婊戝潡
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
 * Pro 妯″紡鎺у埗闈㈡澘
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
        // ISO 鎺у埗
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
        
        // 蹇棬閫熷害
        ParameterRow(
            label = "蹇棬",
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
        
        // 鐧藉钩琛?
ParameterRow(
            label = "White balance",
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
        
        // 鎵嬪姩鐧藉钩琛¤壊娓╂粦鍧楋紙浠呭綋 MANUAL 妯″紡鏃舵樉绀猴級
        if (manualParameters.whiteBalanceMode == WhiteBalanceMode.MANUAL) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "鑹叉俯",
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
                    steps = 39, // 姣?00K涓€涓。
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
        
        // 鏇濆厜琛ュ伩
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
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
                text = "Camera permission required",
                color = LumaGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 鍙傛暟琛? */
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
        horizontalArrangement = Arrangement.SpaceEvenly,
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
 * 鏉冮檺璇锋眰鐣岄潰
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
                text = "Camera permission required",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Luma Camera 闇€瑕佽闂偍鐨勭浉鏈烘墠鑳芥媿鎽勭収鐗囧拰瑙嗛",
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
                    text = "鎺堜簣鏉冮檺",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * LUT 瀵煎叆鎸夐挳
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
                contentDescription = "瀵煎叆 LUT",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "瀵煎叆",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}







