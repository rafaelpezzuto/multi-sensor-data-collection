package org.rjpd.data

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ToggleButton
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager


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

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        val startStopButton = findViewById<ToggleButton>(R.id.start_stop_button)
        val exportButton = findViewById<Button>(R.id.export_button)
        val settingsButton = findViewById<Button>(R.id.settings_button)

        startStopButton.setOnCheckedChangeListener {_, isChecked ->
            val intentSensorsService = Intent(this@MainActivity, SensorsService::class.java)
            val intentLocationTrackingService = Intent(this@MainActivity, LocationTrackingService::class.java)

            if (isChecked) {
                startService(intentSensorsService)

                if (sharedPreferences.getBoolean("gps", false)) {
                    startService(intentLocationTrackingService)
                }

            } else {
                stopService(intentSensorsService)

                if (sharedPreferences.getBoolean("gps", false)) {
                    stopService(intentLocationTrackingService)
                }
            }

            exportButton.isEnabled = !exportButton.isEnabled
            settingsButton.isEnabled = !settingsButton.isEnabled
        }

        exportButton.setOnClickListener {
            // ToDo: implement export module
        }

        settingsButton.setOnClickListener {
            val intentSettings = Intent(this@MainActivity, SettingsActivity::class.java)
            startActivity(intentSettings)
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
                // ToDo: research about it
            } else {
                // ToDo: research about it
            }
        }
    }
}
