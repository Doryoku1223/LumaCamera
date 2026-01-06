package com.luma.camera.lut

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPU LUT 渲染器
 *
 * 使用 OpenGL ES 3.0 实现：
 * - 3D 纹理存储 LUT 数据
 * - 片段着色器实现颜色映射
 * - 支持强度混合 (0-100%)
 * - 120fps 实时渲染
 * - Shader 预编译
 *
 * 性能目标：LUT 切换即时生效 (< 16ms)
 */
@Singleton
class GpuLutRenderer @Inject constructor() {

    companion object {
        // 顶点着色器
        private const val VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 片段着色器 - LUT 应用
        private const val FRAGMENT_SHADER_LUT = """
            #version 300 es
            precision highp float;
            precision highp sampler3D;
            
            in vec2 vTexCoord;
            out vec4 fragColor;
            
            uniform sampler2D uInputTexture;
            uniform sampler3D uLutTexture;
            uniform float uIntensity;
            uniform float uLutSize;
            
            void main() {
                // 采样原始图像
                vec4 originalColor = texture(uInputTexture, vTexCoord);
                
                // 计算 LUT 采样坐标
                // 需要考虑半像素偏移以避免边缘插值问题
                float offset = 0.5 / uLutSize;
                float scale = (uLutSize - 1.0) / uLutSize;
                vec3 lutCoord = offset + originalColor.rgb * scale;
                
                // 从 3D LUT 纹理采样
                vec4 lutColor = texture(uLutTexture, lutCoord);
                
                // 根据强度混合
                vec3 finalColor = mix(originalColor.rgb, lutColor.rgb, uIntensity);
                
                fragColor = vec4(finalColor, originalColor.a);
            }
        """
    }

    // OpenGL 资源
    private var program: Int = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    // LUT 3D 纹理缓存
    private val lutTextures = mutableMapOf<String, Int>()

    // 输入/输出纹理
    private var inputTexture: Int = 0
    private var outputFramebuffer: Int = 0
    private var outputTexture: Int = 0

    // 是否已初始化
    private var isInitialized = false

    /**
     * 初始化 OpenGL 资源
     *
     * 注意：必须在 OpenGL 线程中调用
     */
    fun initialize() {
        if (isInitialized) return

        // 编译着色器程序
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_LUT)

        // 创建顶点缓冲
        val vertices = floatArrayOf(
            -1f, -1f,  // 左下
             1f, -1f,  // 右下
            -1f,  1f,  // 左上
             1f,  1f   // 右上
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        // 纹理坐标
        val texCoords = floatArrayOf(
            0f, 1f,  // 左下
            1f, 1f,  // 右下
            0f, 0f,  // 左上
            1f, 0f   // 右上
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoords)
                position(0)
            }

        // 创建输入纹理
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        inputTexture = textures[0]

        isInitialized = true
    }

    /**
     * 上传 LUT 到 GPU (3D 纹理)
     */
    fun uploadLut(lutId: String, lutData: LutParser.LutData) {
        // 删除旧纹理
        lutTextures[lutId]?.let { textureId ->
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }

        // 创建 3D 纹理
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)

        // 设置纹理参数
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        // 转换数据为 RGBA 格式
        val size = lutData.size
        val rgbaData = ByteBuffer.allocateDirect(size * size * size * 4)
            .order(ByteOrder.nativeOrder())

        val data = lutData.data
        for (i in 0 until size * size * size) {
            val r = (data[i * 3 + 0] * 255).toInt().coerceIn(0, 255).toByte()
            val g = (data[i * 3 + 1] * 255).toInt().coerceIn(0, 255).toByte()
            val b = (data[i * 3 + 2] * 255).toInt().coerceIn(0, 255).toByte()
            rgbaData.put(r)
            rgbaData.put(g)
            rgbaData.put(b)
            rgbaData.put(255.toByte())  // Alpha
        }
        rgbaData.position(0)

        // 上传到 GPU
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA,
            size, size, size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            rgbaData
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)

        lutTextures[lutId] = textureId
    }

    /**
     * 移除 LUT
     */
    fun removeLut(lutId: String) {
        lutTextures.remove(lutId)?.let { textureId ->
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
    
    /**
     * 获取 LUT 纹理 ID
     */
    fun getLutTextureId(lutId: String): Int? {
        return lutTextures[lutId]
    }

    /**
     * 应用 LUT 到图像
     */
    fun apply(input: Bitmap, lutId: String, intensity: Float): Bitmap {
        val lutTextureId = lutTextures[lutId] ?: return input

        // 简化实现：使用 CPU 进行 LUT 映射
        // 实际应该在 OpenGL 线程中使用 GPU 渲染
        return applyCpu(input, lutId, intensity)
    }

    /**
     * CPU 实现的 LUT 应用（后备方案）
     */
    private fun applyCpu(input: Bitmap, lutId: String, intensity: Float): Bitmap {
        // TODO: 实现 CPU LUT 应用
        // 对于每个像素：
        // 1. 获取 RGB 值
        // 2. 查找 LUT 中对应的颜色
        // 3. 与原色混合

        return input.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 创建着色器程序
     */
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Shader program link failed: $error")
        }

        // 删除着色器（已经链接到程序中）
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    /**
     * 编译着色器
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        // 检查编译状态
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $error")
        }

        return shader
    }

    /**
     * 释放资源
     */
    fun release() {
        // 删除所有 LUT 纹理
        for ((_, textureId) in lutTextures) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
        lutTextures.clear()

        // 删除着色器程序
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }

        // 删除输入纹理
        if (inputTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
            inputTexture = 0
        }

        isInitialized = false
    }
}
