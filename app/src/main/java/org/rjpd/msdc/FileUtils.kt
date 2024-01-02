package org.rjpd.msdc

import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import org.joda.time.DateTime
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


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
        Log.d("FileUtils", "$sourceDirOrFile does not exist")
        return false
    }

    if (!destDir.exists()) {
        destDir.mkdirs()
    }

    if (sourceDirOrFile.isDirectory) {
        val files = sourceDirOrFile.listFiles()
        for (file in files!!) {
            Log.d("FileUtils", "Moving file ${file.absolutePath}")
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
    val outputZipFile = File(targetZipFilename)

    ZipOutputStream(FileOutputStream(outputZipFile)).use { zipOutputStream ->
        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val entry = ZipEntry(sourceDir.toPath().relativize(file.toPath()).toString())

                zipOutputStream.putNextEntry(entry)

                file.inputStream().use { input ->
                    input.copyTo(zipOutputStream)
                }

                zipOutputStream.closeEntry()
            }
        }
    }
}

fun listCompressedFiles(zipFilepath: String): MutableList<String> {
    val zipFile = ZipFile(zipFilepath)
    val entries: Enumeration<out ZipEntry> = zipFile.entries()
    val files = mutableListOf<String>()

    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        files.add(entry.name)
        Log.d("FileUtils", "File ${entry.name} is in the ZIP")
    }

    zipFile.close()

    Log.d("FileUtils", "There are ${files.size} compressed files in the ZIP")
    return files
}

fun isFilesListValid(files: MutableList<String>): Boolean {
    val validFilePatterns = listOf(".*sensors\\.three.*", ".*metadata\\.json", ".*\\.mp4")

    for (pattern in validFilePatterns) {
        val regex = Regex(pattern)
        val fileFound = files.any { regex.matches(it) }

        if (!fileFound) {
            return false
        }
    }
    return true
}


fun writeGeolocationData(
    eventDateTimeUTC: DateTime,
    gpsInterval: String,
    accuracy: String,
    latitude: String,
    longitude: String,
    outputDir: String,
    filename: String,
) {
    val line = "$eventDateTimeUTC,$gpsInterval,$accuracy,$latitude,$longitude\n"

    try {
        val file = File(outputDir, "${filename}.gps.csv")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun extractSensorPosfixFilename(axisData: String): String{
    val nfields = axisData.split(",").size

    if (nfields == 1) {
        return "one"
    }

    if (nfields == 3){
        return "three"
    }

    if (nfields == 6) {
        return "three.uncalibrated"
    }

    return "unknown"
}

fun writeSensorData(
    eventTimestampNano: Long?,
    eventDateTimeUTC: DateTime,
    name: String?,
    axisData: String?,
    accuracy: Int?,
    outputDir: String,
    filename: String,
) {
    val line = "$eventTimestampNano,$eventDateTimeUTC,$name,$axisData,$accuracy\n"

    try {
        val filePosfix = extractSensorPosfixFilename(axisData!!)
        val file = File(outputDir, "$filename.sensors.$filePosfix.csv")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun writeConsumptionData(
    eventDateTimeUTC: DateTime,
    batteryStatus: Int,
    outputDir: String,
    filename: String,
) {
    val line = "$eventDateTimeUTC,$batteryStatus\n"

    try {
        val file = File(outputDir, "${filename}.consumption.csv")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

fun writeMetadataFile(
    preferencesData: MutableMap<String, *>,
    displayMetrics: DisplayMetrics,
    sensorsData: Map<String, Any>,
    cameraConfigurationsData: ArrayList<CameraConfiguration>,
    category: String,
    buttonStartDateTime: DateTime,
    buttonStopDatetime: DateTime,
    videoStartDateTime: DateTime,
    videoStopDateTime: DateTime,
    outputDir: File,
    filename: String,
) {
    val metadata = mutableMapOf<String, Any>()
    metadata["preferences"] = preferencesData.toMutableMap()

    metadata["button_start_datetime"] = buttonStartDateTime
    metadata["button_stop_datetime"] = buttonStopDatetime
    metadata["video_start_datetime"] = videoStartDateTime
    metadata["video_stop_datetime"] = videoStopDateTime

    metadata["category"] = category

    metadata["device"] = mutableMapOf(
        "model" to Build.MODEL,
        "manufacturer" to Build.MANUFACTURER,
        "android_version" to Build.VERSION.SDK_INT,
        "screen" to mutableMapOf(
            "screenWidthPixels" to displayMetrics.widthPixels,
            "screenHeightPixels" to displayMetrics.heightPixels,
            "screenDensity" to displayMetrics.density,
            "screenDpi" to displayMetrics.densityDpi,
        ),
        "sensors" to sensorsData.toMutableMap(),
        "cameras" to cameraConfigurationsData.map { it.toString() },
    )

    val metadataString = JSONObject(metadata as Map<*, *>?).toString()
    Log.d("FileUtils", metadataString)

    try {
        val file = File(outputDir, "${filename}.metadata.json")
        val writer = BufferedWriter(FileWriter(file, false))
        writer.write(metadataString)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}