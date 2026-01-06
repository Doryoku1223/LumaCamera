package com.luma.camera.lut

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.domain.model.LutSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LUT 管理器
 *
 * 职责：
 * - 管理内置和用户导入的 LUT
 * - 内置 LUT 从 res/raw/luts/ 或 assets/luts/ 读取
 * - 用户导入的 LUT 存储到应用私有目录
 * - 提供 CRUD 操作
 * - 启动时预加载所有 LUT 到 GPU 显存
 */
@Singleton
class LutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lutParser: LutParser,
    private val gpuLutRenderer: GpuLutRenderer
) {
    companion object {
        private const val ASSETS_LUT_DIR = "luts"
        private const val TAG = "LutManager"
        
        // LUT 文件名到中文显示名称的映射
        private val LUT_DISPLAY_NAMES = mapOf(
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
        
        // res/raw/luts/ 目录下的 LUT 文件列表
        private val RAW_LUT_FILES = listOf(
            "eterna",
            "eterna_bb",
            "classic_chrome",
            "classic_neg",
            "astia",
            "pro_neg_std",
            "provia",
            "velvia",
            "cold",
            "warm",
            "hasselblad_portrait",
            "forest_green",
            "warm_skin",
            "beach_portrait",
            "sunset",
            "snow_portrait"
        )
    }

    // 所有可用的 LUT 列表
    private val _lutFilters = MutableStateFlow<List<LutFilter>>(emptyList())
    val lutFilters: StateFlow<List<LutFilter>> = _lutFilters.asStateFlow()

    // 当前选中的 LUT
    private val _currentLut = MutableStateFlow<LutFilter?>(null)
    val currentLut: StateFlow<LutFilter?> = _currentLut.asStateFlow()

    // LUT 强度
    private val _lutIntensity = MutableStateFlow(1.0f)
    val lutIntensity: StateFlow<Float> = _lutIntensity.asStateFlow()
    
    // 初始化状态
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // LUT 数据缓存
    private val lutDataCache = mutableMapOf<String, LutParser.LutData>()

    // 用户 LUT 存储目录
    private val userLutDir: File by lazy {
        File(context.filesDir, "user_luts").apply { mkdirs() }
    }

    /**
     * 初始化，快速加载 LUT 列表（不预加载数据）
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        Timber.d("Initializing LutManager...")
        val startTime = System.currentTimeMillis()
        val filters = mutableListOf<LutFilter>()

        // 快速加载内置 LUT 列表（只读取元数据，不解析 LUT 数据）
        filters.addAll(loadBuiltInLutsQuick())

        // 加载用户导入的 LUT
        filters.addAll(loadUserLutsQuick())

        // 按排序顺序排列
        _lutFilters.value = filters.sortedBy { it.sortOrder }
        _isInitialized.value = true

        val loadTime = System.currentTimeMillis() - startTime
        Timber.d("LUT list loaded in ${loadTime}ms (${filters.size} LUTs)")

        // 后台预加载 LUT 数据到 GPU（不阻塞 UI）
        preloadToGpuAsync()
    }
    
    /**
     * 快速加载内置 LUT 列表（只创建 LutFilter，不解析数据）
     */
    private fun loadBuiltInLutsQuick(): List<LutFilter> {
        val filters = mutableListOf<LutFilter>()

        try {
            val assetManager = context.assets
            val lutFiles = assetManager.list(ASSETS_LUT_DIR) ?: emptyArray()
            
            Timber.d("Found ${lutFiles.size} files in assets/$ASSETS_LUT_DIR/")

            lutFiles.forEachIndexed { index, fileName ->
                if (fileName.endsWith(".cube") || fileName.endsWith(".3dl")) {
                    val lutName = fileName.substringBeforeLast(".")
                    val lutId = "builtin_$fileName"
                    val displayName = LUT_DISPLAY_NAMES[lutName] ?: lutName
                    
                    filters.add(
                        LutFilter(
                            id = lutId,
                            name = displayName,
                            filePath = "assets://$ASSETS_LUT_DIR/$fileName",
                            isBuiltIn = true,
                            sortOrder = index,
                            size = com.luma.camera.domain.model.LutSize.SIZE_33  // 默认大小
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to list LUT assets")
        }
        
        return filters
    }
    
    /**
     * 快速加载用户 LUT 列表
     */
    private fun loadUserLutsQuick(): List<LutFilter> {
        val filters = mutableListOf<LutFilter>()

        userLutDir.listFiles()?.filter { 
            it.isFile && (it.extension == "cube" || it.extension == "3dl")
        }?.forEachIndexed { index, file ->
            filters.add(
                LutFilter(
                    id = file.name,
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    isBuiltIn = false,
                    sortOrder = 1000 + index,
                    size = com.luma.camera.domain.model.LutSize.SIZE_33
                )
            )
        }

        return filters
    }
    
    /**
     * 异步预加载 LUT 到 GPU
     */
    private suspend fun preloadToGpuAsync() = withContext(Dispatchers.IO) {
        Timber.d("Starting async LUT preload...")
        val startTime = System.currentTimeMillis()
        
        for (filter in _lutFilters.value) {
            try {
                // 懒加载 LUT 数据
                val lutData = loadLutData(filter)
                if (lutData != null) {
                    lutDataCache[filter.id] = lutData
                    gpuLutRenderer.uploadLut(filter.id, lutData)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to preload LUT: ${filter.name}")
            }
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        Timber.d("All LUTs preloaded in ${totalTime}ms")
    }
    
    /**
     * 加载单个 LUT 的数据
     */
    private fun loadLutData(filter: LutFilter): LutParser.LutData? {
        // 先检查缓存
        lutDataCache[filter.id]?.let { return it }
        
        return try {
            if (filter.filePath.startsWith("assets://")) {
                // 从 assets 加载
                val assetPath = filter.filePath.removePrefix("assets://")
                val inputStream = context.assets.open(assetPath)
                if (filter.filePath.endsWith(".cube")) {
                    lutParser.parseCubeStream(inputStream, filter.name)
                } else {
                    lutParser.parse3dlStream(inputStream, filter.name)
                }
            } else {
                // 从文件加载
                lutParser.parseFromFile(File(filter.filePath))
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load LUT data: ${filter.name}")
            null
        }
    }
    
    /**
     * 确保 LUT 已加载（按需加载）
     */
    suspend fun ensureLutLoaded(lutId: String) = withContext(Dispatchers.IO) {
        if (lutDataCache.containsKey(lutId)) return@withContext
        
        val filter = _lutFilters.value.find { it.id == lutId } ?: return@withContext
        val lutData = loadLutData(filter)
        if (lutData != null) {
            lutDataCache[filter.id] = lutData
            gpuLutRenderer.uploadLut(filter.id, lutData)
        }
    }

    /**
     * 初始化，加载所有 LUT（兼容旧代码，现在是异步的）
     */
    @Deprecated("Use initialize() which is now async", ReplaceWith("initialize()"))
    suspend fun initializeLegacy() = withContext(Dispatchers.IO) {
        Timber.d("Initializing LutManager (legacy)...")
        val filters = mutableListOf<LutFilter>()

        // 加载内置 LUT (从 assets/luts/)
        filters.addAll(loadBuiltInLuts())

        // 加载用户导入的 LUT
        filters.addAll(loadUserLuts())

        // 按排序顺序排列
        _lutFilters.value = filters.sortedBy { it.sortOrder }

        Timber.d("Loaded ${filters.size} LUTs total")

        // 预加载到 GPU
        preloadToGpu()
    }

    /**
     * 加载内置 LUT (从 assets/luts/)
     */
    private fun loadBuiltInLuts(): List<LutFilter> {
        val filters = mutableListOf<LutFilter>()

        try {
            val assetManager = context.assets
            val lutFiles = assetManager.list(ASSETS_LUT_DIR) ?: emptyArray()
            
            Timber.d("Found ${lutFiles.size} files in assets/$ASSETS_LUT_DIR/")

            lutFiles.forEachIndexed { index, fileName ->
                if (fileName.endsWith(".cube") || fileName.endsWith(".3dl")) {
                    try {
                        val inputStream = assetManager.open("$ASSETS_LUT_DIR/$fileName")
                        val lutName = fileName.substringBeforeLast(".")
                        val lutData = if (fileName.endsWith(".cube")) {
                            lutParser.parseCubeStream(inputStream, lutName)
                        } else {
                            lutParser.parse3dlStream(inputStream, lutName)
                        }
                        
                        val lutId = "builtin_$fileName"
                        lutDataCache[lutId] = lutData
                        
                        // 使用中文显示名称，如果没有映射则使用原始标题
                        val displayName = LUT_DISPLAY_NAMES[lutName] ?: lutData.title

                        filters.add(
                            LutFilter(
                                id = lutId,
                                name = displayName,
                                filePath = "assets://$ASSETS_LUT_DIR/$fileName",
                                isBuiltIn = true,
                                sortOrder = index,
                                size = lutParser.getLutSize(lutData.size)
                            )
                        )
                        Timber.d("Loaded built-in LUT: $lutName -> $displayName")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load built-in LUT: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to list LUT assets")
        }
        
        Timber.d("Loaded ${filters.size} built-in LUTs from assets/$ASSETS_LUT_DIR/")
        return filters
    }

    /**
     * 加载用户导入的 LUT
     */
    private fun loadUserLuts(): List<LutFilter> {
        val filters = mutableListOf<LutFilter>()

        userLutDir.listFiles()?.filter { 
            it.isFile && (it.extension == "cube" || it.extension == "3dl")
        }?.forEachIndexed { index, file ->
            try {
                val lutData = lutParser.parseFromFile(file)
                lutDataCache[file.name] = lutData

                filters.add(
                    LutFilter(
                        id = file.name,
                        name = lutData.title,
                        filePath = file.absolutePath,
                        isBuiltIn = false,
                        sortOrder = 1000 + index,
                        size = lutParser.getLutSize(lutData.size)
                    )
                )
            } catch (e: Exception) {
                // 跳过无法解析的文件
            }
        }

        return filters
    }

    /**
     * 预加载所有 LUT 到 GPU
     */
    private suspend fun preloadToGpu() = withContext(Dispatchers.Default) {
        for ((id, lutData) in lutDataCache) {
            gpuLutRenderer.uploadLut(id, lutData)
        }
    }

    /**
     * 导入 LUT 文件
     */
    suspend fun importLut(sourceFile: File): LutFilter = withContext(Dispatchers.IO) {
        // 复制到用户目录
        val destFile = File(userLutDir, sourceFile.name)
        sourceFile.copyTo(destFile, overwrite = true)

        // 解析 LUT
        val lutData = lutParser.parseFromFile(destFile)
        lutDataCache[destFile.name] = lutData

        // 创建 LutFilter
        val filter = LutFilter(
            id = destFile.name,
            name = lutData.title,
            filePath = destFile.absolutePath,
            isBuiltIn = false,
            sortOrder = _lutFilters.value.size,
            size = lutParser.getLutSize(lutData.size)
        )

        // 上传到 GPU
        gpuLutRenderer.uploadLut(filter.id, lutData)

        // 更新列表
        _lutFilters.value = _lutFilters.value + filter

        filter
    }

    /**
     * 批量导入 LUT
     */
    suspend fun importLuts(files: List<File>): List<LutFilter> = withContext(Dispatchers.IO) {
        files.mapNotNull { file ->
            try {
                importLut(file)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 删除 LUT
     */
    suspend fun deleteLut(lutId: String): Boolean = withContext(Dispatchers.IO) {
        val filter = _lutFilters.value.find { it.id == lutId } ?: return@withContext false

        // 不能删除内置 LUT
        if (filter.isBuiltIn) return@withContext false

        // 删除文件
        val file = File(filter.filePath)
        if (file.exists()) {
            file.delete()
        }

        // 从 GPU 移除
        gpuLutRenderer.removeLut(lutId)

        // 从缓存移除
        lutDataCache.remove(lutId)

        // 更新列表
        _lutFilters.value = _lutFilters.value.filter { it.id != lutId }

        true
    }

    /**
     * 重命名 LUT
     */
    suspend fun renameLut(lutId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val filter = _lutFilters.value.find { it.id == lutId } ?: return@withContext false

        val updatedFilter = filter.copy(name = newName)
        _lutFilters.value = _lutFilters.value.map { 
            if (it.id == lutId) updatedFilter else it 
        }

        true
    }

    /**
     * 调整 LUT 排序
     */
    fun reorderLuts(fromIndex: Int, toIndex: Int) {
        val list = _lutFilters.value.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)

        // 更新排序序号
        _lutFilters.value = list.mapIndexed { index, filter ->
            filter.copy(sortOrder = index)
        }
    }

    /**
     * 选择当前 LUT
     */
    fun selectLut(lutId: String?) {
        _currentLut.value = if (lutId == null) {
            null
        } else {
            _lutFilters.value.find { it.id == lutId }
        }
    }

    /**
     * 获取 LUT 数据
     */
    fun getLutData(lutId: String): LutParser.LutData? {
        return lutDataCache[lutId]
    }

    /**
     * 应用 LUT 到图像（CPU 实现，用于保存照片）
     */
    suspend fun applyLut(
        input: Bitmap,
        lutId: String,
        intensity: Float = 1.0f
    ): Bitmap = withContext(Dispatchers.Default) {
        // 确保 LUT 数据已加载
        ensureLutLoaded(lutId)
        
        val lutData = lutDataCache[lutId]
        if (lutData == null) {
            Timber.w("LUT data not found: $lutId")
            return@withContext input
        }
        
        // 使用 CPU 实现 LUT 应用
        applyCpuLutOptimized(input, lutData, intensity)
    }
    
    /**
     * 高性能 CPU 实现的 LUT 应用
     * 
     * 优化策略：
     * 1. 使用 Java 并行流进行真正的并行处理
     * 2. 完全内联三线性插值，消除所有函数调用
     * 3. 避免在热循环中使用 coerceIn/floor 等函数
     * 4. 使用位运算优化整数操作
     * 5. 预计算所有常量
     */
    private fun applyCpuLutOptimized(input: Bitmap, lutData: LutParser.LutData, intensity: Float): Bitmap {
        val width = input.width
        val height = input.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val size = lutData.size
        val data = lutData.data
        val oneMinusIntensity = 1f - intensity
        val sizeMinusOne = size - 1
        val sizeSq = size * size
        
        // 预计算归一化常量
        val invSize = 1f / size
        val sizeF = size.toFloat()
        
        // 使用分块处理优化性能（Android 上 Java Stream 效率低）
        val numCores = Runtime.getRuntime().availableProcessors()
        val chunkSize = (totalPixels + numCores - 1) / numCores
        val threads = Array(numCores) { threadIdx ->
            Thread {
                val startIdx = threadIdx * chunkSize
                val endIdx = minOf(startIdx + chunkSize, totalPixels)
                for (i in startIdx until endIdx) {
                    val pixel = pixels[i]
                    val a = pixel and 0xFF000000.toInt()
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
            
            // ===== 优化的三线性插值 =====
            // 直接计算 LUT 索引，避免归一化步骤
            // 原公式: coord = (color/255) * (size-1)/size + 0.5/size
            // 简化为: idx = coord * size - 0.5 = color * (size-1) / 255 + 0.5 - 0.5 = color * (size-1) / 255
            val rIdxF = r * sizeMinusOne / 255f
            val gIdxF = g * sizeMinusOne / 255f
            val bIdxF = b * sizeMinusOne / 255f
            
            // 获取整数部分和小数部分
            val r0 = rIdxF.toInt()
            val g0 = gIdxF.toInt()
            val b0 = bIdxF.toInt()
            
            // 确保不越界
            val r0c = if (r0 < 0) 0 else if (r0 > sizeMinusOne) sizeMinusOne else r0
            val g0c = if (g0 < 0) 0 else if (g0 > sizeMinusOne) sizeMinusOne else g0
            val b0c = if (b0 < 0) 0 else if (b0 > sizeMinusOne) sizeMinusOne else b0
            val r1c = if (r0c >= sizeMinusOne) sizeMinusOne else r0c + 1
            val g1c = if (g0c >= sizeMinusOne) sizeMinusOne else g0c + 1
            val b1c = if (b0c >= sizeMinusOne) sizeMinusOne else b0c + 1
            
            // 计算插值权重
            var rFrac = rIdxF - r0c
            var gFrac = gIdxF - g0c
            var bFrac = bIdxF - b0c
            if (rFrac < 0f) rFrac = 0f else if (rFrac > 1f) rFrac = 1f
            if (gFrac < 0f) gFrac = 0f else if (gFrac > 1f) gFrac = 1f
            if (bFrac < 0f) bFrac = 0f else if (bFrac > 1f) bFrac = 1f
            val rFracInv = 1f - rFrac
            val gFracInv = 1f - gFrac
            val bFracInv = 1f - bFrac
            
            // 计算 8 个角的基础索引（LUT 格式：B 最慢，G 中间，R 最快）
            val base000 = (b0c * sizeSq + g0c * size + r0c) * 3
            val base001 = (b1c * sizeSq + g0c * size + r0c) * 3
            val base010 = (b0c * sizeSq + g1c * size + r0c) * 3
            val base011 = (b1c * sizeSq + g1c * size + r0c) * 3
            val base100 = (b0c * sizeSq + g0c * size + r1c) * 3
            val base101 = (b1c * sizeSq + g0c * size + r1c) * 3
            val base110 = (b0c * sizeSq + g1c * size + r1c) * 3
            val base111 = (b1c * sizeSq + g1c * size + r1c) * 3
            
            // R 通道三线性插值（完全内联）
            val r000 = data[base000]
            val r001 = data[base001]
            val r010 = data[base010]
            val r011 = data[base011]
            val r100 = data[base100]
            val r101 = data[base101]
            val r110 = data[base110]
            val r111 = data[base111]
            val r00 = r000 * rFracInv + r100 * rFrac
            val r01 = r001 * rFracInv + r101 * rFrac
            val r10 = r010 * rFracInv + r110 * rFrac
            val r11 = r011 * rFracInv + r111 * rFrac
            val rr0 = r00 * gFracInv + r10 * gFrac
            val rr1 = r01 * gFracInv + r11 * gFrac
            val newRf = (rr0 * bFracInv + rr1 * bFrac) * 255f
            
            // G 通道三线性插值
            val g000 = data[base000 + 1]
            val g001 = data[base001 + 1]
            val g010 = data[base010 + 1]
            val g011 = data[base011 + 1]
            val g100 = data[base100 + 1]
            val g101 = data[base101 + 1]
            val g110 = data[base110 + 1]
            val g111 = data[base111 + 1]
            val g00 = g000 * rFracInv + g100 * rFrac
            val g01 = g001 * rFracInv + g101 * rFrac
            val g10 = g010 * rFracInv + g110 * rFrac
            val g11 = g011 * rFracInv + g111 * rFrac
            val gg0 = g00 * gFracInv + g10 * gFrac
            val gg1 = g01 * gFracInv + g11 * gFrac
            val newGf = (gg0 * bFracInv + gg1 * bFrac) * 255f
            
            // B 通道三线性插值
            val b000 = data[base000 + 2]
            val b001 = data[base001 + 2]
            val b010 = data[base010 + 2]
            val b011 = data[base011 + 2]
            val b100 = data[base100 + 2]
            val b101 = data[base101 + 2]
            val b110 = data[base110 + 2]
            val b111 = data[base111 + 2]
            val b00 = b000 * rFracInv + b100 * rFrac
            val b01 = b001 * rFracInv + b101 * rFrac
            val b10 = b010 * rFracInv + b110 * rFrac
            val b11 = b011 * rFracInv + b111 * rFrac
            val bb0 = b00 * gFracInv + b10 * gFrac
            val bb1 = b01 * gFracInv + b11 * gFrac
            val newBf = (bb0 * bFracInv + bb1 * bFrac) * 255f
            
            // 根据强度混合并写回（使用位运算优化）
            var finalR = (oneMinusIntensity * r + intensity * newRf).toInt()
            var finalG = (oneMinusIntensity * g + intensity * newGf).toInt()
            var finalB = (oneMinusIntensity * b + intensity * newBf).toInt()
            if (finalR < 0) finalR = 0 else if (finalR > 255) finalR = 255
            if (finalG < 0) finalG = 0 else if (finalG > 255) finalG = 255
            if (finalB < 0) finalB = 0 else if (finalB > 255) finalB = 255
            
            pixels[i] = a or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }
        
        // 启动所有线程
        threads.forEach { it.start() }
        // 等待所有线程完成
        threads.forEach { it.join() }
        
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
    
    /**
     * 三线性插值 LUT 查找（与 GPU shader 完全一致的算法）
     * @return FloatArray [r, g, b] 范围 0-255
     */
    private fun trilinearLutLookup(r: Int, g: Int, b: Int, data: FloatArray, size: Int): FloatArray {
        // 归一化到 0-1 范围
        val rNorm = r / 255f
        val gNorm = g / 255f
        val bNorm = b / 255f
        
        // 与 GPU shader 完全一致的坐标计算
        // GPU: vec3 lutCoord = color * scale + offset
        // scale = (size - 1.0) / size, offset = 0.5 / size
        // 这会将 [0,1] 映射到 LUT 的有效采样区域 [0.5/size, 1-0.5/size]
        val scale = (size - 1f) / size
        val offset = 0.5f / size
        
        // lutCoord 范围是 [0.5/size, 1-0.5/size]，对应 LUT 索引 [0, size-1]
        val rLutCoord = rNorm * scale + offset
        val gLutCoord = gNorm * scale + offset
        val bLutCoord = bNorm * scale + offset
        
        // 将归一化坐标转换为连续索引（0 到 size-1 范围）
        // texCoord * size - 0.5 给出精确的索引位置
        val rIdx = rLutCoord * size - 0.5f
        val gIdx = gLutCoord * size - 0.5f
        val bIdx = bLutCoord * size - 0.5f
        
        // 获取整数坐标和小数部分（用于插值）
        val r0 = kotlin.math.floor(rIdx).toInt().coerceIn(0, size - 1)
        val g0 = kotlin.math.floor(gIdx).toInt().coerceIn(0, size - 1)
        val b0 = kotlin.math.floor(bIdx).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        
        val rFrac = (rIdx - r0).coerceIn(0f, 1f)
        val gFrac = (gIdx - g0).coerceIn(0f, 1f)
        val bFrac = (bIdx - b0).coerceIn(0f, 1f)
        
        // 获取 8 个角的 LUT 值
        // CUBE 标准格式：R 变化最快，G 次之，B 最慢
        // 即数据按照 for B: for G: for R: 顺序排列
        // 索引 = B * size² + G * size + R
        fun getLutValue(ri: Int, gi: Int, bi: Int): FloatArray {
            val index = (bi * size * size + gi * size + ri) * 3
            return if (index + 2 < data.size) {
                floatArrayOf(data[index] * 255f, data[index + 1] * 255f, data[index + 2] * 255f)
            } else {
                floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
            }
        }
        
        // 8 个角的值
        val c000 = getLutValue(r0, g0, b0)
        val c001 = getLutValue(r0, g0, b1)
        val c010 = getLutValue(r0, g1, b0)
        val c011 = getLutValue(r0, g1, b1)
        val c100 = getLutValue(r1, g0, b0)
        val c101 = getLutValue(r1, g0, b1)
        val c110 = getLutValue(r1, g1, b0)
        val c111 = getLutValue(r1, g1, b1)
        
        // 三线性插值（与 GPU texture() 函数一致的插值顺序）
        val result = FloatArray(3)
        for (channel in 0..2) {
            // 沿 R 轴插值（最内层）
            val c00 = c000[channel] * (1 - rFrac) + c100[channel] * rFrac
            val c01 = c001[channel] * (1 - rFrac) + c101[channel] * rFrac
            val c10 = c010[channel] * (1 - rFrac) + c110[channel] * rFrac
            val c11 = c011[channel] * (1 - rFrac) + c111[channel] * rFrac
            
            // 沿 G 轴插值
            val c0 = c00 * (1 - gFrac) + c10 * gFrac
            val c1 = c01 * (1 - gFrac) + c11 * gFrac
            
            // 沿 B 轴插值（最外层）
            result[channel] = c0 * (1 - bFrac) + c1 * bFrac
        }
        
        return result
    }

    /**
     * 设置 LUT 强度
     */
    fun setLutIntensity(intensity: Float) {
        _lutIntensity.value = intensity.coerceIn(0f, 1f)
    }

    /**
     * 从 Uri 导入 LUT（用于 SAF 文件选择器）
     */
    suspend fun importLutFromUri(uri: Uri, displayName: String? = null): LutFilter? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null

            // 确定文件名
            val fileName = displayName ?: uri.lastPathSegment ?: "imported_lut.cube"
            val destFile = File(userLutDir, fileName)

            // 复制到本地
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            // 解析并导入
            importLut(destFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to import LUT from URI: $uri")
            null
        }
    }

    /**
     * 获取当前 LUT 的有效强度
     */
    fun getCurrentEffectiveIntensity(): Float {
        return if (_currentLut.value != null) _lutIntensity.value else 0f
    }

    /**
     * 清除当前 LUT 选择
     */
    fun clearCurrentLut() {
        _currentLut.value = null
    }

    /**
     * 获取 LUT 预览（缩略图）
     */
    suspend fun getLutPreview(lutId: String, sampleImage: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val lutData = lutDataCache[lutId]
        if (lutData != null) {
            gpuLutRenderer.apply(sampleImage, lutId, 1.0f)
        } else {
            sampleImage
        }
    }

    /**
     * 导出 LUT 到外部存储
     */
    suspend fun exportLut(lutId: String, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val filter = _lutFilters.value.find { it.id == lutId } ?: return@withContext false

        try {
            val sourceFile = File(filter.filePath)
            if (!sourceFile.exists()) return@withContext false

            val contentResolver = context.contentResolver
            val outputStream = contentResolver.openOutputStream(destUri) ?: return@withContext false

            sourceFile.inputStream().use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to export LUT: $lutId")
            false
        }
    }
}
