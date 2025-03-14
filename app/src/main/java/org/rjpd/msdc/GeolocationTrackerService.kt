package org.rjpd.msdc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import timber.log.Timber


class GeolocationTrackerService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates(intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startLocationUpdates(intent: Intent?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val outputDir = intent?.extras!!.getString("outputDirectory", "")
        val filename = intent.extras!!.getString("filename", "")

        val gpsInterval = sharedPreferences.getInt("gps_interval", 30).toLong()
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, gpsInterval * 1000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val latitude = location.latitude.toString()
                    val longitude = location.longitude.toString()
                    val accuracy = location.accuracy.toString()

                    val eventDateTimeUTC = TimeUtils.getDateTimeUTC(System.currentTimeMillis())

                    writeGeolocationData(
                        eventDateTimeUTC,
                        gpsInterval.toString(),
                        accuracy,
                        latitude,
                        longitude,
                        outputDir,
                        filename
                    )
                    Timber.tag(TAG).d("$eventDateTimeUTC,$gpsInterval,$accuracy,$latitude,$longitude")
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            null
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    companion object {
        private const val TAG = "GeolocationTrackerService"
        private const val CHANNEL_ID = "GeolocationTrackerServiceChannel"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_TITLE = "Geolocation Tracker Service"
        private const val NOTIFICATION_TEXT = "Tracking geolocation..."
    }
}
