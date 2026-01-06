package com.luma.camera.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 纹理管理器
 *
 * 负责创建、管理和复用 OpenGL 纹理资源
 * 包括：
 * - OES 纹理（相机预览）
 * - 2D 纹理（普通图像）
 * - 3D 纹理（LUT）
 */
@Singleton
class TextureManager @Inject constructor() {

    /** 纹理池，按类型分类 */
    private val texturePool = mutableMapOf<TextureType, MutableList<Int>>()

    /** 已使用的纹理 */
    private val usedTextures = mutableSetOf<Int>()

    /** LUT 3D 纹理缓存 (LUT ID -> Texture ID) */
    private val lutTextureCache = mutableMapOf<String, Int>()

    /**
     * 创建 OES 纹理（用于相机预览）
     */
    fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        
        // 设置纹理参数
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        usedTextures.add(textureId)
        Timber.d("Created OES texture: $textureId")
        return textureId
    }

    /**
     * 创建 2D 纹理
     */
    fun create2DTexture(width: Int, height: Int, data: ByteBuffer? = null): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        // 设置纹理参数
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // 分配纹理存储
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            data
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        usedTextures.add(textureId)
        Timber.d("Created 2D texture: $textureId (${width}x${height})")
        return textureId
    }

    /**
     * 创建 3D LUT 纹理
     *
     * @param lutId LUT 的唯一标识
     * @param size LUT 尺寸（通常 17, 33, 或 65）
     * @param data LUT 数据（RGB 浮点值，范围 0-1，CUBE 格式）
     * 
     * CUBE 格式标准：R 变化最快，G 次之，B 最慢
     * 即按照 for B: for G: for R: 的顺序排列
     * 索引 = B * size² + G * size + R
     * 
     * OpenGL glTexImage3D：X 变化最快，Y 次之，Z 最慢
     * Shader 采样：texture(lut, vec3(r, g, b)) 其中 x=r, y=g, z=b
     * 
     * 因此 CUBE 数据可以直接上传，无需重排
     */
    fun createLutTexture(lutId: String, size: Int, data: FloatArray): Int {
        // 检查缓存
        lutTextureCache[lutId]?.let { cachedTexture ->
            if (usedTextures.contains(cachedTexture)) {
                Timber.d("Using cached LUT texture for $lutId: $cachedTexture")
                return cachedTexture
            }
        }

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)

        // 3D 纹理参数 - 使用三线性插值
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        // 直接使用 CUBE 数据，无需重排
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
        buffer.position(0)

        // 上传 3D 纹理数据
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB16F,  // 16-bit 浮点精度
            size,
            size,
            size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_FLOAT,
            buffer
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)

        usedTextures.add(textureId)
        lutTextureCache[lutId] = textureId
        Timber.d("Created 3D LUT texture for $lutId: $textureId (size: $size)")
        return textureId
    }

    /**
     * 获取已缓存的 LUT 纹理
     */
    fun getLutTexture(lutId: String): Int? {
        return lutTextureCache[lutId]
    }

    /**
     * 删除纹理
     */
    fun deleteTexture(textureId: Int) {
        if (textureId != 0 && usedTextures.contains(textureId)) {
            val textures = intArrayOf(textureId)
            GLES30.glDeleteTextures(1, textures, 0)
            usedTextures.remove(textureId)
            
            // 从 LUT 缓存中移除
            lutTextureCache.entries.removeIf { it.value == textureId }
            
            Timber.d("Deleted texture: $textureId")
        }
    }

    /**
     * 清除 LUT 纹理缓存
     */
    fun clearLutCache() {
        lutTextureCache.values.forEach { textureId ->
            deleteTexture(textureId)
        }
        lutTextureCache.clear()
        Timber.d("Cleared LUT texture cache")
    }

    /**
     * 释放所有纹理资源
     */
    fun releaseAll() {
        if (usedTextures.isNotEmpty()) {
            val textures = usedTextures.toIntArray()
            GLES30.glDeleteTextures(textures.size, textures, 0)
            Timber.d("Released ${textures.size} textures")
        }
        usedTextures.clear()
        lutTextureCache.clear()
        texturePool.clear()
    }

    /**
     * 创建全屏四边形顶点缓冲
     */
    fun createFullscreenQuadBuffer(): FloatBuffer {
        // 顶点坐标 (x, y) + 纹理坐标 (s, t)
        val vertices = floatArrayOf(
            // 位置 (x, y)      // 纹理坐标 (s, t)
            -1.0f, -1.0f,       0.0f, 0.0f,  // 左下
             1.0f, -1.0f,       1.0f, 0.0f,  // 右下
            -1.0f,  1.0f,       0.0f, 1.0f,  // 左上
             1.0f,  1.0f,       1.0f, 1.0f   // 右上
        )

        return ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }
    }

    /**
     * 创建相机预览用顶点缓冲（纹理坐标需要翻转）
     */
    fun createCameraQuadBuffer(isFrontCamera: Boolean = false): FloatBuffer {
        // 相机预览通常需要垂直翻转
        val vertices = if (isFrontCamera) {
            // 前置摄像头：水平 + 垂直翻转
            floatArrayOf(
                -1.0f, -1.0f,       1.0f, 1.0f,
                 1.0f, -1.0f,       0.0f, 1.0f,
                -1.0f,  1.0f,       1.0f, 0.0f,
                 1.0f,  1.0f,       0.0f, 0.0f
            )
        } else {
            // 后置摄像头：仅垂直翻转
            floatArrayOf(
                -1.0f, -1.0f,       0.0f, 1.0f,
                 1.0f, -1.0f,       1.0f, 1.0f,
                -1.0f,  1.0f,       0.0f, 0.0f,
                 1.0f,  1.0f,       1.0f, 0.0f
            )
        }

        return ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }
    }

    enum class TextureType {
        OES,
        TEXTURE_2D,
        TEXTURE_3D
    }

    companion object {
        const val COORDS_PER_VERTEX = 2
        const val TEX_COORDS_PER_VERTEX = 2
        const val STRIDE = (COORDS_PER_VERTEX + TEX_COORDS_PER_VERTEX) * 4 // 4 bytes per float
    }
}
