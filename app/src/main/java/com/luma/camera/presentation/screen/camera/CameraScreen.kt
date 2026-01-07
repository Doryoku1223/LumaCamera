package com.luma.camera.presentation.screen.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.luma.camera.domain.model.AspectRatio
import com.luma.camera.domain.model.CameraMode
import com.luma.camera.domain.model.FlashMode
import com.luma.camera.domain.model.GridType
import com.luma.camera.domain.model.ManualParameters
import com.luma.camera.domain.model.WhiteBalanceMode
import com.luma.camera.presentation.components.CameraViewfinder
import com.luma.camera.presentation.components.ColorPalettePanel
import com.luma.camera.presentation.components.ExposureInfoDisplay
import com.luma.camera.presentation.components.FocalLengthSelector
import com.luma.camera.presentation.components.HistogramView
import com.luma.camera.presentation.components.ModeSelector
import com.luma.camera.presentation.components.ShutterButton
import com.luma.camera.presentation.theme.LumaGold
import com.luma.camera.presentation.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
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
    val isProMode = cameraState.currentMode == CameraMode.PRO
    var showProControls by remember { mutableStateOf(false) }

    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }

    val lutFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importLut(it) }
    }

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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.resumeCamera()
                Lifecycle.Event.ON_PAUSE -> viewModel.pauseCamera()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraViewfinder(
            aspectRatio = cameraState.aspectRatio,
            gridType = if (settings.showGrid) {
                settings.gridType.takeIf { it != GridType.NONE } ?: GridType.RULE_OF_THIRDS
            } else {
                GridType.NONE
            },
            showLevel = settings.showLevel,
            levelAngle = levelAngle,
            glRenderer = viewModel.glPreviewRenderer,
            onSurfaceReady = { surface ->
                viewModel.onPreviewSurfaceReady(surface)
            },
            onGLRendererSurfaceReady = { surface ->
                viewModel.onGLPreviewSurfaceReady(surface)
            },
            onTouchFocus = { x, y, viewWidth, viewHeight ->
                when {
                    cameraState.isColorPalettePanelOpen -> viewModel.closeColorPalettePanel()
                    showProControls -> showProControls = false
                    else -> viewModel.onTouchFocus(x, y, viewWidth, viewHeight)
                }
            },
            onSurfaceDestroyed = {
                viewModel.onPreviewSurfaceDestroyed()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
        )

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

        if (isProMode) {
            ExposureInfoDisplay(
                iso = cameraState.manualParameters.iso,
                shutterSpeed = cameraState.manualParameters.shutterSpeed?.let { "1/${it}s" },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            FocalLengthSelector(
                currentFocalLength = cameraState.currentFocalLength,
                onFocalLengthSelected = { focalLength ->
                    viewModel.switchFocalLength(focalLength)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                    if (mode == CameraMode.PRO && cameraState.currentMode == CameraMode.PRO) {
                        showProControls = !showProControls
                    } else {
                        viewModel.switchMode(mode)
                        showProControls = mode == CameraMode.PRO
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.3f))
                        .clickable {
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
                        contentDescription = "图库",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                ShutterButton(
                    onClick = { viewModel.capturePhoto() },
                    isCapturing = cameraState.isCapturing
                )

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (!cameraState.colorPalette.isDefault()) Color(0xFFD4A574).copy(alpha = 0.3f)
                            else Color.Gray.copy(alpha = 0.3f)
                        )
                        .clickable {
                            if (cameraState.isColorPalettePanelOpen) {
                                viewModel.closeColorPalettePanel()
                            } else {
                                viewModel.openColorPalettePanel()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = if (!cameraState.colorPalette.isDefault()) Color(0xFFD4A574) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        ColorPalettePanel(
            visible = cameraState.isColorPalettePanelOpen,
            palette = cameraState.colorPalette,
            lutFilters = lutFilters,
            currentLut = cameraState.selectedLut,
            lutIntensity = cameraState.lutIntensity / 100f,
            onPaletteChange = { palette ->
                viewModel.updateColorPalette(palette)
            },
            onResetAll = {
                viewModel.resetColorPalette()
            },
            onLutSelected = { lut ->
                viewModel.selectLut(lut)
            },
            onLutIntensityChange = { intensity ->
                viewModel.setLutIntensity((intensity * 100).toInt())
            },
            onImportLut = {
                lutFilePicker.launch("*/*")
            },
            onManageLuts = {
                viewModel.closeColorPalettePanel()
                onNavigateToLutManager()
            },
            onDismiss = {
                viewModel.closeColorPalettePanel()
            },
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.BottomCenter)
        )
    }
}

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
            .statusBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        IconButton(onClick = onLivePhotoToggle) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = "实况",
                tint = if (isLivePhotoEnabled) LumaGold else Color.White
            )
        }

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

        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                tint = Color.White
            )
        }
    }
}

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

        ParameterRow(
            label = "对焦",
            value = if (manualParameters.focusDistance == null) "AUTO" else "MANUAL",
            values = listOf("AUTO", "MANUAL"),
            onValueChange = { value ->
                val distance = if (value == "AUTO") null else manualParameters.focusDistance ?: 0.5f
                onParametersChange(manualParameters.copy(focusDistance = distance))
            }
        )

        if (manualParameters.focusDistance != null) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "对焦",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )

                Slider(
                    value = manualParameters.focusDistance,
                    onValueChange = { distance ->
                        onParametersChange(
                            manualParameters.copy(
                                focusDistance = distance
                            )
                        )
                    },
                    valueRange = 0f..1f,
                    steps = 99,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = LumaGold,
                        activeTrackColor = LumaGold
                    )
                )

                Text(
                    text = String.format("%.2f", manualParameters.focusDistance),
                    color = LumaGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ParameterRow(
            label = "白平衡",
            value = when (manualParameters.whiteBalanceMode) {
                WhiteBalanceMode.MANUAL -> "${manualParameters.whiteBalanceKelvin}K"
                else -> manualParameters.whiteBalanceMode.name
            },
            values = WhiteBalanceMode.entries.map { it.name },
            onValueChange = { value ->
                val mode = WhiteBalanceMode.entries.find { it.name == value } ?: WhiteBalanceMode.AUTO
                onParametersChange(manualParameters.copy(whiteBalanceMode = mode))
            }
        )

        if (manualParameters.whiteBalanceMode == WhiteBalanceMode.MANUAL) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
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
                        onParametersChange(
                            manualParameters.copy(
                                whiteBalanceKelvin = kelvin.toInt()
                            )
                        )
                    },
                    valueRange = ManualParameters.WB_KELVIN_MIN.toFloat()..ManualParameters.WB_KELVIN_MAX.toFloat(),
                    steps = 39,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
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
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                colors = SliderDefaults.colors(
                    thumbColor = LumaGold,
                    activeTrackColor = LumaGold
                )
            )

            Text(
                text = String.format("%+.1f", manualParameters.exposureCompensation),
                color = LumaGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { onParametersChange(manualParameters.copy(isAeLocked = !manualParameters.isAeLocked)) },
                modifier = Modifier
                    .background(
                        if (manualParameters.isAeLocked) LumaGold.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Text(
                    text = if (manualParameters.isAeLocked) "AE锁定" else "AE解除",
                    color = if (manualParameters.isAeLocked) LumaGold else Color.White
                )
            }

            TextButton(
                onClick = { onParametersChange(manualParameters.copy(isAfLocked = !manualParameters.isAfLocked)) },
                modifier = Modifier
                    .background(
                        if (manualParameters.isAfLocked) LumaGold.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Text(
                    text = if (manualParameters.isAfLocked) "AF锁定" else "AF解除",
                    color = if (manualParameters.isAfLocked) LumaGold else Color.White
                )
            }
        }
    }
}

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
                text = "LumaCamera 需要访问相机和麦克风才能拍摄照片和实况视频。",
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
                    text = "授权权限",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
