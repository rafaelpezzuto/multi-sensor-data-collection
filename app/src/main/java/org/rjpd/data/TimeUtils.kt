package org.rjpd.data

import android.widget.TextView
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class TimeUtils(private val clockView: TextView) {

    private var startTime: Long = 0
    private var isRunning = false

    private lateinit var timer: Timer

    fun startTimer() {
        if (!isRunning) {
            timer = Timer()
            startTime = System.currentTimeMillis()
            isRunning = true

            timer.scheduleAtFixedRate(object: TimerTask() {
                override fun run() {updateTimer() }
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

            clockView.text = String.format(
                Locale.getDefault(), "%02d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        }
    }
}