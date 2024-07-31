package org.rjpd.msdc

import android.os.Build
import android.util.DisplayMetrics
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.util.Enumeration
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.joda.time.DateTime
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter

private const val TAG = "FileUtils"
private val datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.)\\w+\\.\\w+")
const val FILE_HEADER_SENSOR_ONE = "timestamp_nano,datetime_utc,name,axis_x,accuracy\n"
const val FILE_HEADER_SENSOR_THREE = "timestamp_nano,datetime_utc,name,axis_x,axis_y,axis_z,accuracy\n"
const val FILE_HEADER_SENSOR_THREE_UNCALIBRATED = "timestamp_nano,datetime_utc,name,axis_x,axis_y,axis_z,delta_x,delta_y,delta_z,accuracy\n"
const val FILE_HEADER_GPS = "datetime_utc,gps_interval,accuracy,latitude,longitude\n"
const val FILE_HEADER_CONSUMPTION = "datetime_utc,battery_microamperes\n"
val headerMap = mapOf(
    "one" to FILE_HEADER_SENSOR_ONE,
    "three" to FILE_HEADER_SENSOR_THREE,
    "three.uncalibrated" to FILE_HEADER_SENSOR_THREE_UNCALIBRATED
)

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
        Timber.tag(TAG).d("$sourceDirOrFile does not exist.")
        return false
    }

    if (!destDir.exists()) {
        destDir.mkdirs()
    }

    if (sourceDirOrFile.isDirectory) {
        val files = sourceDirOrFile.listFiles()
        for (file in files!!) {
            Timber.tag(TAG).d("Moving file ${file.absolutePath}.")
            if (file.isDirectory) {
                moveContent(file, File(destDir, file.name))
            } else {
                file.renameTo(removeDateFromFilename(destDir, file.name))
            }
        }
    } else {
        sourceDirOrFile.renameTo(removeDateFromFilename(destDir, sourceDirOrFile.name))
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
        Timber.tag(TAG).d("File ${entry.name} is in the ZIP.")
    }

    zipFile.close()

    Timber.tag(TAG).d("There are ${files.size} compressed files in the ZIP.")
    return files
}

fun listFiles(directory: File): MutableList<String> {
    val fileList = mutableListOf<String>()

    if (directory.exists() && directory.isDirectory) {
        val files = directory.listFiles()

        files?.let {
            for (file in it) {
                fileList.add(file.name)
            }
        }
    }

    return fileList
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

fun generateInstanceName(text: String): String {
    val titledText = text.lowercase().split(" ").joinToString(" ") { it ->
        it.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(
            Locale.ROOT
        ) else it.toString()
    } }

    val specialChars = setOf(
        ' ', '\\', '/', ':', '*', '?', '"', '<', '>', '|',
        '`', '~', '!', '@', '#', '$', '%', '^', '&', '(',
        ')', '{', '}', '[', ']', '+', '=', ',', ';'
    )

    return titledText.filter { it !in specialChars }
}

fun generateInstancePath(outputDir: File, tempFileName: String, levelOne: String, levelTwo: String): File {
    val levelOneName = generateInstanceName(levelOne)
    val levelTwoName = generateInstanceName(levelTwo)

    if (levelOneName.isEmpty() && levelTwoName.isEmpty()) {
        return createSubDirectory(outputDir.absolutePath, tempFileName)
    }

    if (levelOneName.isNotEmpty() && levelTwoName.isEmpty()) {
        return createSubDirectory(outputDir.absolutePath, "$levelOneName-${tempFileName}")
    }

    if (levelOneName.isEmpty() && levelTwoName.isNotEmpty()) {
        return createSubDirectory(outputDir.absolutePath, "$levelTwoName-${tempFileName}")
    }

    return createSubDirectory(createSubDirectory(outputDir.absolutePath, levelOneName).absolutePath, "$levelTwoName-${tempFileName}")
}

fun createFile(file: File, fileContentType: String, filePostfix: String) {
    val header = detectFileHeader(fileContentType, filePostfix)

    try {
        FileOutputStream(file, true).use { fos ->
            OutputStreamWriter(fos).use { writer ->
                writer.append(header)
            }
        }
    } catch (e: IOException) {
        Timber.tag(TAG).d(e.toString())
    }
}

fun detectFileHeader(fileContentType: String, filePostfix: String): String {
    return when (fileContentType) {
        "gps" -> FILE_HEADER_GPS
        "consumption" -> FILE_HEADER_CONSUMPTION
        "sensor" -> headerMap.getOrDefault(filePostfix, FILE_HEADER_SENSOR_ONE)
        else -> FILE_HEADER_SENSOR_ONE
    }
}

fun extractSensorPostfixFilename(axisData: String): String{
    val numberOfFields = axisData.split(",").size
    return when (numberOfFields) {
        1 -> "one"
        3 -> "three"
        6 -> "three.uncalibrated"
        else ->"unknown"
    }
}

fun removeDateFromFilename(destDir: File, fileName: String): File {
    val matcher = datePattern.matcher(fileName)
    return if (matcher.find()) {
        val newFilename = fileName.replaceFirst(matcher.group(1), "")
        Timber.tag(TAG).d("$fileName changed to $newFilename")

        File(destDir, newFilename)
    } else {
        File(destDir, fileName)
    }
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
        val filePostfix = extractSensorPostfixFilename(axisData!!)
        val file = File(outputDir, "$filename.sensors.$filePostfix.csv")

        if (!file.exists()) {
            createFile(file, "sensor", filePostfix)
        }

        FileOutputStream(file, true).use { fos ->
            OutputStreamWriter(fos).use { writer ->
                writer.write(line)
            }
        }

    } catch (e: IOException) {
        Timber.tag(TAG).d(e, "Error writing sensor data to file.")
    }
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

        if (!file.exists()) {
            createFile(file, "gps", "")
        }

        FileOutputStream(file, true).use { fos ->
            OutputStreamWriter(fos).use { writer ->
                writer.write(line)
            }
        }
    } catch (e: IOException) {
        Timber.tag(TAG).d(e, "Error writing geolocation data to file.")
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

        if (!file.exists()) {
            createFile(file, "consumption", "")
        }

        FileOutputStream(file, true).use { fos ->
            OutputStreamWriter(fos).use { writer ->
                writer.write(line)
            }
        }
    } catch (e: IOException) {
        Timber.tag(TAG).d(e, "Error writing consumption data to file.")
    }
}

fun writeMetadataFile(
    preferencesData: MutableMap<String, *>,
    displayMetrics: DisplayMetrics,
    sensorsData: Map<String, Any>,
    deviceStartAngle: String,
    buttonStartDateTime: DateTime,
    buttonStopDatetime: DateTime,
    videoStartDateTime: DateTime,
    videoStopDateTime: DateTime,
    outputDir: File
) {
    val datetimeFormatUTC = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    val metadata = mutableMapOf<String, Any>()
    metadata["preferences"] = preferencesData.toMutableMap()

    metadata["time"] = mutableMapOf(
        "buttonStartDateTime" to buttonStartDateTime.toString(datetimeFormatUTC),
        "buttonStopDatetime" to buttonStopDatetime.toString(datetimeFormatUTC),
        "videoStartDateTime" to videoStartDateTime.toString(datetimeFormatUTC),
        "videoStopDateTime" to videoStopDateTime.toString(datetimeFormatUTC),
    )

    metadata["deviceStartAngle"] = deviceStartAngle

    metadata["device"] = mutableMapOf(
        "model" to Build.MODEL,
        "manufacturer" to Build.MANUFACTURER,
        "androidVersion" to Build.VERSION.SDK_INT,
        "screen" to mutableMapOf(
            "screenWidthPixels" to displayMetrics.widthPixels,
            "screenHeightPixels" to displayMetrics.heightPixels,
            "screenDensity" to displayMetrics.density,
            "screenDpi" to displayMetrics.densityDpi,
        ),
        "sensors" to sensorsData.toMutableMap(),
    )

    val metadataString = JSONObject(metadata as Map<*, *>?).toString()
    Timber.d(TAG, metadataString)

    try {
        val file = File(outputDir, "metadata.json")
        val writer = BufferedWriter(FileWriter(file, false))
        writer.write(metadataString)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}