package org.rjpd.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log


class SensorsService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager

    private val supportedSensors = arrayOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
    )

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSensors()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startSensors() {
        for (sensor in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.type in supportedSensors) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type in supportedSensors) {
            val axisData = event?.values?.joinToString(",") { "${it}" }
            val name = event?.sensor?.name
            val accuracy = event?.accuracy
            val timestamp = event?.timestamp

            writeSensorData(this@SensorsService, name, axisData, accuracy, timestamp)
            Log.d("SensorsService", "$name,$axisData,$accuracy,$timestamp")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, p1: Int) {
        return
    }
}