package org.rjpd.msdc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import timber.log.Timber


class WiFiNetworkScanService : Service() {
    private lateinit var wifiManager: WifiManager
    private var isMonitoring = false

    private val handler = Handler(Looper.getMainLooper())
    private val intervalMillis = 30000L

    private var filename = ""
    private var outputDir = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (!isMonitoring) {
            startScanning(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun startScanning(intent: Intent?) {
        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent.extras!!.getString("filename", "")

        handler.post(object : Runnable {
            override fun run() {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    wifiManager.startScan()
                    val scanResults = wifiManager.scanResults

                    try {
                        val currentDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
                        writeWifiNetworkData(currentDateTime, scanResults, outputDir, filename)
                        Timber.tag(TAG).d("$currentDateTime,$scanResults")
                    } catch (e: UninitializedPropertyAccessException) {
                        Timber.tag(TAG).d("Wi-Fi Scan Service has not been initialized: $e")
                    }
                } else {
                    Timber.tag(TAG).d("Permission for accessing Wi-Fi scan results is not granted.")
                }
                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    companion object {
        private const val TAG = "WiFiNetworkScanService"
        private const val CHANNEL_ID = "WiFiNetworkScanServiceChannel"
        private const val NOTIFICATION_ID = 6
        private const val NOTIFICATION_TITLE = "Wi-Fi Network Scan Service"
        private const val NOTIFICATION_TEXT = "Scanning for Wi-Fi networks..."
    }
}
