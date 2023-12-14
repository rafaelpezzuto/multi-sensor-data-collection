package org.rjpd.msdc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager


class SensorsService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager

    private val supportedSensors = mutableListOf<Int>()

    private var filename = ""
    private var outputDir = ""

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSensors(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startSensors(intent: Intent?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent?.extras!!.getString("filename", "")

        if (sharedPreferences.getBoolean("accelerometer", false)) {
            supportedSensors.add(Sensor.TYPE_ACCELEROMETER)
            supportedSensors.add(Sensor.TYPE_LINEAR_ACCELERATION)
        }

        if (sharedPreferences.getBoolean("gravity", false)){
            supportedSensors.add(Sensor.TYPE_GRAVITY)
        }

        if (sharedPreferences.getBoolean("gyroscope", false)){
            supportedSensors.add(Sensor.TYPE_GYROSCOPE)
            supportedSensors.add(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        }

        if (sharedPreferences.getBoolean("magnetometer", false)){
            supportedSensors.add(Sensor.TYPE_MAGNETIC_FIELD)
            supportedSensors.add(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        }

        if (sharedPreferences.getBoolean("light", false)) {
            supportedSensors.add(Sensor.TYPE_LIGHT)
        }

        if (sharedPreferences.getBoolean("extra", false)) {
            supportedSensors.add(Sensor.TYPE_STEP_DETECTOR)
            supportedSensors.add(Sensor.TYPE_STEP_COUNTER)
            supportedSensors.add(Sensor.TYPE_SIGNIFICANT_MOTION)
            supportedSensors.add(Sensor.TYPE_PRESSURE)
            supportedSensors.add(Sensor.TYPE_PROXIMITY)
        }

        for (sensor in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.type in supportedSensors) {
                val sensorDelay = sharedPreferences.getString("sensors_sr", SensorManager.SENSOR_DELAY_NORMAL.toString())!!.toInt()
                Log.d("SensorsService", "Added sensor ${sensor.name} with sampling rate $sensorDelay (microseconds)")
                sensorManager.registerListener(this, sensor, sensorDelay)
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
            val name = event?.sensor?.name
            val axisData = event?.values?.joinToString(",") { "${it}" }
            val timestamp = event?.timestamp
            val accuracy = event?.accuracy

            writeSensorData(name, axisData, timestamp, accuracy, outputDir, filename)
            Log.d("SensorsService", "$name,$axisData,$timestamp,$accuracy")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, p1: Int) {
        return
    }
}