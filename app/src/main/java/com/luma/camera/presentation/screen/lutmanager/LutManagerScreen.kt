package com.luma.camera.presentation.screen.lutmanager

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.presentation.theme.LumaGold
import kotlinx.coroutines.launch

/**
 * LUT 管理页面
 * 
 * 功能：
 * - 显示所有 LUT 列表（带预览图）
 * - 导入新 LUT
 * - 重命名 LUT
 * - 分享 LUT
 * - 删除 LUT
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LutManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: LutManagerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val lutFilters by viewModel.lutFilters.collectAsState()
    val lutPreviews by viewModel.lutPreviews.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    var showDeleteDialog by remember { mutableStateOf<LutFilter?>(null) }
    var showRenameDialog by remember { mutableStateOf<LutFilter?>(null) }
    var renameText by remember { mutableStateOf("") }
    
    // 文件选择器
    val lutFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                viewModel.importLut(it)
            }
        }
    }
    
    // 导出文件选择器
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "LUT 管理",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 导入按钮
                    IconButton(
                        onClick = {
                            lutFilePicker.launch(arrayOf("*/*"))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "导入 LUT"
                        )
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
                // 空状态
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
                        text = "点击右上角 + 号导入 .cube 或 .3dl 文件",
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 内置 LUT 分组
                    val builtInLuts = lutFilters.filter { it.isBuiltIn }
                    val userLuts = lutFilters.filter { !it.isBuiltIn }
                    
                    if (builtInLuts.isNotEmpty()) {
                        item {
                            Text(
                                text = "内置 LUT (${builtInLuts.size})",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        
                        itemsIndexed(
                            items = builtInLuts,
                            key = { _, lut -> lut.id }
                        ) { _, lut ->
                            LutListItem(
                                lut = lut,
                                preview = lutPreviews[lut.id],
                                canDelete = false,
                                canRename = false,
                                onShare = {
                                    viewModel.pendingExportLut = lut
                                    exportFilePicker.launch("${lut.name}.cube")
                                },
                                onRename = null,
                                onDelete = null
                            )
                        }
                    }
                    
                    if (userLuts.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "用户导入 (${userLuts.size})",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        
                        itemsIndexed(
                            items = userLuts,
                            key = { _, lut -> lut.id }
                        ) { _, lut ->
                            LutListItem(
                                lut = lut,
                                preview = lutPreviews[lut.id],
                                canDelete = true,
                                canRename = true,
                                onShare = {
                                    viewModel.pendingExportLut = lut
                                    exportFilePicker.launch("${lut.name}.cube")
                                },
                                onRename = {
                                    renameText = lut.name
                                    showRenameDialog = lut
                                },
                                onDelete = {
                                    showDeleteDialog = lut
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // 删除确认对话框
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
    
    // 重命名对话框
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
}

/**
 * LUT 列表项
 */
@Composable
private fun LutListItem(
    lut: LutFilter,
    preview: Bitmap?,
    canDelete: Boolean,
    canRename: Boolean,
    onShare: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { showMenu = !showMenu }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 预览图
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray)
                .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = lut.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 占位符
                Icon(
                    imageVector = Icons.Outlined.PhotoFilter,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 名称和信息
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
        
        // 操作按钮
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
