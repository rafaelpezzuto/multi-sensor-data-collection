package org.rjpd.msdc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import timber.log.Timber


class CellularNetworkScanService : Service() {
    private lateinit var telephonyManager: TelephonyManager
    private var isMonitoring = false

    private val handler = Handler(Looper.getMainLooper())
    private val intervalMillis = 30000L

    private var filename = ""
    private var outputDir = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
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

    private fun startScanning(intent: Intent?) {
        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent.extras!!.getString("filename", "")

        handler.post(object : Runnable {
            @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            override fun run() {
                val cellInfoList: List<CellInfo> = telephonyManager.allCellInfo
                val currentDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())

                writeCellularNetworkData(currentDateTime, cellInfoList, outputDir, filename)
                Timber.tag(TAG).d("Cellular networks found: $cellInfoList")
                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    companion object {
        private const val TAG = "CellularNetworkScanService"
        private const val CHANNEL_ID = "CellularNetworkScanServiceChannel"
        private const val NOTIFICATION_ID = 7
        private const val NOTIFICATION_TITLE = "Cellular Network Scan Service"
        private const val NOTIFICATION_TEXT = "Scanning for cellular networks..."
    }
}
