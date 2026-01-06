package com.luma.camera.render

import android.content.Context
import android.opengl.GLES30
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OpenGL ES 3.0 Shader 程序基类
 *
 * 提供 Shader 编译、链接、Uniform 设置等基础功能
 * 所有具体 Shader 实现继承此类
 */
abstract class ShaderProgram(
    protected val context: Context
) {
    /** Shader 程序 ID */
    var programId: Int = 0
        protected set

    /** 是否已初始化 */
    var isInitialized: Boolean = false
        protected set

    /**
     * 初始化 Shader 程序
     * 必须在 GL 线程调用
     */
    open fun initialize() {
        if (isInitialized) return

        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, getVertexShaderSource())
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, getFragmentShaderSource())

        if (vertexShader == 0 || fragmentShader == 0) {
            Timber.e("Failed to compile shaders")
            return
        }

        programId = linkProgram(vertexShader, fragmentShader)

        // 链接后可以删除 shader 对象
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        if (programId != 0) {
            isInitialized = true
            onProgramCreated()
            Timber.d("${javaClass.simpleName} initialized successfully")
        }
    }

    /**
     * 使用此 Shader 程序
     */
    fun use() {
        if (!isInitialized) {
            Timber.w("Shader program not initialized")
            return
        }
        GLES30.glUseProgram(programId)
    }

    /**
     * 释放资源
     */
    open fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        isInitialized = false
    }

    /**
     * 获取顶点着色器源码
     */
    protected abstract fun getVertexShaderSource(): String

    /**
     * 获取片段着色器源码
     */
    protected abstract fun getFragmentShaderSource(): String

    /**
     * Shader 程序创建成功后的回调
     * 子类可在此获取 Uniform/Attribute 位置
     */
    protected open fun onProgramCreated() {}

    // ==================== Uniform 设置方法 ====================

    protected fun getUniformLocation(name: String): Int {
        val location = GLES30.glGetUniformLocation(programId, name)
        if (location == -1) {
            Timber.w("Uniform '$name' not found in shader")
        }
        return location
    }

    protected fun getAttribLocation(name: String): Int {
        val location = GLES30.glGetAttribLocation(programId, name)
        if (location == -1) {
            Timber.w("Attribute '$name' not found in shader")
        }
        return location
    }

    protected fun setUniform1i(location: Int, value: Int) {
        GLES30.glUniform1i(location, value)
    }

    protected fun setUniform1f(location: Int, value: Float) {
        GLES30.glUniform1f(location, value)
    }

    protected fun setUniform2f(location: Int, x: Float, y: Float) {
        GLES30.glUniform2f(location, x, y)
    }

    protected fun setUniform3f(location: Int, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(location, x, y, z)
    }

    protected fun setUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {
        GLES30.glUniform4f(location, x, y, z, w)
    }

    protected fun setUniformMatrix4fv(location: Int, matrix: FloatArray) {
        GLES30.glUniformMatrix4fv(location, 1, false, matrix, 0)
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 raw 资源加载 Shader 源码
     */
    protected fun loadShaderFromRaw(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val builder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            builder.append(line).append("\n")
        }
        reader.close()
        return builder.toString()
    }

    /**
     * 编译单个 Shader
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            Timber.e("Failed to create shader of type $type")
            return 0
        }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)

        if (compileStatus[0] == 0) {
            val errorLog = GLES30.glGetShaderInfoLog(shader)
            Timber.e("Shader compilation failed: $errorLog")
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    /**
     * 链接 Shader 程序
     */
    private fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            Timber.e("Failed to create program")
            return 0
        }

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)

        if (linkStatus[0] == 0) {
            val errorLog = GLES30.glGetProgramInfoLog(program)
            Timber.e("Program linking failed: $errorLog")
            GLES30.glDeleteProgram(program)
            return 0
        }

        return program
    }

    companion object {
        /**
         * 检查 OpenGL 错误
         */
        fun checkGlError(operation: String) {
            var error: Int
            while (GLES30.glGetError().also { error = it } != GLES30.GL_NO_ERROR) {
                Timber.e("$operation: glError $error")
            }
        }
    }
}
