package com.example.fraudcheck.checks

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.fraudcheck.core.Signal
import kotlin.math.sqrt

/**
 * Records accelerometer + gyroscope while the user fills the form.
 * A real hand-held phone always shows tiny noise; emulators and some farm rigs report constant values.
 */
class SensorRecorder(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelMag = ArrayList<Double>()
    private val gyroMag = ArrayList<Double>()

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        val list = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) accelMag else gyroMag
        synchronized(list) { if (list.size < 20_000) list += m }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun stdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val mean = xs.average()
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
    }

    fun signal(): Signal {
        val a = synchronized(accelMag) { accelMag.toList() }
        val g = synchronized(gyroMag) { gyroMag.toList() }
        val aSd = stdDev(a)
        val gSd = stdDev(g)
        val reasons = mutableListOf<String>()
        if (accel == null) reasons += "no accelerometer"
        if (gyro == null) reasons += "no gyroscope"
        if (a.size >= 50 && aSd < 1e-4) reasons += "accelerometer perfectly constant"
        if (g.size >= 50 && gSd < 1e-5) reasons += "gyroscope perfectly constant"
        if (a.size < 50 && accel != null) reasons += "too few samples yet (keep the form open longer)"
        val flagged = reasons.any { !it.startsWith("too few") }
        return Signal("sensors.variance", "Motion sensors look synthetic", flagged, weight = 25,
            evidence = "accel n=${a.size} sd=%.6f gyro n=${g.size} sd=%.6f %s".format(aSd, gSd, reasons))
    }
}
