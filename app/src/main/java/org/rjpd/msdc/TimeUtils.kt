package org.rjpd.msdc

import android.os.Handler
import android.widget.TextView
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

class TimeUtils(private val mainHandler: Handler, private val clockView: TextView) {

    private var startTime: Long = 0
    private var isRunning = false

    private lateinit var timer: Timer

    fun startTimer() {
        if (!isRunning) {
            timer = Timer()
            startTime = System.currentTimeMillis()
            isRunning = true

            timer.scheduleAtFixedRate(object: TimerTask() {
                override fun run() { updateTimer() }
            }, 0, 1000)
        }
    }

    fun stopTimer() {
        if (isRunning) {
            isRunning = false

            timer.cancel()
            timer.purge()
        }
    }

    private fun updateTimer() {
        if (isRunning) {
            val elapsedTime = System.currentTimeMillis() - startTime
            val seconds = (elapsedTime / 1000).toInt()
            val minutes = seconds / 60
            val hours = minutes / 60

            mainHandler.post {
                val formattedSeconds = seconds % 60
                clockView.text = String.format(
                    Locale.getDefault(), "%02d:%02d:%02d",
                    hours,
                    minutes,
                    formattedSeconds,
                )
            }
        }
    }
}

fun getDateTimeUTC(systemCurrentTimeMillis: Long): DateTime {
    return DateTime(systemCurrentTimeMillis, DateTimeZone.UTC)
}

fun getDateTimeUTC(systemCurrentTimeMillis: Long, timestampNano: Long, elapsedRealtimeNano: Long): DateTime {
    val systemClockElapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNano)
    val sensorEventTimeStampMillis = TimeUnit.NANOSECONDS.toMillis(timestampNano)
    val currentMinusElapsedRealtimeMillis = systemCurrentTimeMillis - systemClockElapsedRealtimeMillis
    val actualEventTimeMillis = currentMinusElapsedRealtimeMillis + sensorEventTimeStampMillis

    return DateTime(actualEventTimeMillis, DateTimeZone.UTC)
}
