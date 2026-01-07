
package com.luma.camera.presentation.screen.lutmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.presentation.theme.LumaGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LUT 管理页面
 *
 * 功能：
 * - 显示 LUT 列表（带预览图）
 * - 导入/导出 LUT
 * - 重命名 LUT
 * - 设置缩略图
 * - 多选批量删除
 * - 长按拖拽排序
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LutManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: LutManagerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val lutFilters by viewModel.lutFilters.collectAsState()
    val lutPreviews by viewModel.lutPreviews.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<LutFilter?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<LutFilter?>(null) }
    var renameText by remember { mutableStateOf("") }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selectedIds.isNotEmpty()
    val lutById = remember(lutFilters) { lutFilters.associateBy { it.id } }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var lastHapticIndex by remember { mutableStateOf<Int?>(null) }
    var itemHeightPx by remember { mutableStateOf(0f) }
    var dragStartItemOffsetPx by remember { mutableStateOf(0f) }
    var dragItemHeightPx by remember { mutableStateOf(0f) }
    val fallbackItemHeightPx = with(LocalDensity.current) { 92.dp.toPx() }
    val listState = rememberLazyListState()
    val spacingPx = with(LocalDensity.current) { 12.dp.toPx() }

    fun toggleSelection(id: String) {
        selectedIds = if (selectedIds.contains(id)) {
            selectedIds - id
        } else {
            selectedIds + id
        }
    }

    val deletableSelection = selectedIds.mapNotNull { lutById[it] }.filter { !it.isBuiltIn }

    var pendingThumbnailLut by remember { mutableStateOf<LutFilter?>(null) }
    var editorLut by remember { mutableStateOf<LutFilter?>(null) }
    var editorBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val lutFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                viewModel.importLut(it)
            }
        }
    }

    val exportFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { destUri ->
            viewModel.pendingExportLut?.let { lut ->
                scope.launch {
                    viewModel.exportLut(lut.id, destUri)
                }
            }
        }
    }

    val thumbnailPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingThumbnailLut
        if (uri != null && target != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                if (bitmap != null) {
                    editorLut = target
                    editorBitmap = bitmap
                }
            }
        }
        pendingThumbnailLut = null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectionMode) "已选 ${selectedIds.size}" else "LUT 管理",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "取消选择"
                            )
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = { showBatchDeleteDialog = true },
                            enabled = deletableSelection.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "批量删除"
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { lutFilePicker.launch(arrayOf("*/*")) }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "导入 LUT"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = LumaGold
                )
            } else if (lutFilters.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoFilter,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "暂无 LUT",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "点击右上角 + 导入 .cube 或 .3dl 文件",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { lutFilePicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LumaGold,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入 LUT")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = lutFilters,
                        key = { _, lut -> lut.id }
                    ) { index, lut ->
                        var thumbnailBitmap by remember(lut.thumbnailPath) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(lut.thumbnailPath) {
                            val path = lut.thumbnailPath
                                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                            thumbnailBitmap = withContext(Dispatchers.IO) {
                                if (path == null) {
                                    null
                                } else {
                                    val file = File(path)
                                    if (file.exists()) {
                                        BitmapFactory.decodeFile(file.absolutePath)
                                    } else {
                                        null
                                    }
                                }
                            }
                        }
                        val previewBitmap = thumbnailBitmap ?: lutPreviews[lut.id]
                        val isSelected = selectedIds.contains(lut.id)
                        val isDragging = draggingId == lut.id
                        val stepPx = if (itemHeightPx > 0f) itemHeightPx + spacingPx else fallbackItemHeightPx + spacingPx
                        val startIndex = dragStartIndex
                        val targetIndex = dragTargetIndex
                        val targetShift = if (!isDragging && startIndex != null && targetIndex != null) {
                            when {
                                targetIndex > startIndex && index in (startIndex + 1..targetIndex) -> -stepPx
                                targetIndex < startIndex && index in (targetIndex until startIndex) -> stepPx
                                else -> 0f
                            }
                        } else {
                            0f
                        }
                        val animatedShift by animateFloatAsState(
                            targetValue = targetShift,
                            label = "dragShift"
                        )
                        val dragTranslationY = if (isDragging) dragOffsetY else animatedShift

                        LutListItem(
                            lut = lut,
                            preview = previewBitmap,
                            canDelete = !lut.isBuiltIn,
                            canRename = true,
                            selectionMode = selectionMode,
                            isSelected = isSelected,
                            isDragging = isDragging,
                            offsetY = dragTranslationY,
                            onToggleSelection = {
                                toggleSelection(lut.id)
                            },
                            onShare = {
                                viewModel.pendingExportLut = lut
                                exportFilePicker.launch("${lut.name}.cube")
                            },
                            onRename = {
                                renameText = lut.name
                                showRenameDialog = lut
                            },
                            onSetThumbnail = {
                                pendingThumbnailLut = lut
                                thumbnailPicker.launch("image/*")
                            },
                            onPin = {
                                viewModel.pinLut(lut.id)
                            },
                            onDelete = {
                                showDeleteDialog = lut
                            },
                            onDragStart = { _ ->
                                if (!selectionMode) {
                                    selectedIds = setOf(lut.id)
                                } else if (!selectedIds.contains(lut.id)) {
                                    toggleSelection(lut.id)
                                }
                                draggingId = lut.id
                                val startIndex = lutFilters.indexOfFirst { it.id == lut.id }
                                dragStartIndex = startIndex
                                dragTargetIndex = dragStartIndex
                                dragOffsetY = 0f
                                lastHapticIndex = dragStartIndex
                                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == startIndex }
                                dragStartItemOffsetPx = info?.offset?.toFloat() ?: 0f
                                dragItemHeightPx = info?.size?.toFloat()
                                    ?: if (itemHeightPx > 0f) itemHeightPx else fallbackItemHeightPx
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { delta ->
                                val startIndex = dragStartIndex
                                if (startIndex == null) return@LutListItem
                                val height = if (dragItemHeightPx > 0f) dragItemHeightPx else fallbackItemHeightPx
                                val stepPx = if (dragItemHeightPx > 0f) dragItemHeightPx + spacingPx else fallbackItemHeightPx + spacingPx
                                val startOffset = dragStartItemOffsetPx
                                val layoutInfo = listState.layoutInfo
                                val viewportStart = layoutInfo.viewportStartOffset.toFloat()
                                val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
                                val minOffset = max(viewportStart - startOffset, -startIndex * stepPx)
                                val maxOffset = min(viewportEnd - startOffset - height, (lutFilters.lastIndex - startIndex) * stepPx)
                                dragOffsetY = (dragOffsetY + delta).coerceIn(minOffset, maxOffset)

                                val target = if (stepPx > 0f) {
                                    (startIndex + (dragOffsetY / stepPx).roundToInt())
                                } else {
                                    startIndex
                                }.coerceIn(0, lutFilters.lastIndex)
                                if (target != dragTargetIndex) {
                                    dragTargetIndex = target
                                    if (lastHapticIndex != target) {
                                        lastHapticIndex = target
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            },
                            onDragEnd = {
                                val startIndex = dragStartIndex
                                val targetIndex = dragTargetIndex
                                if (startIndex != null && targetIndex != null && targetIndex != startIndex) {
                                    viewModel.reorderLuts(startIndex, targetIndex)
                                }
                                draggingId = null
                                dragStartIndex = null
                                dragTargetIndex = null
                                dragOffsetY = 0f
                                dragStartItemOffsetPx = 0f
                                dragItemHeightPx = 0f
                                lastHapticIndex = null
                            },
                            onDragCancel = {
                                draggingId = null
                                dragStartIndex = null
                                dragTargetIndex = null
                                dragOffsetY = 0f
                                dragStartItemOffsetPx = 0f
                                dragItemHeightPx = 0f
                                lastHapticIndex = null
                            },
                            onSizeKnown = { height ->
                                if (itemHeightPx <= 0f && height > 0) {
                                    itemHeightPx = height.toFloat()
                                }
                            },
                            modifier = Modifier
                        )
                    }
                }
            }
        }
    }

    showDeleteDialog?.let { lut ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除 LUT") },
            text = { Text("确定要删除「${lut.name}」吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.deleteLut(lut.id)
                        }
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f)
        )
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("批量删除") },
            text = {
                Text(
                    text = if (deletableSelection.isEmpty()) {
                        "所选 LUT 均为内置项，无法删除。"
                    } else {
                        "将删除 ${deletableSelection.size} 个 LUT，内置 LUT 不会被删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (deletableSelection.isNotEmpty()) {
                                viewModel.deleteLuts(deletableSelection.map { it.id })
                            }
                        }
                        selectedIds = emptySet()
                        showBatchDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (deletableSelection.isEmpty()) Color.Gray else Color.Red
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("取消")
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f)
        )
    }

    showRenameDialog?.let { lut ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("重命名 LUT") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LumaGold,
                        cursorColor = LumaGold,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            scope.launch {
                                viewModel.renameLut(lut.id, renameText.trim())
                            }
                        }
                        showRenameDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = LumaGold
                    )
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("取消")
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White
        )
    }

    if (editorBitmap != null && editorLut != null) {
        ThumbnailEditorDialog(
            bitmap = editorBitmap!!,
            onCancel = {
                editorBitmap = null
                editorLut = null
            },
            onConfirm = { result ->
                val target = editorLut
                if (target != null) {
                    scope.launch {
                        viewModel.updateLutThumbnailBitmap(target.id, result)
                    }
                }
                editorBitmap = null
                editorLut = null
            }
        )
    }
}
@Composable
private fun LutListItem(
    lut: LutFilter,
    preview: Bitmap?,
    canDelete: Boolean,
    canRename: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean,
    offsetY: Float,
    onToggleSelection: () -> Unit,
    onShare: () -> Unit,
    onRename: (() -> Unit)?,
    onSetThumbnail: () -> Unit,
    onPin: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onSizeKnown: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val borderColor = if (isSelected) LumaGold else Color.Transparent
    val backgroundColor by animateColorAsState(
        targetValue = if (isDragging) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
        label = "dragBackground"
    )
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        label = "dragScale"
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        label = "dragElevation"
    )
    val density = LocalDensity.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = offsetY
                scaleX = dragScale
                scaleY = dragScale
                shadowElevation = with(density) { dragElevation.toPx() }
                shape = RoundedCornerShape(12.dp)
                clip = true
            }
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .pointerInput(lut.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStart(offset.y) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            }
            .clickable {
                if (selectionMode) {
                    onToggleSelection()
                } else {
                    showMenu = !showMenu
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray)
                .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .onSizeChanged { onSizeKnown(it.height) }
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = lut.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PhotoFilter,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = LumaGold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = lut.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (lut.isBuiltIn) "内置" else "用户",
                    color = if (lut.isBuiltIn) LumaGold else Color.Cyan,
                    fontSize = 12.sp
                )

                Text(
                    text = " · ${lut.size.dimension}x${lut.size.dimension}x${lut.size.dimension}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多操作",
                    tint = Color.White
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onShare()
                    }
                )

                DropdownMenuItem(
                    text = { Text("设置缩略图") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onSetThumbnail()
                    }
                )

                if (!lut.isPinned && onPin != null) {
                    DropdownMenuItem(
                        text = { Text("置顶") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Star, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onPin()
                        }
                    )
                } else if (lut.isPinned) {
                    DropdownMenuItem(
                        text = { Text("已置顶") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Star, contentDescription = null)
                        },
                        enabled = false,
                        onClick = { }
                    )
                }

                if (canRename && onRename != null) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                }

                if (canDelete && onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("删除", color = Color.Red) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = Color.Red
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailEditorDialog(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val density = LocalDensity.current
    val cropSize = 240.dp
    val cropSizePx = with(density) { cropSize.toPx() }
    val outputSizePx = 256

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var rotationSteps by remember { mutableStateOf(0) }

    val rotationDegrees = (rotationSteps % 4) * 90

    fun clampOffset(currentX: Float, currentY: Float): Pair<Float, Float> {
        val rotatedWidth = if (rotationDegrees % 180 == 0) bitmap.width.toFloat() else bitmap.height.toFloat()
        val rotatedHeight = if (rotationDegrees % 180 == 0) bitmap.height.toFloat() else bitmap.width.toFloat()
        val baseScale = max(cropSizePx / rotatedWidth, cropSizePx / rotatedHeight)
        val totalScale = baseScale * scale
        val scaledWidth = rotatedWidth * totalScale
        val scaledHeight = rotatedHeight * totalScale
        val maxX = max(0f, (scaledWidth - cropSizePx) / 2f)
        val maxY = max(0f, (scaledHeight - cropSizePx) / 2f)
        return Pair(currentX.coerceIn(-maxX, maxX), currentY.coerceIn(-maxY, maxY))
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("编辑缩略图") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(cropSize)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .pointerInput(rotationDegrees) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 4f)
                                scale = newScale
                                val newOffsetX = offsetX + pan.x
                                val newOffsetY = offsetY + pan.y
                                val (clampedX, clampedY) = clampOffset(newOffsetX, newOffsetY)
                                offsetX = clampedX
                                offsetY = clampedY
                            }
                        }
                ) {
                    val rotatedWidth = if (rotationDegrees % 180 == 0) bitmap.width.toFloat() else bitmap.height.toFloat()
                    val rotatedHeight = if (rotationDegrees % 180 == 0) bitmap.height.toFloat() else bitmap.width.toFloat()
                    val baseScale = max(cropSizePx / rotatedWidth, cropSizePx / rotatedHeight)
                    val totalScale = baseScale * scale

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = offsetX
                                translationY = offsetY
                                scaleX = totalScale
                                scaleY = totalScale
                                rotationZ = rotationDegrees.toFloat()
                            }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            rotationSteps = (rotationSteps + 3) % 4
                            val (clampedX, clampedY) = clampOffset(offsetX, offsetY)
                            offsetX = clampedX
                            offsetY = clampedY
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.RotateLeft, contentDescription = "向左旋转")
                    }
                    IconButton(
                        onClick = {
                            rotationSteps = (rotationSteps + 1) % 4
                            val (clampedX, clampedY) = clampOffset(offsetX, offsetY)
                            offsetX = clampedX
                            offsetY = clampedY
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "向右旋转")
                    }
                    TextButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotationSteps = 0
                        }
                    ) {
                        Text("重置")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = createThumbnailBitmap(
                        source = bitmap,
                        rotationDegrees = rotationDegrees,
                        userScale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        containerSizePx = cropSizePx,
                        outputSizePx = outputSizePx
                    )
                    onConfirm(result)
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = LumaGold
                )
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        },
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.85f)
    )
}

private fun createThumbnailBitmap(
    source: Bitmap,
    rotationDegrees: Int,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    containerSizePx: Float,
    outputSizePx: Int
): Bitmap {
    val rotatedWidth = if (rotationDegrees % 180 == 0) source.width.toFloat() else source.height.toFloat()
    val rotatedHeight = if (rotationDegrees % 180 == 0) source.height.toFloat() else source.width.toFloat()
    val baseScale = max(containerSizePx / rotatedWidth, containerSizePx / rotatedHeight)
    val outputScaleFactor = outputSizePx / containerSizePx
    val totalScale = baseScale * userScale * outputScaleFactor
    val outputOffsetX = offsetX * outputScaleFactor
    val outputOffsetY = offsetY * outputScaleFactor

    val bitmap = Bitmap.createBitmap(outputSizePx, outputSizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    val matrix = Matrix()
    matrix.postTranslate(-source.width / 2f, -source.height / 2f)
    matrix.postRotate(rotationDegrees.toFloat())
    matrix.postScale(totalScale, totalScale)
    matrix.postTranslate(outputSizePx / 2f + outputOffsetX, outputSizePx / 2f + outputOffsetY)

    canvas.drawBitmap(source, matrix, paint)

    return bitmap
}
