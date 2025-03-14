package org.rjpd.msdc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import timber.log.Timber


class AudioRecorderService: Service() {
    private var recorder: MediaRecorder? = null

    private var filename = ""
    private var outputDir = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording(intent)
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
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
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TEXT)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
    }

    @SuppressLint("NewApi")
    fun startRecording(intent: Intent?) {
        outputDir = intent?.extras!!.getString("outputDirectory", "")
        filename = intent.extras!!.getString("filename", "")

        var audioSamplingRate = intent.extras!!.getInt("audioSamplingRate", 44100)
        var audioChannels = intent.extras!!.getInt("audioChannels", 2)
        var audioEncodingBitRate = intent.extras!!.getInt("audioEncodingBitRate", 128000)

        recorder = MediaRecorder(applicationContext)
        recorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder?.setAudioSamplingRate(audioSamplingRate)
        recorder?.setAudioChannels(audioChannels)
        recorder?.setAudioEncodingBitRate(audioEncodingBitRate)

        recorder?.setOutputFile("$outputDir/$filename.audio.m4a")

        try {
            recorder?.prepare()
            recorder?.start()
        } catch (e: Exception) {
            Timber.tag(TAG).e("Error starting recording: $e")
        }
    }

    fun stopRecording() {
        if (recorder != null) {
            recorder!!.stop()
            recorder!!.release()
            recorder = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val TAG = "AudioRecorderService"
        private const val CHANNEL_ID = "AudioRecorderServiceChannel"
        private const val NOTIFICATION_ID = 5
        private const val NOTIFICATION_TITLE = "Audio Recorder Service"
        private const val NOTIFICATION_TEXT = "Recording audio..."
        const val ACTION_START_RECORDING = "START_RECORDING"
        const val ACTION_STOP_RECORDING = "STOP_RECORDING"
    }
}
