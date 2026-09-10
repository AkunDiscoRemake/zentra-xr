package com.zentra.xr.mrrenderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.zentra.xr.cardboard.CardboardDistortion
import com.zentra.xr.cardboard.CardboardManager
import com.zentra.xr.cardboard.LensParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ZENTRA XR Beta 2 - Stereo Renderer
 * REAL VR Box rendering with:
 * - Side-by-side viewports (left/right eye)
 * - IPD offset for stereo parallax
 * - Barrel distortion correction
 * - Camera background in stereo
 */

class StereoRenderer(
    private val context: Context,
    private val cardboardManager: CardboardManager
) : GLSurfaceView.Renderer {

    private var cameraTextureId = -1
    private var surfaceTexture: SurfaceTexture? = null
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // Shader programs
    private var cameraProgram = 0
    private var distortionProgram = 0

    // Handles
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uMVPHandle = 0
    private var uTextureHandle = 0
    private var uDistortionHandle = 0
    private var uEyeHandle = 0

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val eyeViewMatrix = FloatArray(16)

    // Quad for camera background
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

    private var vertexBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer

    // Stereo state
    private var screenWidth = 0
    private var screenHeight = 0
    private var stereoEnabled = true // Beta 2 defaults to stereo for VR Box
    private var lensParams = LensParams()

    init {
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(quadVertices)
                position(0)
            }
        texCoordBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(quadTexCoords)
                position(0)
            }
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.setIdentityM(viewMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Camera external texture
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

        // Camera shader (no distortion, for background)
        cameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)

        // Distortion shader (with barrel distortion)
        distortionProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_DISTORTION)

        Log.i(TAG, "StereoRenderer created, cameraTexId=$cameraTextureId")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        GLES20.glViewport(0, 0, width, height)

        val ratio = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 10f)

        Log.i(TAG, "Surface changed $width x $height stereo=$stereoEnabled")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        surfaceTexture?.updateTexImage()

        if (stereoEnabled) {
            // STEREO RENDERING - Left and Right eye
            val halfWidth = screenWidth / 2

            // Left eye
            GLES20.glViewport(0, 0, halfWidth, screenHeight)
            drawEye(0, halfWidth, screenHeight)

            // Right eye
            GLES20.glViewport(halfWidth, 0, halfWidth, screenHeight)
            drawEye(1, halfWidth, screenHeight)

        } else {
            // MONO fallback
            GLES20.glViewport(0, 0, screenWidth, screenHeight)
            drawEye(0, screenWidth, screenHeight, mono = true)
        }
    }

    private fun drawEye(eye: Int, width: Int, height: Int, mono: Boolean = false) {
        // Get head matrix from CardboardManager
        val headMatrix = FloatArray(16)
        cardboardManager.getHeadMatrix(headMatrix)

        // Eye view with IPD offset
        Matrix.setIdentityM(eyeViewMatrix, 0)
        val ipd = 0.064f
        val eyeOffset = if (eye == 0) -ipd / 2f else ipd / 2f
        if (!mono) {
            Matrix.translateM(eyeViewMatrix, 0, eyeOffset, 0f, 0f)
        }
        Matrix.multiplyMM(eyeViewMatrix, 0, headMatrix, 0, eyeViewMatrix, 0)

        // MVP
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, eyeViewMatrix, 0)

        // Draw camera background with distortion correction
        val program = if (mono) cameraProgram else distortionProgram
        GLES20.glUseProgram(program)

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMVPHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        uEyeHandle = GLES20.glGetUniformLocation(program, "uEye")
        uDistortionHandle = GLES20.glGetUniformLocation(program, "uDistortion")

        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(uMVPHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1i(uEyeHandle, eye)
        GLES20.glUniform2f(uDistortionHandle, lensParams.distortionCoeffs.let { it.k1 }, lensParams.distortionCoeffs.let { it.k2 })

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(uTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    }

    fun setStereoEnabled(enabled: Boolean) {
        stereoEnabled = enabled
        Log.i(TAG, "Stereo set to $enabled")
    }

    fun setLensParams(params: LensParams) {
        lensParams = params
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        }
        return program
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    companion object {
        private const val TAG = "ZentraStereoRenderer"

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
                gl_FragColor = color;
            }
        """

        private const val FRAGMENT_SHADER_DISTORTION = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform int uEye;
            uniform vec2 uDistortion; // k1, k2
            
            void main() {
                // Barrel distortion correction
                // Convert texCoord 0..1 to -1..1
                vec2 coord = vTexCoord * 2.0 - 1.0;
                float r2 = coord.x * coord.x + coord.y * coord.y;
                float r4 = r2 * r2;
                float distortionFactor = 1.0 + uDistortion.x * r2 + uDistortion.y * r4;
                
                // For VR Box, we apply inverse distortion to counteract lens distortion
                // Simple version: just scale based on distance from center
                // More accurate would use Cardboard's distortion mesh
                vec2 distortedCoord = coord * distortionFactor;
                
                // Convert back to 0..1
                vec2 distortedTexCoord = distortedCoord * 0.5 + 0.5;
                
                // Clamp to avoid sampling outside
                if (distortedTexCoord.x < 0.0 || distortedTexCoord.x > 1.0 || distortedTexCoord.y < 0.0 || distortedTexCoord.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); // black border for outside
                } else {
                    vec4 color = texture2D(uTexture, distortedTexCoord);
                    // Slight vignette for VR feel
                    float vignette = 1.0 - dot(coord, coord) * 0.15;
                    gl_FragColor = vec4(color.rgb * vignette, color.a);
                }
            }
        """
    }

    object GLES11Ext {
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
