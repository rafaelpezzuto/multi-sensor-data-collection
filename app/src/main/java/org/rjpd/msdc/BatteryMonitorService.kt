package org.rjpd.msdc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import timber.log.Timber

class BatteryMonitorService : Service() {
    private lateinit var scheduledExecutor: ScheduledExecutorService
    private var isMonitoring = false

    private var filename = ""
    private var outputDir = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (!isMonitoring) {
            startMonitoring(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            NOTIFICATION_TITLE,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startMonitoring(intent: Intent?) {
        isMonitoring = true

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val consumptionInterval = sharedPreferences.getInt("consumption_interval", 15).toLong()

        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent.extras!!.getString("filename", "")

        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor.scheduleWithFixedDelay({
            val currentDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
            val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

            writeConsumptionData(currentDateTime, batteryStatus, outputDir, filename)
            Timber.tag(TAG).d("$currentDateTime,$batteryStatus")

        }, 0, consumptionInterval, TimeUnit.SECONDS)
    }

    private fun stopMonitoring() {
        isMonitoring = false
        scheduledExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ConsumptionService"
        private const val CHANNEL_ID = "ConsumptionServiceChannel"
        private const val NOTIFICATION_ID = 3
        private const val NOTIFICATION_TITLE = "Battery Consumption Monitor"
        private const val NOTIFICATION_TEXT = "Monitoring battery..."
    }
}