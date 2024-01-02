package org.rjpd.msdc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ConsumptionService : Service() {
    private lateinit var scheduledExecutor: ScheduledExecutorService
    private var isMonitoring = false

    private var filename = ""
    private var outputDir = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isMonitoring) {
            startMonitoring(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startMonitoring(intent: Intent?) {
        isMonitoring = true

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val consumptionInterval = sharedPreferences.getInt("consumption_interval", 15).toLong()

        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent?.extras!!.getString("filename", "")

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor.scheduleAtFixedRate({
            val currentDateTime = getDateTimeUTC(System.currentTimeMillis())
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

            writeConsumptionData(currentDateTime, batteryStatus, outputDir, filename)
            Log.d("ConsumptionService", "$currentDateTime,$batteryStatus")

        }, 0, consumptionInterval, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        scheduledExecutor.shutdown()
    }
}