package org.rjpd.msdc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import timber.log.Timber


class LocationTrackingService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var wakeLock: WakeLock
    private val channelID = "LocationTrackingServiceChannel"

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LocationTrackingService::WakelockTag"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10*60*1000L /*10 minutes*/)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates(intent)

        createNotificationChannel()
        val notification: Notification = Notification.Builder(this, channelID)
            .setContentTitle(TAG)
            .setContentText("Recording geolocation data")
            .build()

        startForeground(1, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val name = "LocationTrackingServiceChannel"
        val descriptionText = "LocationTrackingService notification channel"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(channelID, name, importance)
        channel.description = descriptionText

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
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
        private const val TAG = "LocationTrackingService"
    }
}
