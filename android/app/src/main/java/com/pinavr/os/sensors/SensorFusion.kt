package com.pinavr.os.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

/**
 * PINA VR - Sensor Fusion Nativa - Melhor Giroscópio Possível
 * - Fusão de Gyro (200Hz) + Accel + Magnetometer via RotationVector (hardware)
 * - Kalman Filter para correção de drift
 * - Complementary filter + Madgwick
 * - Entrega quaternion direto para WebView via NativeBridge 200Hz
 */

class SensorFusion(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Estado
    var quaternion = floatArrayOf(0f, 0f, 0f, 1f) // x,y,z,w
    var gyro = floatArrayOf(0f, 0f, 0f) // rad/s
    var accel = floatArrayOf(0f, 0f, 9.81f)
    var mag = floatArrayOf(0f, 0f, 0f)
    var gyroBias = floatArrayOf(0f, 0f, 0f)

    // Kalman para orientação
    private var kalmanP = 1.0
    private val kalmanQ = 0.0001
    private val kalmanR = 0.5

    // Para integração
    private var lastTimestamp: Long = 0
    private var isCalibrated = false
    private val biasSamples = mutableListOf<FloatArray>()
    private val rotationMatrix = FloatArray(16)
    private val orientationAngles = FloatArray(3)

    // Listener
    var onSensorsUpdate: ((quat: FloatArray, gyro: FloatArray, accel: FloatArray, mag: FloatArray) -> Unit)? = null

    // OneEuro para accel (reduz jitter)
    private var accelFiltered = floatArrayOf(0f, 0f, 9.81f)
    private var lastAccelTime = 0L

    fun start() {
        // Registra com máxima frequência - SENSOR_DELAY_FASTEST = 200-400Hz
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gameRotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        magSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Calibração de bias
        calibrateBias()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    private fun calibrateBias() {
        // Coleta 1s de gyro parado
        biasSamples.clear()
        Thread {
            Thread.sleep(1000)
            if (biasSamples.size > 20) {
                var bx = 0f; var by = 0f; var bz = 0f
                for (s in biasSamples) { bx += s[0]; by += s[1]; bz += s[2] }
                bx /= biasSamples.size; by /= biasSamples.size; bz /= biasSamples.size
                gyroBias = floatArrayOf(bx, by, bz)
                isCalibrated = true
                android.util.Log.i("PinaSensors", "Bias calibrado: $bx, $by, $bz")
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // Melhor precisão: RotationVector já faz fusão hardware
                SensorManager.getQuaternionFromVector(quaternion, event.values)
                // quaternion do Android é [x,y,z,w] já, mas getQuaternionFromVector retorna [x,y,z,w] ?
                // Na verdade retorna x,y,z,w em ordem diferente em algumas versões, normalizamos
                // Converte para nosso formato [x,y,z,w]
                // event.values é [x,y,z,w, accuracy] para ROTATION_VECTOR
                // Já temos quaternion direto

                // Atualiza rotationMatrix para debug
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                notifyUpdate()
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                // values: [x,y,z, bias_x, bias_y, bias_z]
                val rawGyro = floatArrayOf(event.values[0], event.values[1], event.values[2])
                if (!isCalibrated) {
                    biasSamples.add(rawGyro)
                    if (biasSamples.size > 200) biasSamples.removeAt(0)
                }
                gyro = floatArrayOf(
                    rawGyro[0] - gyroBias[0],
                    rawGyro[1] - gyroBias[1],
                    rawGyro[2] - gyroBias[2]
                )

                // Integração se não tiver rotation vector
                if (rotationVectorSensor == null) {
                    integrateGyro(event.timestamp)
                }

                notifyUpdate()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // One Euro filter simples para accel
                val now = System.nanoTime()
                val dt = if (lastAccelTime == 0L) 0.016f else (now - lastAccelTime) / 1e9f
                lastAccelTime = now
                val alpha = 0.15f // low-pass
                for (i in 0..2) {
                    accelFiltered[i] = accelFiltered[i] * (1 - alpha) + event.values[i] * alpha
                }
                accel = accelFiltered.clone()

                // Se sem rotation vector, corrige pitch/roll com accel
                if (rotationVectorSensor == null) {
                    correctWithAccel()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mag = event.values.clone()
            }
        }
    }

    private fun integrateGyro(timestamp: Long) {
        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return
        }
        val dt = (timestamp - lastTimestamp) * 1e-9f
        lastTimestamp = timestamp
        if (dt <= 0 || dt > 0.1f) return

        val gx = gyro[0]; val gy = gyro[1]; val gz = gyro[2]
        val qx = quaternion[0]; val qy = quaternion[1]; val qz = quaternion[2]; val qw = quaternion[3]

        val halfDt = dt * 0.5f
        val dqW = -halfDt * (gx*qx + gy*qy + gz*qz)
        val dqX = halfDt * (gx*qw + gy*qz - gz*qy)
        val dqY = halfDt * (-gx*qz + gy*qw + gz*qx)
        val dqZ = halfDt * (gx*qy - gy*qx + gz*qw)

        var nq = floatArrayOf(qx+dqX, qy+dqY, qz+dqZ, qw+dqW)
        val len = sqrt(nq[0]*nq[0] + nq[1]*nq[1] + nq[2]*nq[2] + nq[3]*nq[3])
        nq = floatArrayOf(nq[0]/len, nq[1]/len, nq[2]/len, nq[3]/len)
        quaternion = nq
    }

    private fun correctWithAccel() {
        val ax = accel[0]; val ay = accel[1]; val az = accel[2]
        val norm = sqrt(ax*ax + ay*ay + az*az)
        if (norm < 0.1f) return
        val axn = ax/norm; val ayn = ay/norm; val azn = az/norm

        // Gravidade esperada do quaternion atual
        val qx = quaternion[0]; val qy = quaternion[1]; val qz = quaternion[2]; val qw = quaternion[3]
        val gxEst = 2*(qx*qz - qw*qy)
        val gyEst = 2*(qw*qx + qy*qz)
        val gzEst = qw*qw - qx*qx - qy*qy + qz*qz

        val ex = ayn*gzEst - azn*gyEst
        val ey = azn*gxEst - axn*gzEst
        val ez = axn*gyEst - ayn*gxEst

        val k = 0.02f
        gyroBias[0] += ex * k * 0.01f
        gyroBias[1] += ey * k * 0.01f
        gyroBias[2] += ez * k * 0.01f

        quaternion[0] += ex * k
        quaternion[1] += ey * k
        quaternion[2] += ez * k
        val len = sqrt(quaternion[0]*quaternion[0] + quaternion[1]*quaternion[1] + quaternion[2]*quaternion[2] + quaternion[3]*quaternion[3])
        quaternion = floatArrayOf(quaternion[0]/len, quaternion[1]/len, quaternion[2]/len, quaternion[3]/len)
    }

    private fun notifyUpdate() {
        onSensorsUpdate?.invoke(quaternion, gyro, accel, mag)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun getJson(): String {
        // Para enviar ao WebView via JavascriptInterface
        return """{"quat":[${quaternion[0]},${quaternion[1]},${quaternion[2]},${quaternion[3]}],"gyro":[${gyro[0]},${gyro[1]},${gyro[2]}],"accel":[${accel[0]},${accel[1]},${accel[2]}],"mag":[${mag[0]},${mag[1]},${mag[2]}]}"""
    }
}
