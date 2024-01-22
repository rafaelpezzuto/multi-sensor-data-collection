package org.rjpd.msdc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.withSign
import timber.log.Timber

class AngleDetectionService(private val context: Context) {
    private lateinit var sensorManager: SensorManager
    private lateinit var sensorListener: SensorEventListener
    private lateinit var sensor: Sensor
    private lateinit var statusView: TextView
    private var devicePosition: Int? = null
    private var pitch = 0.0
    private var tilt = 0.0
    private var azimuth = 0.0

    fun create() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)!!

        sensorListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            override fun onSensorChanged(event: SensorEvent) {
                val g: DoubleArray = convertFloatsToDoubles(event.values.clone())

                val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2] + g[3] * g[3])
                g[0] /= norm
                g[1] /= norm
                g[2] /= norm
                g[3] /= norm

                val x = g[0]
                val y = g[1]
                val z = g[2]
                val w = g[3]

                val sinP = 2.0 * (w * x + y * z)
                val cosP = 1.0 - 2.0 * (x * x + y * y)
                pitch = atan2(sinP, cosP) * (180 / Math.PI)

                val sinT = 2.0 * (w * y - z * x)
                tilt =
                    if (abs(sinT) >= 1) (Math.PI / 2).withSign(sinT) * (180 / Math.PI) else asin(
                        sinT
                    ) * (180 / Math.PI)

                val sinA = 2.0 * (w * z + x * y)
                val cosA = 1.0 - 2.0 * (y * y + z * z)
                azimuth = atan2(sinA, cosA) * (180 / Math.PI)

                Timber.tag(TAG).d("$pitch,$tilt,$azimuth")

                if (devicePosition == 0 || devicePosition == 2) {
                    statusView.text = String.format("%.2f°", pitch).replace(",", ".")
                } else {
                    statusView.text = String.format("%.2f°", tilt).replace(",", ".")
                }
            }
        }
    }

    fun setDevicePosition(position: Int) {
        devicePosition = position
    }

    fun start(textView: TextView) {
        statusView = textView
        sensorManager.registerListener(
            sensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
    }

    private fun convertFloatsToDoubles(input: FloatArray?): DoubleArray {
        val output = DoubleArray(input!!.size)
        for (i in input.indices) output[i] = input[i].toDouble()
        return output
    }

    companion object {
        private const val TAG = "AngleDetectionService"
    }
}