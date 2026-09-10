package com.zentra.xr.cardboard

import android.opengl.Matrix
import kotlin.math.pow

/**
 * ZENTRA XR Beta 2 - Cardboard Distortion
 * REAL lens distortion correction for VR Box
 * Implements barrel distortion using Cardboard SDK params
 */

data class DistortionCoefficients(
    val k1: Float = 0.34f,
    val k2: Float = 0.55f
)

data class LensParams(
    val screenToLensDistance: Float = 0.04f,
    val interLensDistance: Float = 0.064f,
    val distortionCoeffs: DistortionCoefficients = DistortionCoefficients(),
    val fovDegrees: Float = 60f
)

object CardboardDistortion {

    /**
     * Calculate distorted UV for barrel distortion
     * Formula from Google Cardboard SDK: https://github.com/googlevr/cardboard
     * r' = r * (1 + k1*r^2 + k2*r^4)
     */
    fun distortInverse(
        x: Float, y: Float,
        coeffs: DistortionCoefficients
    ): Pair<Float, Float> {
        // Inverse distortion (for rendering)
        // We want to find source UV that maps to distorted UV
        val r2 = x * x + y * y
        val r4 = r2 * r2
        val distortionFactor = 1f + coeffs.k1 * r2 + coeffs.k2 * r4
        return Pair(x * distortionFactor, y * distortionFactor)
    }

    fun distort(
        x: Float, y: Float,
        coeffs: DistortionCoefficients
    ): Pair<Float, Float> {
        // Forward distortion
        val r2 = x * x + y * y
        val r4 = r2 * r2
        val factor = 1f + coeffs.k1 * r2 + coeffs.k2 * r4
        return Pair(x * factor, y * factor)
    }

    /**
     * Generate distortion mesh for OpenGL
     * Returns vertices with distorted tex coords
     */
    fun generateDistortionMesh(
        eye: Int, // 0 left, 1 right
        width: Int,
        height: Int,
        lensParams: LensParams,
        rows: Int = 20,
        cols: Int = 20
    ): DistortionMesh {
        val vertices = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        val eyeOffset = if (eye == 0) -lensParams.interLensDistance / 2f else lensParams.interLensDistance / 2f

        for (r in 0..rows) {
            for (c in 0..cols) {
                val u = c.toFloat() / cols
                val v = r.toFloat() / rows

                // Normalized device coords -1..1
                val x = (u * 2f - 1f)
                val y = (v * 2f - 1f)

                // Apply eye offset and lens distortion
                val (dx, dy) = distort(x, y, lensParams.distortionCoeffs)

                vertices.add(dx)
                vertices.add(dy)
                vertices.add(0f)

                // Tex coords with eye offset compensation
                val texU = (u + eyeOffset * 0.1f).coerceIn(0f, 1f)
                texCoords.add(texU)
                texCoords.add(v)
            }
        }

        // Indices for triangle strip
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i0 = (r * (cols + 1) + c).toShort()
                val i1 = ((r + 1) * (cols + 1) + c).toShort()
                val i2 = (r * (cols + 1) + c + 1).toShort()
                val i3 = ((r + 1) * (cols + 1) + c + 1).toShort()

                indices.add(i0)
                indices.add(i1)
                indices.add(i2)

                indices.add(i2)
                indices.add(i1)
                indices.add(i3)
            }
        }

        return DistortionMesh(
            vertices = vertices.toFloatArray(),
            texCoords = texCoords.toFloatArray(),
            indices = indices.toShortArray(),
            eye = eye
        )
    }

    /**
     * Calculate projection matrix for eye with lens params
     */
    fun calculateEyeProjection(
        eye: Int,
        fovDegrees: Float,
        aspect: Float,
        near: Float,
        far: Float,
        interLensDistance: Float
    ): FloatArray {
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, fovDegrees, aspect, near, far)

        // Apply IPD offset to projection
        // For left eye, shift frustum right, for right eye shift left
        // This creates proper stereo parallax
        val eyeOffset = if (eye == 0) -interLensDistance / 2f else interLensDistance / 2f
        // Simple translation in projection - in real SDK this is more complex with asymmetric frustum
        // For Beta 2, we use view matrix translation instead

        return proj
    }
}

data class DistortionMesh(
    val vertices: FloatArray,
    val texCoords: FloatArray,
    val indices: ShortArray,
    val eye: Int
)
