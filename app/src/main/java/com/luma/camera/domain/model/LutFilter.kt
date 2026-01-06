package com.luma.camera.domain.model

/**
 * LUT 滤镜模型
 */
data class LutFilter(
    val id: String,
    val name: String,
    val filePath: String,
    val category: LutCategory = LutCategory.BUILTIN,
    val isBuiltIn: Boolean = true,
    val thumbnailPath: String? = null,
    val sortOrder: Int = 0,
    val size: LutSize = LutSize.SIZE_33
)

/**
 * LUT 分类
 */
enum class LutCategory {
    BUILTIN,    // 内置
    FILM,       // 胶片
    CINEMATIC,  // 电影感
    PORTRAIT,   // 人像
    LANDSCAPE,  // 风景
    USER        // 用户导入
}

/**
 * LUT 尺寸
 */
enum class LutSize(val dimension: Int) {
    SIZE_17(17),
    SIZE_33(33),
    SIZE_65(65)
}

/**
 * LUT 文件格式
 */
enum class LutFormat {
    CUBE,  // Adobe .cube
    DL3    // Autodesk .3dl
}
