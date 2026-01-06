package com.luma.camera.lut

import android.content.Context
import com.luma.camera.domain.model.LutFilter
import com.luma.camera.domain.model.LutSize
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LUT 解析器
 *
 * 支持格式：
 * - .cube (Adobe 标准格式)
 * - .3dl (Autodesk 格式)
 *
 * 支持尺寸：17³, 33³, 65³
 */
@Singleton
class LutParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 解析后的 LUT 数据
     */
    data class LutData(
        val title: String,
        val size: Int,
        val data: FloatArray  // RGB 三通道，大小为 size^3 * 3
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as LutData
            return title == other.title && size == other.size && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = title.hashCode()
            result = 31 * result + size
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * 从文件解析 LUT
     */
    fun parseFromFile(file: File): LutData {
        val extension = file.extension.lowercase()
        return when (extension) {
            "cube" -> parseCubeFile(file.inputStream(), file.nameWithoutExtension)
            "3dl" -> parse3dlFile(file.inputStream(), file.nameWithoutExtension)
            else -> throw IllegalArgumentException("Unsupported LUT format: $extension")
        }
    }

    /**
     * 从 raw 资源解析 LUT
     */
    fun parseFromResource(resourceId: Int, name: String): LutData {
        val inputStream = context.resources.openRawResource(resourceId)
        // 根据资源名称推断格式（假设都是 .cube）
        return parseCubeFile(inputStream, name)
    }

    /**
     * 从 InputStream 解析 .cube 格式
     */
    fun parseCubeStream(inputStream: InputStream, defaultName: String): LutData {
        return parseCubeFile(inputStream, defaultName)
    }

    /**
     * 从 InputStream 解析 .3dl 格式
     */
    fun parse3dlStream(inputStream: InputStream, defaultName: String): LutData {
        return parse3dlFile(inputStream, defaultName)
    }

    /**
     * 解析 .cube 格式
     *
     * 格式说明：
     * - TITLE "LUT Name"
     * - LUT_3D_SIZE 33
     * - DOMAIN_MIN 0.0 0.0 0.0
     * - DOMAIN_MAX 1.0 1.0 1.0
     * - 后面是 size^3 行的 RGB 值 (0.0-1.0)
     */
    private fun parseCubeFile(inputStream: InputStream, defaultName: String): LutData {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var title = defaultName
        var size = 33
        val values = mutableListOf<Float>()

        reader.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                
                when {
                    // 跳过空行和注释
                    trimmed.isEmpty() || trimmed.startsWith("#") -> continue
                    
                    // 解析标题
                    trimmed.startsWith("TITLE") -> {
                        title = trimmed.substringAfter("TITLE")
                            .trim()
                            .removeSurrounding("\"")
                    }
                    
                    // 解析 LUT 尺寸
                    trimmed.startsWith("LUT_3D_SIZE") -> {
                        size = trimmed.substringAfter("LUT_3D_SIZE").trim().toInt()
                    }
                    
                    // 跳过其他元数据
                    trimmed.startsWith("DOMAIN_MIN") -> continue
                    trimmed.startsWith("DOMAIN_MAX") -> continue
                    trimmed.startsWith("LUT_1D_SIZE") -> {
                        throw IllegalArgumentException("1D LUT not supported")
                    }
                    
                    // 解析 RGB 值
                    else -> {
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            values.add(parts[0].toFloat())
                            values.add(parts[1].toFloat())
                            values.add(parts[2].toFloat())
                        }
                    }
                }
            }
        }

        return LutData(
            title = title,
            size = size,
            data = values.toFloatArray()
        )
    }

    /**
     * 解析 .3dl 格式
     *
     * Autodesk 格式，数值范围通常是 0-1023 或 0-4095
     */
    private fun parse3dlFile(inputStream: InputStream, defaultName: String): LutData {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val values = mutableListOf<Float>()
        var size = 17
        var maxValue = 1023f  // 默认 10-bit

        reader.useLines { lines ->
            var lineNumber = 0
            for (line in lines) {
                val trimmed = line.trim()
                lineNumber++
                
                // 跳过空行
                if (trimmed.isEmpty()) continue
                
                // 第一行可能是尺寸信息
                if (lineNumber == 1 && trimmed.all { it.isDigit() || it.isWhitespace() }) {
                    val firstLineValues = trimmed.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    if (firstLineValues.size == 1) {
                        size = firstLineValues[0]
                        continue
                    } else if (firstLineValues.size >= 3) {
                        // 这是数据行
                        maxValue = firstLineValues.maxOrNull()?.toFloat() ?: 1023f
                        if (maxValue > 1f) {
                            maxValue = if (maxValue > 4000) 4095f else 1023f
                        }
                        values.addAll(firstLineValues.take(3).map { it / maxValue })
                        continue
                    }
                }
                
                // 解析 RGB 值
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val rgb = parts.take(3).mapNotNull { it.toFloatOrNull() }
                    if (rgb.size == 3) {
                        // 归一化到 0-1
                        val factor = if (rgb.any { it > 1f }) 1f / maxValue else 1f
                        values.addAll(rgb.map { it * factor })
                    }
                }
            }
        }

        // 推断尺寸
        val totalEntries = values.size / 3
        size = kotlin.math.round(kotlin.math.cbrt(totalEntries.toDouble())).toInt()

        return LutData(
            title = defaultName,
            size = size,
            data = values.toFloatArray()
        )
    }

    /**
     * 验证 LUT 数据
     */
    fun validate(lutData: LutData): Boolean {
        val expectedSize = lutData.size * lutData.size * lutData.size * 3
        return lutData.data.size == expectedSize
    }

    /**
     * 获取 LUT 尺寸枚举
     */
    fun getLutSize(size: Int): LutSize {
        return when (size) {
            17 -> LutSize.SIZE_17
            33 -> LutSize.SIZE_33
            65 -> LutSize.SIZE_65
            else -> LutSize.SIZE_33
        }
    }
}
