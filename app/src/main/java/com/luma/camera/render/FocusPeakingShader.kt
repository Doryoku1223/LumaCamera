package com.luma.camera.render

import android.content.Context
import android.opengl.GLES30
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 峰值对焦着色器
 *
 * 功能：
 * - 使用 Sobel 算子检测边缘
 * - 在对焦清晰区域叠加高亮颜色
 * - 可配置边缘检测阈值和高亮颜色
 *
 * 用于帮助用户判断对焦是否准确
 */
class FocusPeakingShader @Inject constructor(
    @ApplicationContext context: Context
) : ShaderProgram(context) {

    // Uniform 位置
    private var uTextureLocation: Int = -1
    private var uTextureSizeLocation: Int = -1
    private var uThresholdLocation: Int = -1
    private var uPeakingColorLocation: Int = -1
    private var uEnabledLocation: Int = -1

    // Attribute 位置
    private var aPositionLocation: Int = -1
    private var aTexCoordLocation: Int = -1

    /** 边缘检测阈值 (0.0 - 1.0) */
    private var _threshold: Float = 0.15f
    val threshold: Float get() = _threshold

    /** 峰值高亮颜色 */
    private var _peakingColor: PeakingColor = PeakingColor.RED
    val peakingColor: PeakingColor get() = _peakingColor

    /** 是否启用峰值对焦 */
    private var _isEnabled: Boolean = false
    val isEnabled: Boolean get() = _isEnabled

    override fun getVertexShaderSource(): String = """
        #version 300 es
        
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        
        out vec2 vTexCoord;
        
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    override fun getFragmentShaderSource(): String = """
        #version 300 es
        
        precision highp float;
        
        in vec2 vTexCoord;
        
        uniform sampler2D uTexture;
        uniform vec2 uTextureSize;
        uniform float uThreshold;
        uniform vec3 uPeakingColor;
        uniform int uEnabled;
        
        out vec4 fragColor;
        
        /**
         * 将 RGB 转换为亮度值
         */
        float luminance(vec3 color) {
            return dot(color, vec3(0.299, 0.587, 0.114));
        }
        
        /**
         * Sobel 边缘检测
         * 计算图像梯度强度
         */
        float sobelEdge(sampler2D tex, vec2 coord, vec2 texelSize) {
            // Sobel 卷积核
            // Gx:           Gy:
            // -1  0  1      -1 -2 -1
            // -2  0  2       0  0  0
            // -1  0  1       1  2  1
            
            float tl = luminance(texture(tex, coord + vec2(-texelSize.x, -texelSize.y)).rgb);
            float t  = luminance(texture(tex, coord + vec2(0.0, -texelSize.y)).rgb);
            float tr = luminance(texture(tex, coord + vec2(texelSize.x, -texelSize.y)).rgb);
            
            float l  = luminance(texture(tex, coord + vec2(-texelSize.x, 0.0)).rgb);
            float r  = luminance(texture(tex, coord + vec2(texelSize.x, 0.0)).rgb);
            
            float bl = luminance(texture(tex, coord + vec2(-texelSize.x, texelSize.y)).rgb);
            float b  = luminance(texture(tex, coord + vec2(0.0, texelSize.y)).rgb);
            float br = luminance(texture(tex, coord + vec2(texelSize.x, texelSize.y)).rgb);
            
            // 计算梯度
            float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
            float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
            
            // 梯度幅值
            return sqrt(gx * gx + gy * gy);
        }
        
        void main() {
            vec4 originalColor = texture(uTexture, vTexCoord);
            
            if (uEnabled == 0) {
                fragColor = originalColor;
                return;
            }
            
            // 计算纹素大小
            vec2 texelSize = 1.0 / uTextureSize;
            
            // Sobel 边缘检测
            float edge = sobelEdge(uTexture, vTexCoord, texelSize);
            
            // 应用阈值
            float peakingMask = smoothstep(uThreshold, uThreshold + 0.1, edge);
            
            // 混合原始颜色和峰值颜色
            vec3 finalColor = mix(originalColor.rgb, uPeakingColor, peakingMask * 0.8);
            
            fragColor = vec4(finalColor, originalColor.a);
        }
    """.trimIndent()

    override fun onProgramCreated() {
        uTextureLocation = getUniformLocation("uTexture")
        uTextureSizeLocation = getUniformLocation("uTextureSize")
        uThresholdLocation = getUniformLocation("uThreshold")
        uPeakingColorLocation = getUniformLocation("uPeakingColor")
        uEnabledLocation = getUniformLocation("uEnabled")

        aPositionLocation = getAttribLocation("aPosition")
        aTexCoordLocation = getAttribLocation("aTexCoord")
    }

    /**
     * 设置输入纹理
     */
    fun setTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        setUniform1i(uTextureLocation, 0)
    }

    /**
     * 设置纹理尺寸（用于计算纹素大小）
     */
    fun setTextureSize(width: Int, height: Int) {
        setUniform2f(uTextureSizeLocation, width.toFloat(), height.toFloat())
    }

    /**
     * 设置边缘检测阈值
     */
    fun setThreshold(value: Float) {
        _threshold = value.coerceIn(0f, 1f)
        setUniform1f(uThresholdLocation, _threshold)
    }

    /**
     * 设置峰值高亮颜色
     */
    fun setPeakingColor(color: PeakingColor) {
        _peakingColor = color
        setUniform3f(uPeakingColorLocation, color.r, color.g, color.b)
    }

    /**
     * 设置是否启用
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        setUniform1i(uEnabledLocation, if (enabled) 1 else 0)
    }

    fun getPositionAttributeLocation(): Int = aPositionLocation
    fun getTexCoordAttributeLocation(): Int = aTexCoordLocation

    /**
     * 峰值对焦高亮颜色选项
     */
    enum class PeakingColor(val r: Float, val g: Float, val b: Float) {
        RED(1.0f, 0.0f, 0.0f),
        YELLOW(1.0f, 1.0f, 0.0f),
        BLUE(0.0f, 0.5f, 1.0f),
        WHITE(1.0f, 1.0f, 1.0f),
        GREEN(0.0f, 1.0f, 0.0f)
    }
}
