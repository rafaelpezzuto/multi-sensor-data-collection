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

private const val TAG = "FileUtils"
private val datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.)\\w+\\.\\w+")


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
    val specialChars = setOf(
        ' ', '\\', '/', ':', '*', '?', '"', '<', '>', '|',
        '`', '~', '!', '@', '#', '$', '%', '^', '&', '(',
        ')', '{', '}', '[', ']', '+', '=', ',', ';'
    )

    return text.filter { it !in specialChars }
}

fun generateInstancePath(outputDir: File, category: String): File {
    val instanceName = generateInstanceName(category)

    var categoryPath = File(outputDir, instanceName)
    if (!categoryPath.exists()) {
        categoryPath = createSubDirectory(outputDir.absolutePath, instanceName)
    }

    var instanceNumber = 1
    var instancePathZipFile = File(categoryPath.absolutePath, "$instanceNumber.zip")

    while (instancePathZipFile.exists()) {
        instanceNumber++
        instancePathZipFile = File(categoryPath, "$instanceNumber.zip")
    }

    return createSubDirectory(categoryPath.absolutePath, "$instanceNumber")
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

fun extractSensorPostfixFilename(axisData: String): String{
    val numberOfFields = axisData.split(",").size

    if (numberOfFields == 1) {
        return "one"
    }

    if (numberOfFields == 3){
        return "three"
    }

    if (numberOfFields == 6) {
        return "three.uncalibrated"
    }

    return "unknown"
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
    category: String,
    tags: String,
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

    metadata["category"] = category

    if (tags.isNotEmpty()) {
        metadata["tags"] = tags.split(", ")
    } else {
        metadata["tags"] = emptyList<String>()
    }

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