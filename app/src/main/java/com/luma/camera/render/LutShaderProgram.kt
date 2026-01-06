package com.luma.camera.render

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * LUT 着色器程序
 *
 * 功能：
 * - 接收相机 OES 纹理输入
 * - 应用 3D LUT 颜色查找
 * - 支持 LUT 强度调节（0-100%）
 * - 三线性插值保证平滑过渡
 */
class LutShaderProgram @Inject constructor(
    @ApplicationContext context: Context
) : ShaderProgram(context) {

    // Uniform 位置
    private var uTextureLocation: Int = -1
    private var uLutTextureLocation: Int = -1
    private var uLutIntensityLocation: Int = -1
    private var uLutSizeLocation: Int = -1
    private var uTexMatrixLocation: Int = -1

    // Attribute 位置
    private var aPositionLocation: Int = -1
    private var aTexCoordLocation: Int = -1

    /** 当前 LUT 强度 (0.0 - 1.0) */
    var lutIntensity: Float = 1.0f

    /** 当前 LUT 尺寸 */
    var lutSize: Int = 33

    override fun getVertexShaderSource(): String = """
        #version 300 es
        
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        
        uniform mat4 uTexMatrix;
        
        out vec2 vTexCoord;
        
        void main() {
            gl_Position = aPosition;
            // 应用纹理变换矩阵（处理相机方向）
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    override fun getFragmentShaderSource(): String = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        
        precision highp float;
        precision highp sampler3D;
        
        in vec2 vTexCoord;
        
        uniform samplerExternalOES uTexture;      // 相机输入
        uniform sampler3D uLutTexture;            // 3D LUT
        uniform float uLutIntensity;              // LUT 强度 (0-1)
        uniform float uLutSize;                   // LUT 尺寸
        
        out vec4 fragColor;
        
        /**
         * 应用 3D LUT 颜色查找
         * 使用三线性插值确保平滑的颜色过渡
         */
        vec3 applyLut(vec3 color, sampler3D lut, float size) {
            // 计算 LUT 坐标（考虑半像素偏移以获得正确的采样）
            float scale = (size - 1.0) / size;
            float offset = 0.5 / size;
            vec3 lutCoord = color * scale + offset;
            
            // 三线性插值采样
            return texture(lut, lutCoord).rgb;
        }
        
        void main() {
            // 从相机纹理采样原始颜色
            vec4 originalColor = texture(uTexture, vTexCoord);
            
            // 应用 LUT
            vec3 lutColor = applyLut(originalColor.rgb, uLutTexture, uLutSize);
            
            // 根据强度混合原始颜色和 LUT 颜色
            vec3 finalColor = mix(originalColor.rgb, lutColor, uLutIntensity);
            
            fragColor = vec4(finalColor, originalColor.a);
        }
    """.trimIndent()

    override fun onProgramCreated() {
        // 获取 Uniform 位置
        uTextureLocation = getUniformLocation("uTexture")
        uLutTextureLocation = getUniformLocation("uLutTexture")
        uLutIntensityLocation = getUniformLocation("uLutIntensity")
        uLutSizeLocation = getUniformLocation("uLutSize")
        uTexMatrixLocation = getUniformLocation("uTexMatrix")

        // 获取 Attribute 位置
        aPositionLocation = getAttribLocation("aPosition")
        aTexCoordLocation = getAttribLocation("aTexCoord")
    }

    /**
     * 设置相机纹理到纹理单元 0
     */
    fun setCameraTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        setUniform1i(uTextureLocation, 0)
    }

    /**
     * 设置 LUT 纹理到纹理单元 1
     */
    fun setLutTexture(textureId: Int, size: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        setUniform1i(uLutTextureLocation, 1)
        setUniform1f(uLutSizeLocation, size.toFloat())
        lutSize = size
    }

    /**
     * 设置 LUT 强度
     */
    fun setIntensity(intensity: Float) {
        lutIntensity = intensity.coerceIn(0f, 1f)
        setUniform1f(uLutIntensityLocation, lutIntensity)
    }

    /**
     * 设置纹理变换矩阵
     */
    fun setTextureMatrix(matrix: FloatArray) {
        setUniformMatrix4fv(uTexMatrixLocation, matrix)
    }

    /**
     * 获取位置属性位置
     */
    fun getPositionAttributeLocation(): Int = aPositionLocation

    /**
     * 获取纹理坐标属性位置
     */
    fun getTexCoordAttributeLocation(): Int = aTexCoordLocation
}

/**
 * 直通着色器（无 LUT，仅显示相机画面）
 *
 * 用于 LUT 强度为 0 或未选择 LUT 时
 */
class PassthroughShaderProgram @Inject constructor(
    @ApplicationContext context: Context
) : ShaderProgram(context) {

    private var uTextureLocation: Int = -1
    private var uTexMatrixLocation: Int = -1
    private var aPositionLocation: Int = -1
    private var aTexCoordLocation: Int = -1

    override fun getVertexShaderSource(): String = """
        #version 300 es
        
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        
        uniform mat4 uTexMatrix;
        
        out vec2 vTexCoord;
        
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    override fun getFragmentShaderSource(): String = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        
        precision highp float;
        
        in vec2 vTexCoord;
        
        uniform samplerExternalOES uTexture;
        
        out vec4 fragColor;
        
        void main() {
            fragColor = texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    override fun onProgramCreated() {
        uTextureLocation = getUniformLocation("uTexture")
        uTexMatrixLocation = getUniformLocation("uTexMatrix")
        aPositionLocation = getAttribLocation("aPosition")
        aTexCoordLocation = getAttribLocation("aTexCoord")
    }

    fun setCameraTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        setUniform1i(uTextureLocation, 0)
    }

    fun setTextureMatrix(matrix: FloatArray) {
        setUniformMatrix4fv(uTexMatrixLocation, matrix)
    }

    fun getPositionAttributeLocation(): Int = aPositionLocation
    fun getTexCoordAttributeLocation(): Int = aTexCoordLocation
}
