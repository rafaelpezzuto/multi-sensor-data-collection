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
import androidx.preference.PreferenceManager
import org.json.JSONArray
import java.io.File


class SensorsService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager

    private val supportedSensors = mutableListOf<Int>()
    private val sensorDataPath: MutableMap<Int, File> = mutableMapOf()

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
            sensorDataPath[Sensor.TYPE_ACCELEROMETER] = File(outputDir, "${filename}.accelerometer.csv")
        }

        if (sharedPreferences.getBoolean("gravity", false)){
            supportedSensors.add(Sensor.TYPE_GRAVITY)
            sensorDataPath[Sensor.TYPE_GRAVITY] = File(outputDir, "${filename}.gravity.csv")
        }

        if (sharedPreferences.getBoolean("gyroscope", false)){
            supportedSensors.add(Sensor.TYPE_GYROSCOPE)
            sensorDataPath[Sensor.TYPE_GYROSCOPE] = File(outputDir, "${filename}.gyroscope.csv")
        }

        if (sharedPreferences.getBoolean("magnetometer", false)){
            supportedSensors.add(Sensor.TYPE_MAGNETIC_FIELD)
            sensorDataPath[Sensor.TYPE_MAGNETIC_FIELD] = File(outputDir, "${filename}.magnetic_field.csv")
        }

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

            if (event?.sensor?.type in sensorDataPath.keys) {
                Log.d("SensorsService", "$sensorDataPath, $event?.sensor?.type")
                writeSensorData(name, axisData, accuracy, timestamp, sensorDataPath[event?.sensor?.type].toString())
            }
            Log.d("SensorsService", "$name,$axisData,$accuracy,$timestamp, $sensorDataPath")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, p1: Int) {
        return
    }
}