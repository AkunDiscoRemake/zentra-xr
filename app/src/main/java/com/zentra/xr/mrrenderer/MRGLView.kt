package com.zentra.xr.mrrenderer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.zentra.xr.cardboard.CardboardManager

/**
 * ZENTRA XR - MR GLSurfaceView
 * Wrapper for MRRenderer with Cardboard integration
 * Prepared for future stereo distortion rendering
 */

class MRGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val cardboardManager: CardboardManager? = null
) : GLSurfaceView(context, attrs) {

    private var mrRenderer: MRRenderer? = null

    init {
        // OpenGL ES 2.0 context
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true

        // For future Cardboard distortion, we might need stencil/depth
        // setEGLConfigChooser(8, 8, 8, 8, 16, 8)

        // Renderer will be set via setup()
    }

    fun setup(cardboardManager: CardboardManager, onSurfaceTextureReady: (android.graphics.SurfaceTexture) -> Unit) {
        mrRenderer = MRRenderer(context, cardboardManager).apply {
            this.onSurfaceTextureReady = onSurfaceTextureReady
        }
        setRenderer(mrRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun getMRRenderer(): MRRenderer? = mrRenderer

    override fun onPause() {
        super.onPause()
        // Cardboard tracking paused in ViewModel
    }

    override fun onResume() {
        super.onResume()
    }
}
