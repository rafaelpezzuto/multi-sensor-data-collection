package org.rjpd.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


fun writeGeolocationData(context: Context, latitude: Double, longitude: Double) {
    val localeDate = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())

    val line = "$localeDate,$latitude,$longitude\n"

    try {
        val file = File(context.getExternalFilesDir(null), "geo_data.txt")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun writeSensorData(context: Context, name: String?, axisData: String?, accuracy: Int?, timestamp: Long?) {
    val fmtDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.getDefault()).format(Date())

    val line = "$fmtDate,$name,$axisData,$accuracy,$timestamp\n"

    try {
        val file = File(context.getExternalFilesDir(null), "sensor_data.txt")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}
