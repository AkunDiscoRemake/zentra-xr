package com.pinavr.os.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * PINA VR - Passthrough Camera Service - Camera2 API para MR
 * Usa Camera2Manager para câmera traseira em modo passthrough
 * Converte YUV_420_888 para processamento ou envia direto para WebView via JS
 */

class PassthroughCameraService(private val context: Context) {

    private val camera2Manager = Camera2Manager(context)
    private var isActive = false

    var onFrameBitmap: ((bitmap: Bitmap) -> Unit)? = null
    var onFrameYuv: ((yuv: ByteArray, width: Int, height: Int) -> Unit)? = null

    fun start() {
        if (isActive) return
        isActive = true

        camera2Manager.startRearCamera { image ->
            try {
                // Converte YUV_420_888 para Bitmap para debug ou processamento
                // Para MR passthrough real, o JS usa getUserMedia, mas aqui temos controle Camera2 nativo
                // Podemos enviar frame via base64 ou processar depth

                // Opção 1: envia YUV direto
                val yuv = yuv420ToNv21(image)
                onFrameYuv?.invoke(yuv, image.width, image.height)

                // Opção 2: bitmap (mais pesado, só se necessário)
                // val bmp = yuvToBitmap(image)
                // onFrameBitmap?.invoke(bmp)

                // Stats
                Log.d("PinaPassthrough", "Rear frame ${image.width}x${image.height} format ${image.format}")

            } catch (e: Exception) {
                Log.e("PinaPassthrough", "frame error", e)
            } finally {
                image.close()
            }
        }

        Log.i("PinaPassthrough", "Passthrough Camera2 iniciado - ${camera2Manager.getCameraInfo()}")
    }

    fun stop() {
        isActive = false
        camera2Manager.stopRear()
    }

    // YUV_420_888 -> NV21 para conversão fácil
    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(width * height + width * height / 2)

        // U e V são intercalados em NV21 como VU
        // Y
        yBuffer.get(nv21, 0, ySize)

        // UV
        val uvPixelStride = uPlane.pixelStride
        if (uvPixelStride == 1) {
            // UV planar simples
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            // Reordena para NV21 (VU)
            // Já está quase, mas precisa intercalar
            // Simplificado: usa U e V separados e intercala
            val uv = ByteArray(width * height / 2)
            var pos = 0
            for (i in 0 until height/2) {
                for (j in 0 until width/2) {
                    val uIndex = i * uPlane.rowStride + j * uvPixelStride
                    val vIndex = i * vPlane.rowStride + j * vPlane.pixelStride
                    if (vIndex < vSize && uIndex < uSize) {
                        uv[pos++] = vBuffer.get(vIndex)
                        uv[pos++] = uBuffer.get(uIndex)
                    }
                }
            }
            System.arraycopy(uv, 0, nv21, ySize, minOf(uv.size, nv21.size - ySize))
        } else {
            // UV intercalado
            var pos = ySize
            for (i in 0 until height/2) {
                for (j in 0 until width/2) {
                    val index = i * vPlane.rowStride + j * vPlane.pixelStride
                    if (index < vSize) {
                        nv21[pos++] = vBuffer.get(index)
                        if (pos < nv21.size) nv21[pos++] = uBuffer.get(index)
                    }
                }
            }
        }

        return nv21
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0,0,image.width,image.height), 90, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun getInfo(): String = camera2Manager.getCameraInfo()
}
