package org.rjpd.data

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


fun createSubDirectory(rootDirectory: String, subDirectory: String): File {
    val directory = File(
        rootDirectory,
        subDirectory
    )
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return directory
}

fun moveContent(sourceDirOrFile: File, destDir: File): Boolean {
    if (!sourceDirOrFile.exists()) {
        Log.d("MultiSensorDataCollector", "$sourceDirOrFile does not exist")
        return false
    }

    if (!destDir.exists()) {
        destDir.mkdirs()
    }

    if (sourceDirOrFile.isDirectory) {
        val files = sourceDirOrFile.listFiles()
        for (file in files) {
            Log.d("MultiSensorDataCollector", "Moving file ${file.absolutePath}")
            val destFile = File(destDir, file.name)
            if (file.isDirectory) {
                moveContent(file, destFile)
            } else {
                file.renameTo(destFile)
            }
        }
    } else {
        val destFile = File(destDir, sourceDirOrFile.name)
        sourceDirOrFile.renameTo(destFile)
    }

    return true
}

fun getZipTargetFilename(currentOutputDir: File): String {
    val s = currentOutputDir.parent
    val f = currentOutputDir.name

    val destinationZipFile = File(s, "${f}.zip")

    return destinationZipFile.absolutePath
}

fun zipEverything(sourceDir: File, targetZipFilename: String) {
    // ToDo: method responsible for compressing everything inside the directory sourceDir
}

fun writeGeolocationData(
    gpsInterval: String,
    accuracy: String,
    latitude: String,
    longitude: String,
    outputDir: String,
    filename: String,
) {
    val localeDate = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())

    val line = "$localeDate,$gpsInterval,$accuracy,$latitude,$longitude\n"

    try {
        val file = File(outputDir, "${filename}.gps.csv")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun writeSensorData(
    name: String?,
    axisData: String?,
    accuracy: Int?,
    timestamp: Long?,
    filename: String?,
) {
    val fmtDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.getDefault()).format(Date())

    val line = "$fmtDate,$name,$axisData,$accuracy,$timestamp\n"

    try {
        val writer = BufferedWriter(FileWriter(filename, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun getDeviceInfo(): JSONObject {
    val deviceInfo = JSONObject()
    deviceInfo.put("DeviceModel", Build.MODEL)
    deviceInfo.put("FirmwareVersion", Build.VERSION.RELEASE)
    deviceInfo.put("SoftwareVersion", Build.VERSION.SDK_INT)
    deviceInfo.put("AndroidVersion", Build.VERSION.RELEASE)
    Log.d("SensorsService", "$deviceInfo")
    return deviceInfo
}

fun getSensorInfo(sensorManager: SensorManager): JSONArray {
    val sensorArray = JSONArray()
    for (sensor in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
        val sensorObj = JSONObject()
        sensorObj.put("Sensor Name", sensor.name)
        sensorObj.put("Type", sensor.type)
        sensorObj.put("Device Manufacturer", sensor.vendor)
        sensorObj.put("Version", sensor.version)
        sensorObj.put("Resolution", sensor.resolution)
        sensorObj.put("Power", sensor.power)

        sensorArray.put(sensorObj)
        Log.d("SensorsService", "$sensorObj")
    }
    return sensorArray
}

fun saveInfoToJson(file: String, info: JSONObject) {
    try {
        val file = File(file, "metadata.json")
        val fileWriter = FileWriter(file)
        fileWriter.write(info.toString())
        fileWriter.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}
