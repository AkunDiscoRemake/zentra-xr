package com.zentra.xr.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ZENTRA XR - Settings Module
 * Simple local settings, no cloud
 * - Stereo mode, tracking quality, etc.
 */

class ZentraSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("zentra_settings", Context.MODE_PRIVATE)

    private val _stereoEnabled = MutableStateFlow(prefs.getBoolean(KEY_STEREO, false))
    val stereoEnabled: StateFlow<Boolean> = _stereoEnabled

    private val _trackingQuality = MutableStateFlow(
        TrackingQuality.valueOf(prefs.getString(KEY_TRACKING_QUALITY, TrackingQuality.BALANCED.name) ?: TrackingQuality.BALANCED.name)
    )
    val trackingQuality: StateFlow<TrackingQuality> = _trackingQuality

    private val _handSmoothing = MutableStateFlow(prefs.getFloat(KEY_SMOOTHING, 0.35f))
    val handSmoothing: StateFlow<Float> = _handSmoothing

    enum class TrackingQuality { LOW, BALANCED, HIGH }

    fun setStereoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEREO, enabled).apply()
        _stereoEnabled.value = enabled
    }

    fun setTrackingQuality(quality: TrackingQuality) {
        prefs.edit().putString(KEY_TRACKING_QUALITY, quality.name).apply()
        _trackingQuality.value = quality
    }

    fun setHandSmoothing(value: Float) {
        prefs.edit().putFloat(KEY_SMOOTHING, value).apply()
        _handSmoothing.value = value
    }

    companion object {
        private const val KEY_STEREO = "stereo_enabled"
        private const val KEY_TRACKING_QUALITY = "tracking_quality"
        private const val KEY_SMOOTHING = "hand_smoothing"
    }
}
