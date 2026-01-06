package com.luma.camera.render

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.luma.camera.domain.model.ColorPalette
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.pow

/**
 * 专业调色盘着色器程序 (Professional Color Grading Shader)
 * 
 * 处理管线（在线性空间）：
 * 1. sRGB → Linear 转换
 * 2. 白平衡 (Temperature): 使用 Mired 插值计算目标色温的 RGB 乘数
 * 3. 曝光 (Exposure): gain = 2^EV，在线性空间进行增益
 * 4. 饱和度 (Saturation): OKLab 色彩空间调整，带高光保护
 * 5. Linear → sRGB 转换
 * 
 * 参数范围（UI 值 -100 ~ +100）：
 * - tempUI: 色温 (K_WARM=2500K ~ K_COOL=9000K，中性白 K0=5500K)
 * - expUI: 曝光 (±EV_MAX = ±2.0 EV)
 * - satUI: 饱和度 (±SAT_MAX = ±0.35)
 * 
 * 可与 LUT 着色器级联使用：Camera → ColorPalette → LUT → Output
 */
class ColorPaletteShaderProgram @Inject constructor(
    @ApplicationContext context: Context
) : ShaderProgram(context) {

    // Uniform 位置
    private var uTextureLocation: Int = -1
    private var uTexMatrixLocation: Int = -1
    private var uTemperatureRgbLocation: Int = -1
    private var uExposureGainLocation: Int = -1
    private var uSaturationAdjustLocation: Int = -1
    private var uEnabledLocation: Int = -1

    // Attribute 位置
    private var aPositionLocation: Int = -1
    private var aTexCoordLocation: Int = -1

    /** 是否启用调色效果 */
    private var _isEnabled: Boolean = false
    val isEnabled: Boolean get() = _isEnabled
    
    /** 色温 RGB 乘数 */
    private var temperatureRgb = floatArrayOf(1f, 1f, 1f)
    
    /** 曝光增益 (线性空间) */
    private var exposureGain = 1f
    
    /** 饱和度调整值 (-0.35 ~ +0.35) */
    private var saturationAdjust = 0f

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
        uniform vec3 uTemperatureRgb;     // 色温 RGB 乘数
        uniform float uExposureGain;       // 曝光增益 (线性空间, 2^EV)
        uniform float uSaturationAdjust;   // 饱和度调整 (-0.35 ~ +0.35)
        uniform bool uEnabled;             // 是否启用
        
        out vec4 fragColor;
        
        // ==================== 色彩空间转换 ====================
        
        // sRGB 转 Linear (精确版)
        float srgbToLinear(float c) {
            return c <= 0.04045 
                ? c / 12.92 
                : pow((c + 0.055) / 1.055, 2.4);
        }
        
        vec3 srgbToLinear(vec3 srgb) {
            return vec3(
                srgbToLinear(srgb.r),
                srgbToLinear(srgb.g),
                srgbToLinear(srgb.b)
            );
        }
        
        // Linear 转 sRGB (精确版)
        float linearToSrgb(float c) {
            return c <= 0.0031308 
                ? c * 12.92 
                : 1.055 * pow(c, 1.0/2.4) - 0.055;
        }
        
        vec3 linearToSrgb(vec3 linear) {
            return vec3(
                linearToSrgb(linear.r),
                linearToSrgb(linear.g),
                linearToSrgb(linear.b)
            );
        }
        
        // ==================== OKLab 色彩空间 ====================
        // OKLab 是一种感知均匀的色彩空间，适合调整饱和度
        // 参考: https://bottosson.github.io/posts/oklab/
        
        // Linear RGB → OKLab
        vec3 linearRgbToOklab(vec3 rgb) {
            float l = 0.4122214708 * rgb.r + 0.5363325363 * rgb.g + 0.0514459929 * rgb.b;
            float m = 0.2119034982 * rgb.r + 0.6806995451 * rgb.g + 0.1073969566 * rgb.b;
            float s = 0.0883024619 * rgb.r + 0.2817188376 * rgb.g + 0.6299787005 * rgb.b;
            
            l = pow(max(l, 0.0), 1.0/3.0);
            m = pow(max(m, 0.0), 1.0/3.0);
            s = pow(max(s, 0.0), 1.0/3.0);
            
            return vec3(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            );
        }
        
        // OKLab → Linear RGB
        vec3 oklabToLinearRgb(vec3 lab) {
            float L = lab.x;
            float a = lab.y;
            float b = lab.z;
            
            float l = L + 0.3963377774 * a + 0.2158037573 * b;
            float m = L - 0.1055613458 * a - 0.0638541728 * b;
            float s = L - 0.0894841775 * a - 1.2914855480 * b;
            
            l = l * l * l;
            m = m * m * m;
            s = s * s * s;
            
            return vec3(
                 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
            );
        }
        
        // ==================== 饱和度调整（带高光保护）====================
        
        vec3 adjustSaturationOklab(vec3 linear, float satAdjust) {
            if (abs(satAdjust) < 0.001) return linear;
            
            // 转换到 OKLab
            vec3 lab = linearRgbToOklab(linear);
            
            // 计算色度 (chroma)
            float chroma = sqrt(lab.y * lab.y + lab.z * lab.z);
            
            // 高光保护：亮度越高，饱和度调整越小
            // 这可以防止高光区域出现爆色
            float luminance = lab.x;
            float highlightProtection = 1.0 - smoothstep(0.7, 1.0, luminance);
            
            // 暗部保护：亮度越低，减饱和效果越小
            float shadowProtection = smoothstep(0.0, 0.2, luminance);
            
            // 综合保护因子
            float protection = mix(shadowProtection, highlightProtection, step(0.0, satAdjust));
            
            // 应用饱和度调整
            float satFactor;
            if (satAdjust > 0.0) {
                // 增加饱和度：使用 S 曲线防止过饱和
                satFactor = 1.0 + satAdjust * protection * 3.0;
            } else {
                // 减少饱和度：线性减少
                satFactor = 1.0 + satAdjust * protection * 2.8;
            }
            
            satFactor = max(satFactor, 0.0);
            
            // 调整色度（保持色相不变）
            if (chroma > 0.001) {
                lab.y *= satFactor;
                lab.z *= satFactor;
            }
            
            // 转换回 Linear RGB
            vec3 result = oklabToLinearRgb(lab);
            
            // 软裁剪：使用 tanh 进行软限制，避免硬裁剪导致的颜色失真
            // 对于过饱和的颜色，将其压缩回有效范围
            float maxChannel = max(max(result.r, result.g), result.b);
            if (maxChannel > 1.0) {
                // 使用色彩压缩而非简单裁剪
                float compressionFactor = 1.0 / maxChannel;
                float blendFactor = smoothstep(1.0, 2.0, maxChannel);
                result = mix(result, result * compressionFactor, blendFactor);
            }
            
            return max(result, vec3(0.0));
        }
        
        // ==================== 主处理流程 ====================
        
        void main() {
            vec4 color = texture(uTexture, vTexCoord);
            
            if (!uEnabled) {
                fragColor = color;
                return;
            }
            
            // 1. sRGB → Linear
            vec3 linear = srgbToLinear(color.rgb);
            
            // 2. 白平衡（色温）
            linear = linear * uTemperatureRgb;
            
            // 3. 曝光增益（在线性空间）
            linear = linear * uExposureGain;
            
            // 4. 饱和度调整（OKLab + 高光保护）
            linear = adjustSaturationOklab(linear, uSaturationAdjust);
            
            // 5. Linear → sRGB
            vec3 srgb = linearToSrgb(linear);
            
            // 最终裁剪到 [0, 1]
            srgb = clamp(srgb, 0.0, 1.0);
            
            fragColor = vec4(srgb, color.a);
        }
    """.trimIndent()

    override fun onProgramCreated() {
        // 获取 Uniform 位置
        uTextureLocation = getUniformLocation("uTexture")
        uTexMatrixLocation = getUniformLocation("uTexMatrix")
        uTemperatureRgbLocation = getUniformLocation("uTemperatureRgb")
        uExposureGainLocation = getUniformLocation("uExposureGain")
        uSaturationAdjustLocation = getUniformLocation("uSaturationAdjust")
        uEnabledLocation = getUniformLocation("uEnabled")

        // 获取 Attribute 位置
        aPositionLocation = getAttribLocation("aPosition")
        aTexCoordLocation = getAttribLocation("aTexCoord")
    }

    /**
     * 设置相机纹理
     */
    fun setCameraTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        setUniform1i(uTextureLocation, 0)
    }
    
    /**
     * 设置 2D 纹理（用于多 Pass 渲染）
     */
    fun set2DTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        setUniform1i(uTextureLocation, 0)
    }

    /**
     * 设置纹理变换矩阵
     */
    fun setTextureMatrix(matrix: FloatArray) {
        setUniformMatrix4fv(uTexMatrixLocation, matrix)
    }

    /**
     * 设置色温 RGB 乘数
     */
    fun setTemperature(rgbMultipliers: FloatArray) {
        temperatureRgb = rgbMultipliers
        GLES30.glUniform3fv(uTemperatureRgbLocation, 1, temperatureRgb, 0)
    }

    /**
     * 设置曝光增益
     * @param gain 线性增益值 (2^EV，范围约 0.25 ~ 4.0)
     */
    fun setExposureGain(gain: Float) {
        exposureGain = gain.coerceIn(0.1f, 10f)
        setUniform1f(uExposureGainLocation, exposureGain)
    }

    /**
     * 设置饱和度调整值
     * @param adjust 饱和度调整值 (-0.35 ~ +0.35)
     */
    fun setSaturationAdjust(adjust: Float) {
        saturationAdjust = adjust.coerceIn(-0.5f, 0.5f)
        setUniform1f(uSaturationAdjustLocation, saturationAdjust)
    }

    /**
     * 设置是否启用
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        GLES30.glUniform1i(uEnabledLocation, if (enabled) 1 else 0)
    }

    /**
     * 应用所有参数（新 API）
     * @param palette ColorPalette 参数对象
     * @param enabled 是否启用
     */
    fun applyParams(palette: ColorPalette, enabled: Boolean) {
        setTemperature(palette.getTemperatureMultipliers())
        setExposureGain(palette.exposureGain)
        setSaturationAdjust(palette.saturationAdjust)
        setEnabled(enabled)
    }

    /**
     * 应用所有参数（兼容旧 API）
     * @param rgbMultipliers 色温 RGB 乘数
     * @param saturation 饱和度系数（旧 API: 0.65~1.35，会转换为调整值）
     * @param tone 光影系数（旧 API: -1~1，会转换为曝光增益）
     * @param enabled 是否启用
     */
    fun applyParams(rgbMultipliers: FloatArray, saturation: Float, tone: Float, enabled: Boolean) {
        setTemperature(rgbMultipliers)
        // 兼容旧 API：将 saturation (0.65~1.35) 转换为 adjust (-0.35~+0.35)
        setSaturationAdjust(saturation - 1f)
        // 兼容旧 API：将 tone (-1~1) 转换为曝光增益
        // tone=0 → gain=1, tone=1 → gain=2, tone=-1 → gain=0.5
        val ev = tone * ColorPalette.EV_MAX
        setExposureGain(2.0.pow(ev.toDouble()).toFloat())
        setEnabled(enabled)
    }

    // 兼容旧 API
    fun setSaturation(factor: Float) {
        setSaturationAdjust(factor - 1f)
    }
    
    fun setTone(factor: Float) {
        val ev = factor * ColorPalette.EV_MAX
        setExposureGain(2.0.pow(ev.toDouble()).toFloat())
    }

    fun getPositionAttributeLocation(): Int = aPositionLocation
    fun getTexCoordAttributeLocation(): Int = aTexCoordLocation
}

/**
 * 支持 2D 纹理输入的专业调色盘着色器
 * 
 * 用于 LUT → ColorPalette 的链式处理
 * 处理管线与 ColorPaletteShaderProgram 完全一致
 */
class ColorPalette2DShaderProgram @Inject constructor(
    @ApplicationContext context: Context
) : ShaderProgram(context) {

    private var uTextureLocation: Int = -1
    private var uTexMatrixLocation: Int = -1
    private var uTemperatureRgbLocation: Int = -1
    private var uExposureGainLocation: Int = -1
    private var uSaturationAdjustLocation: Int = -1
    private var uEnabledLocation: Int = -1

    private var aPositionLocation: Int = -1
    private var aTexCoordLocation: Int = -1

    private var _isEnabled: Boolean = false
    val isEnabled: Boolean get() = _isEnabled
    private var temperatureRgb = floatArrayOf(1f, 1f, 1f)
    private var exposureGain = 1f
    private var saturationAdjust = 0f

    override fun getVertexShaderSource(): String = """
        #version 300 es
        
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        
        uniform mat4 uTexMatrix;
        
        out vec2 vTexCoord;
        
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord; // 2D 纹理不需要变换矩阵
        }
    """.trimIndent()

    override fun getFragmentShaderSource(): String = """
        #version 300 es
        
        precision highp float;
        
        in vec2 vTexCoord;
        
        uniform sampler2D uTexture;
        uniform vec3 uTemperatureRgb;      // 色温 RGB 乘数
        uniform float uExposureGain;        // 曝光增益 (线性空间, 2^EV)
        uniform float uSaturationAdjust;    // 饱和度调整 (-0.35 ~ +0.35)
        uniform bool uEnabled;              // 是否启用
        
        out vec4 fragColor;
        
        // ==================== 色彩空间转换 ====================
        
        float srgbToLinear(float c) {
            return c <= 0.04045 
                ? c / 12.92 
                : pow((c + 0.055) / 1.055, 2.4);
        }
        
        vec3 srgbToLinear(vec3 srgb) {
            return vec3(
                srgbToLinear(srgb.r),
                srgbToLinear(srgb.g),
                srgbToLinear(srgb.b)
            );
        }
        
        float linearToSrgb(float c) {
            return c <= 0.0031308 
                ? c * 12.92 
                : 1.055 * pow(c, 1.0/2.4) - 0.055;
        }
        
        vec3 linearToSrgb(vec3 linear) {
            return vec3(
                linearToSrgb(linear.r),
                linearToSrgb(linear.g),
                linearToSrgb(linear.b)
            );
        }
        
        // ==================== OKLab 色彩空间 ====================
        
        vec3 linearRgbToOklab(vec3 rgb) {
            float l = 0.4122214708 * rgb.r + 0.5363325363 * rgb.g + 0.0514459929 * rgb.b;
            float m = 0.2119034982 * rgb.r + 0.6806995451 * rgb.g + 0.1073969566 * rgb.b;
            float s = 0.0883024619 * rgb.r + 0.2817188376 * rgb.g + 0.6299787005 * rgb.b;
            
            l = pow(max(l, 0.0), 1.0/3.0);
            m = pow(max(m, 0.0), 1.0/3.0);
            s = pow(max(s, 0.0), 1.0/3.0);
            
            return vec3(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            );
        }
        
        vec3 oklabToLinearRgb(vec3 lab) {
            float L = lab.x;
            float a = lab.y;
            float b = lab.z;
            
            float l = L + 0.3963377774 * a + 0.2158037573 * b;
            float m = L - 0.1055613458 * a - 0.0638541728 * b;
            float s = L - 0.0894841775 * a - 1.2914855480 * b;
            
            l = l * l * l;
            m = m * m * m;
            s = s * s * s;
            
            return vec3(
                 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
            );
        }
        
        // ==================== 饱和度调整（带高光保护）====================
        
        vec3 adjustSaturationOklab(vec3 linear, float satAdjust) {
            if (abs(satAdjust) < 0.001) return linear;
            
            vec3 lab = linearRgbToOklab(linear);
            float chroma = sqrt(lab.y * lab.y + lab.z * lab.z);
            
            float luminance = lab.x;
            float highlightProtection = 1.0 - smoothstep(0.7, 1.0, luminance);
            float shadowProtection = smoothstep(0.0, 0.2, luminance);
            float protection = mix(shadowProtection, highlightProtection, step(0.0, satAdjust));
            
            float satFactor;
            if (satAdjust > 0.0) {
                satFactor = 1.0 + satAdjust * protection * 3.0;
            } else {
                satFactor = 1.0 + satAdjust * protection * 2.8;
            }
            
            satFactor = max(satFactor, 0.0);
            
            if (chroma > 0.001) {
                lab.y *= satFactor;
                lab.z *= satFactor;
            }
            
            vec3 result = oklabToLinearRgb(lab);
            
            float maxChannel = max(max(result.r, result.g), result.b);
            if (maxChannel > 1.0) {
                float compressionFactor = 1.0 / maxChannel;
                float blendFactor = smoothstep(1.0, 2.0, maxChannel);
                result = mix(result, result * compressionFactor, blendFactor);
            }
            
            return max(result, vec3(0.0));
        }
        
        // ==================== 主处理流程 ====================
        
        void main() {
            vec4 color = texture(uTexture, vTexCoord);
            
            if (!uEnabled) {
                fragColor = color;
                return;
            }
            
            // 1. sRGB → Linear
            vec3 linear = srgbToLinear(color.rgb);
            
            // 2. 白平衡（色温）
            linear = linear * uTemperatureRgb;
            
            // 3. 曝光增益（在线性空间）
            linear = linear * uExposureGain;
            
            // 4. 饱和度调整（OKLab + 高光保护）
            linear = adjustSaturationOklab(linear, uSaturationAdjust);
            
            // 5. Linear → sRGB
            vec3 srgb = linearToSrgb(linear);
            
            srgb = clamp(srgb, 0.0, 1.0);
            
            fragColor = vec4(srgb, color.a);
        }
    """.trimIndent()

    override fun onProgramCreated() {
        uTextureLocation = getUniformLocation("uTexture")
        uTexMatrixLocation = getUniformLocation("uTexMatrix")
        uTemperatureRgbLocation = getUniformLocation("uTemperatureRgb")
        uExposureGainLocation = getUniformLocation("uExposureGain")
        uSaturationAdjustLocation = getUniformLocation("uSaturationAdjust")
        uEnabledLocation = getUniformLocation("uEnabled")

        aPositionLocation = getAttribLocation("aPosition")
        aTexCoordLocation = getAttribLocation("aTexCoord")
    }

    fun setTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        setUniform1i(uTextureLocation, 0)
    }

    fun setTemperature(rgbMultipliers: FloatArray) {
        temperatureRgb = rgbMultipliers
        GLES30.glUniform3fv(uTemperatureRgbLocation, 1, temperatureRgb, 0)
    }

    fun setExposureGain(gain: Float) {
        exposureGain = gain.coerceIn(0.1f, 10f)
        setUniform1f(uExposureGainLocation, exposureGain)
    }

    fun setSaturationAdjust(adjust: Float) {
        saturationAdjust = adjust.coerceIn(-0.5f, 0.5f)
        setUniform1f(uSaturationAdjustLocation, saturationAdjust)
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        GLES30.glUniform1i(uEnabledLocation, if (enabled) 1 else 0)
    }

    fun applyParams(palette: ColorPalette, enabled: Boolean) {
        setTemperature(palette.getTemperatureMultipliers())
        setExposureGain(palette.exposureGain)
        setSaturationAdjust(palette.saturationAdjust)
        setEnabled(enabled)
    }

    // 兼容旧 API
    fun applyParams(rgbMultipliers: FloatArray, saturation: Float, tone: Float, enabled: Boolean) {
        setTemperature(rgbMultipliers)
        setSaturationAdjust(saturation - 1f)
        val ev = tone * ColorPalette.EV_MAX
        setExposureGain(2.0.pow(ev.toDouble()).toFloat())
        setEnabled(enabled)
    }

    fun setSaturation(factor: Float) {
        setSaturationAdjust(factor - 1f)
    }
    
    fun setTone(factor: Float) {
        val ev = factor * ColorPalette.EV_MAX
        setExposureGain(2.0.pow(ev.toDouble()).toFloat())
    }

    fun getPositionAttributeLocation(): Int = aPositionLocation
    fun getTexCoordAttributeLocation(): Int = aTexCoordLocation
}
