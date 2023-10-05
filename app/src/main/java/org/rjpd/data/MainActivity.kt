package org.rjpd.data

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ToggleButton
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi


const val REQUEST_COLLECTING_DATA = 999


class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val permissions = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        requestPermissions(permissions, REQUEST_COLLECTING_DATA)

        val startSensorsButton = findViewById<ToggleButton>(R.id.start_sensors_button)
        val startGPSButton = findViewById<ToggleButton>(R.id.start_gps_button)
        val startCameraRecordingButton = findViewById<ToggleButton>(R.id.start_camera_recording_button)

        startSensorsButton.setOnCheckedChangeListener {_, isChecked ->
            val intentSensorsService = Intent(this@MainActivity, SensorsService::class.java)
            if (isChecked) {
                startService(intentSensorsService)
            } else {
                stopService(intentSensorsService)
            }
        }

        startGPSButton.setOnCheckedChangeListener {_, isChecked ->
            val intentLocationTrackingService = Intent(this@MainActivity, LocationTrackingService::class.java)
            if (isChecked) {
                startService(intentLocationTrackingService)
            } else {
                stopService(intentLocationTrackingService)
            }
        }

        startCameraRecordingButton.setOnCheckedChangeListener {buttonView, isChecked ->
            if (isChecked){
                // ToDo
            } else {
                // ToDo
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_COLLECTING_DATA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            } else {
            }
        }
    }
}
