package org.rjpd.msdc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import androidx.preference.PreferenceManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import timber.log.Timber


class USBConnectedSensorsService : Service() {
    private lateinit var scheduledExecutor: ScheduledExecutorService
    private var isMonitoring = false

    private lateinit var usbManager: UsbManager
    private lateinit var usbConnection: UsbDeviceConnection
    private var usbSerialPort: UsbSerialPort? = null

    private var filename = ""
    private var outputDir = ""
    private var interval = 10

    private fun findAndConnectUSBDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Timber.tag(TAG).d("No USB serial devices found.")
            return
        }

        val driver = availableDrivers.firstOrNull()
        usbConnection = usbManager.openDevice(driver?.device)
        usbSerialPort = driver?.ports?.firstOrNull()

        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            Timber.tag(TAG).d("Connected to USB device: ${driver?.device?.deviceName}.")
        } catch (e: Exception) {
            Timber.tag(TAG).e("Error setting up USB serial port: ${e.message}.")
            usbSerialPort?.close()
            usbSerialPort = null
        }
    }

    private fun readSensorData(interval: Int): Pair<String, Boolean> {
        usbSerialPort?.let { port ->
            try {
                val buffer = ByteArray(100)
                val data = port.read(buffer, interval)
                if (data > 0) {
                    return Pair(String(buffer.copyOf(data), Charsets.UTF_8), true)
                } else {
                    Timber.tag(TAG).d("No data read from sensor.")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e("Error reading from USB device: ${e.message}.")
            }
        }
        return Pair("", false)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        if (!isMonitoring) {
            startMonitoring(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            NOTIFICATION_TEXT,
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
        Timber.tag(TAG).d("Monitoring external sensors and writing data to file $filename.")
        isMonitoring = true

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        interval = sharedPreferences.getInt("external_sensors_interval", 15)

        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent.extras!!.getString("filename", "")

        findAndConnectUSBDevice()

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        scheduledExecutor.scheduleWithFixedDelay({
            val currentDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
            val (sensorValue, isValid) = readSensorData(interval)
            if (isValid) {
                Timber.tag(TAG).d("$currentDateTime,$sensorValue")
                writeUSBConnectedSensorData(currentDateTime, sensorValue, outputDir, filename)
            } else {
                Timber.tag(TAG).d("$currentDateTime: No valid sensor data received.")
            }
        }, 0, interval.toLong(), TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        scheduledExecutor.shutdown()
    }

    companion object {
        private const val TAG = "USBConnectedSensorsService"
        private const val CHANNEL_ID = "USBConnectedSensorsServiceChannel"
        private const val NOTIFICATION_ID = 4
        private const val NOTIFICATION_TITLE = "USB-Connected Sensors Service"
        private const val NOTIFICATION_TEXT = "Monitoring USB-connected sensors..."
    }
}