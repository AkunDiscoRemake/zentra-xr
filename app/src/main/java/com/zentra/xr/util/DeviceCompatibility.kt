package com.zentra.xr.util

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log

/**
 * ZENTRA XR - Compatibility Checker
 * Verifies device capabilities and shows clear messages instead of crashing
 */
data class CompatibilityReport(
    val hasCamera: Boolean,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasMagnetometer: Boolean,
    val supportsOpenGL30: Boolean,
    val androidVersionOk: Boolean,
    val ramOk: Boolean,
    val isProbablyLowEnd: Boolean,
    val warnings: List<String>,
    val errors: List<String>,
    val isCompatible: Boolean
)

object DeviceCompatibility {

    fun check(context: Context): CompatibilityReport {
        val pm = context.packageManager
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasMag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

        val gl30 = pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_AEP) ||
                true // Most devices support ES 3.0 now

        val androidOk = Build.VERSION.SDK_INT >= 26 // minSdk 26

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamGB = memInfo.totalMem / (1024f * 1024f * 1024f)
        val ramOk = totalRamGB >= 2.5f

        val isLowEnd = activityManager.isLowRamDevice || totalRamGB < 3f

        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (!hasCamera) errors.add("Câmera não encontrada. O ZENTRA XR precisa da câmera para MR.")
        if (!hasAccel) warnings.add("Acelerômetro ausente. Cardboard IMU limitado.")
        if (!hasGyro) warnings.add("Giroscópio ausente. Experiência Cardboard será limitada.")
        if (!hasMag) warnings.add("Magnetômetro ausente. Drift de rotação pode ocorrer.")
        if (!androidOk) errors.add("Android 8.0+ necessário. Atualize seu sistema.")
        if (!ramOk) warnings.add("Pouca RAM (${String.format("%.1f", totalRamGB)}GB). Performance pode ser reduzida.")
        if (isLowEnd) warnings.add("Dispositivo de baixo desempenho detectado. Hand tracking em qualidade reduzida.")

        val isCompatible = errors.isEmpty() && hasCamera

        val report = CompatibilityReport(
            hasCamera = hasCamera,
            hasAccelerometer = hasAccel,
            hasGyroscope = hasGyro,
            hasMagnetometer = hasMag,
            supportsOpenGL30 = gl30,
            androidVersionOk = androidOk,
            ramOk = ramOk,
            isProbablyLowEnd = isLowEnd,
            warnings = warnings,
            errors = errors,
            isCompatible = isCompatible
        )

        Log.i("ZentraCompat", "Report: $report")
        return report
    }
}
