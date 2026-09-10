package com.zentra.xr.mrrenderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.zentra.xr.cardboard.CardboardManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ZENTRA XR - MR Renderer
 * Composites camera background + virtual Joy-Con models + pointers
 * - Uses OpenGL ES 2.0/3.0 for performance
 * - Supports mono and stereo (Cardboard) rendering paths
 * - Camera texture as background
 * - Minimal overdraw
 */
class MRRenderer(
    private val context: Context,
    private val cardboardManager: CardboardManager
) : GLSurfaceView.Renderer {

    // Camera texture
    private var cameraTextureId = -1
    private var surfaceTexture: SurfaceTexture? = null

    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Shader for camera background (OES external)
    private var cameraProgram = 0
    private var cameraPositionHandle = 0
    private var cameraTexCoordHandle = 0
    private var cameraMVPHandle = 0
    private var cameraTextureHandle = 0

    // Simple quad vertices
    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f,
        1f, -1f, 0f,
        -1f, 1f, 0f,
        1f, 1f, 0f
    )
    private val quadTexCoords = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    private var vertexBuffer: java.nio.FloatBuffer
    private var texCoordBuffer: java.nio.FloatBuffer

    init {
        vertexBuffer = java.nio.ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(quadVertices)
                position(0)
            }
        texCoordBuffer = java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(quadTexCoords)
                position(0)
            }
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.08f, 1f)

        // Create external texture for camera
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(cameraTextureId).also {
            onSurfaceTextureReady?.invoke(it)
        }

        // Compile camera shader
        cameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
        cameraPositionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        cameraTexCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        cameraMVPHandle = GLES20.glGetUniformLocation(cameraProgram, "uMVPMatrix")
        cameraTextureHandle = GLES20.glGetUniformLocation(cameraProgram, "uTexture")

        Log.i(TAG, "MR Renderer created, cameraTexId=$cameraTextureId")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 10f)
        Log.i(TAG, "Surface changed $width x $height")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        surfaceTexture?.updateTexImage()

        // Draw camera background
        GLES20.glUseProgram(cameraProgram)
        GLES20.glEnableVertexAttribArray(cameraPositionHandle)
        GLES20.glVertexAttribPointer(cameraPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(cameraTexCoordHandle)
        GLES20.glVertexAttribPointer(cameraTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(cameraMVPHandle, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(cameraPositionHandle)
        GLES20.glDisableVertexAttribArray(cameraTexCoordHandle)

        // Future: draw Joy-Con models via OpenGL here
        // For Beta 1, Joy-Con rendering is done via Compose overlay for simplicity and stability
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val TAG = "ZentraMRRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uMVPMatrix;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                // Slight MR tint for XR feel, but keep real environment
                gl_FragColor = color;
            }
        """
    }

    // Workaround for OES constant
    object GLES11Ext {
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
