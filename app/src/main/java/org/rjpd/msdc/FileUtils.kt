package org.rjpd.msdc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import android.os.Environment
import android.text.format.Formatter
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
private const val UPLOAD_CACHE_DIR = "upload_cache"
private val datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.)\\w+\\.\\w+")
const val FILE_HEADER_SENSOR_ONE = "timestamp_nano,datetime_utc,name,axis_x,accuracy\n"
const val FILE_HEADER_SENSOR_THREE = "timestamp_nano,datetime_utc,name,axis_x,axis_y,axis_z,accuracy\n"
const val FILE_HEADER_SENSOR_THREE_UNCALIBRATED = "timestamp_nano,datetime_utc,name,axis_x,axis_y,axis_z,delta_x,delta_y,delta_z,accuracy\n"
const val FILE_HEADER_GPS = "datetime_utc,gps_interval,accuracy,latitude,longitude\n"
const val FILE_HEADER_CONSUMPTION = "datetime_utc,battery_microamperes\n"
const val FILE_HEADER_EXTERNAL_SENSOR = "datime_utc,sensor_value\n"
const val FILE_HEADER_CELLULAR_NETWORK = "datetime_utc,cellular_network\n"
const val FILE_HEADER_WIFI_NETWORK = "datetime_utc,wifi_network\n"

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
                val destFile = removeDateFromFilename(destDir, file.name)
                if (!file.renameTo(destFile)) {
                    file.copyTo(destFile, overwrite = true)
                    file.delete()
                }
            }
        }
    } else {
        val destFile = removeDateFromFilename(destDir, sourceDirOrFile.name)
        if (!sourceDirOrFile.renameTo(destFile)) {
            sourceDirOrFile.copyTo(destFile, overwrite = true)
            sourceDirOrFile.delete()
        }
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

fun isFilesListValid(files: MutableList<String>, isAudioVideoMode: Boolean): MutableMap<String, Any> {
    val notFoundFiles = mutableListOf<String>()
    val foundFiles = mutableListOf<String>()
    val validFilePatterns = mapOf(
        ".*sensors\\.one.csv" to "Sensor 1 axis",
        ".*sensors\\.three.csv" to "Sensor 3 axes",
        ".*sensors\\.three\\.uncalibrated.csv" to "Sensor 6 axes",
        ".*metadata\\.json" to "Metadata",
        ".*\\.mp4" to "Video",
        ".*wifi.csv" to "Wi-Fi",
        ".*cell.csv" to "Cellular",
        ".*gps.csv" to "GPS",
        ".*consumption.csv" to "Batery",
        ".*audio.*" to "Audio",
    )

    for ((pattern, name) in validFilePatterns) {
        val regex = Regex(pattern)
        val fileFound = files.any { regex.matches(it) }

        if (!fileFound) {
            notFoundFiles.add(name)
        } else {
            foundFiles.add(name)
        }
    }

    val validationResult: MutableMap<String, Any> = mutableMapOf(
        "foundFiles" to foundFiles,
        "notFoundFiles" to notFoundFiles
    )

    if (isAudioVideoMode && notFoundFiles.contains("Video")) {
        validationResult["isValid"] = false
        validationResult["errorMessage"] = "Video file is missing."
    } else if (!isAudioVideoMode && notFoundFiles.contains("Audio")) {
        validationResult["isValid"] = false
        validationResult["errorMessage"] = "Audio file is missing."
    } else {
        validationResult["isValid"] = true
        validationResult["errorMessage"] = ""
    }

    return validationResult
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
        "external_sensor" -> FILE_HEADER_EXTERNAL_SENSOR
        "sensor" -> headerMap.getOrDefault(filePostfix, FILE_HEADER_SENSOR_ONE)
        "wifi_network" -> FILE_HEADER_WIFI_NETWORK
        "cellular_network" -> FILE_HEADER_CELLULAR_NETWORK
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
        val newFilename = fileName.replaceFirst(matcher.group(1)!!, "")
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

fun writeWifiNetworkData(
    eventDateTimeUTC: DateTime,
    wifiNetworkData: Any,
    outputDir: String,
    filename: String,
) {
    for (wifi in wifiNetworkData as List<*>) {
        val line = "$eventDateTimeUTC,$wifi\n"

        try {
            val file = File(outputDir, "${filename}.wifi.csv")

            if (!file.exists()) {
                createFile(file, "wifi_network", "")
            }

            FileOutputStream(file, true).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(line)
                }
            }
        } catch (e: IOException) {
            Timber.tag(TAG).d(e, "Error writing wifi data to file.")
        }
    }
}

fun writeCellularNetworkData(
    eventDateTimeUTC: DateTime,
    cellularNetworkData: Any,
    outputDir: String,
    filename: String,
) {
    for (cn in cellularNetworkData as List<*>) {
        val line = "$eventDateTimeUTC,$cn\n"

        try {
            val file = File(outputDir, "${filename}.cell.csv")

            if (!file.exists()) {
                createFile(file, "cellular_network", "")
            }

            FileOutputStream(file, true).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(line)
                }
            }
        } catch (e: IOException) {
            Timber.tag(TAG).d(e, "Error writing cellular network data to file.")
        }
    }
}

fun writeMetadataFile(
    preferencesData: MutableMap<String, *>,
    displayMetrics: DisplayMetrics,
    sensorsData: Map<String, Any>,
    deviceStartAngle: String,
    buttonStartDateTime: DateTime,
    buttonStopDateTime: DateTime,
    mediaStartDateTime: DateTime,
    mediaStopDateTime: DateTime,
    outputDir: File,
    directory: String = "",
    subdirectory: String = ""
) {
    val datetimeFormatUTC = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    val metadata = mutableMapOf<String, Any>()
    metadata["preferences"] = preferencesData.toMutableMap()
    metadata["directory"] = directory.trim()
    metadata["subdirectory"] = subdirectory.trim()

    metadata["time"] = mutableMapOf(
        "buttonStartDateTime" to buttonStartDateTime.toString(datetimeFormatUTC),
        "buttonStopDateTime" to buttonStopDateTime.toString(datetimeFormatUTC),
        "mediaStartDateTime" to mediaStartDateTime.toString(datetimeFormatUTC),
        "mediaStopDateTime" to mediaStopDateTime.toString(datetimeFormatUTC),
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

    val metadataString = JSONObject((metadata as Map<*, *>?)!!).toString()
    Timber.tag(TAG).d(metadataString)

    try {
        val file = File(outputDir, "metadata.json")
        val writer = BufferedWriter(FileWriter(file, false))
        writer.write(metadataString)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

data class DatasetSummary(
    val name: String,
    val path: File,
    val isZip: Boolean,
    val sizeBytes: Long,
    val formattedSize: String,
    val lastModifiedMillis: Long,
    val fileCount: Int,
    val fileList: List<String>,
    val metadataMap: Map<String, Any>?,
    val directory: String = "",
    val subdirectory: String = "",
    val formattedDuration: String? = null,
    val durationMillis: Long = 0L
)

fun scanCollectedDatasets(context: Context): List<DatasetSummary> {
    val results = mutableListOf<DatasetSummary>()
    val processedPaths = mutableSetOf<String>()

    val roots = listOfNotNull(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.resolve("MultiSensorDC"),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.resolve("MultiSensorDC"),
        context.getExternalFilesDir("MultiSensorDC")
    )

    for (root in roots) {
        if (!root.exists() || !root.isDirectory) continue

        root.walkTopDown().forEach { file ->
            if (file.canonicalPath in processedPaths) return@forEach

            if (file.isFile && file.extension.lowercase() == "zip") {
                processedPaths.add(file.canonicalPath)
                val size = file.length()
                val formattedSize = Formatter.formatFileSize(context, size)
                val fileNames = mutableListOf<String>()
                var metadataJsonStr: String? = null

                try {
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            fileNames.add(entry.name)
                            if (entry.name.endsWith("metadata.json")) {
                                zip.getInputStream(entry).bufferedReader().use { reader ->
                                    metadataJsonStr = reader.readText()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error reading zip file: ${file.name}")
                }

                val metadataMap = parseJsonToMap(metadataJsonStr)
                val (dir, subdir) = extractDirAndSubdir(file, root, metadataMap)
                val durationMs = getCollectionDurationMillis(metadataMap)
                val durationStr = if (durationMs > 0) formatDurationMillis(durationMs) else null

                results.add(
                    DatasetSummary(
                        name = file.name,
                        path = file,
                        isZip = true,
                        sizeBytes = size,
                        formattedSize = formattedSize,
                        lastModifiedMillis = file.lastModified(),
                        fileCount = fileNames.size,
                        fileList = fileNames,
                        metadataMap = metadataMap,
                        directory = dir,
                        subdirectory = subdir,
                        formattedDuration = durationStr,
                        durationMillis = durationMs
                    )
                )
            } else if (file.isDirectory && file.resolve("metadata.json").exists()) {
                processedPaths.add(file.canonicalPath)
                val filesInside = file.walkTopDown().filter { it.isFile }.toList()
                filesInside.forEach { processedPaths.add(it.canonicalPath) }

                val size = filesInside.sumOf { it.length() }
                val formattedSize = Formatter.formatFileSize(context, size)
                val fileNames = filesInside.map { it.relativeTo(file).path }

                var metadataJsonStr: String? = null
                try {
                    metadataJsonStr = file.resolve("metadata.json").readText()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error reading metadata file: ${file.name}")
                }

                val metadataMap = parseJsonToMap(metadataJsonStr)
                val (dir, subdir) = extractDirAndSubdir(file, root, metadataMap)
                val durationMs = getCollectionDurationMillis(metadataMap)
                val durationStr = if (durationMs > 0) formatDurationMillis(durationMs) else null

                results.add(
                    DatasetSummary(
                        name = file.name,
                        path = file,
                        isZip = false,
                        sizeBytes = size,
                        formattedSize = formattedSize,
                        lastModifiedMillis = file.lastModified(),
                        fileCount = fileNames.size,
                        fileList = fileNames,
                        metadataMap = metadataMap,
                        directory = dir,
                        subdirectory = subdir,
                        formattedDuration = durationStr,
                        durationMillis = durationMs
                    )
                )
            }
        }
    }

    return results.sortedByDescending { it.lastModifiedMillis }
}

private fun extractDirAndSubdir(file: File, root: File, metadataMap: Map<String, Any>?): Pair<String, String> {
    var dir = metadataMap?.get("directory")?.toString()?.trim()
        ?: metadataMap?.get("dirEdittext")?.toString()?.trim()
        ?: ""
    var subdir = metadataMap?.get("subdirectory")?.toString()?.trim()
        ?: metadataMap?.get("subdirEdittext")?.toString()?.trim()
        ?: ""

    if (dir.isEmpty()) {
        val relativeParent = file.relativeToOrNull(root)?.parent
        if (relativeParent != null && relativeParent != ".") {
            dir = relativeParent.replace("\\", "/")
        }
    }

    if (subdir.isEmpty()) {
        val filename = file.nameWithoutExtension
        val timestampIndex = filename.indexOf("-20")
        if (timestampIndex > 0) {
            subdir = filename.substring(0, timestampIndex)
        }
    }

    return Pair(dir, subdir)
}

fun getCollectionDurationMillis(metadataMap: Map<String, Any>?): Long {
    if (metadataMap == null) return 0L

    return try {
        @Suppress("UNCHECKED_CAST")
        val timeMap = metadataMap["time"] as? Map<String, Any> ?: return 0L
        val startStr = timeMap["buttonStartDateTime"]?.toString() ?: return 0L
        val stopStr = timeMap["buttonStopDateTime"]?.toString() ?: return 0L

        val startMillis = DateTime.parse(startStr).millis
        val stopMillis = DateTime.parse(stopStr).millis
        val duration = stopMillis - startMillis
        if (duration > 0) duration else 0L
    } catch (_: Exception) {
        0L
    }
}

fun formatDurationMillis(durationMillis: Long): String {
    if (durationMillis <= 0) return "00:00"

    val seconds = (durationMillis / 1000) % 60
    val minutes = (durationMillis / (1000 * 60)) % 60
    val hours = durationMillis / (1000 * 60 * 60)

    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatCollectionDuration(metadataMap: Map<String, Any>?): String? {
    val durationMillis = getCollectionDurationMillis(metadataMap)
    if (durationMillis <= 0) return null
    return formatDurationMillis(durationMillis)
}

fun parseJsonToMap(jsonStr: String?): Map<String, Any>? {
    return jsonStr?.let {
        try {
            val jsonObject = JSONObject(it)
            jsonObjectToMap(jsonObject)
        } catch (_: Exception) {
            null
        }
    }
}

private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = jsonObject.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = jsonObject.get(key)
        if (value is JSONObject) {
            map[key] = jsonObjectToMap(value)
        } else {
            map[key] = value
        }
    }
    return map
}

fun prepareDatasetPreview(context: Context, datasetPath: File, isZip: Boolean): File {
    val cacheDir = File(context.cacheDir, "preview_cache/${datasetPath.nameWithoutExtension}")
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }

    if (isZip) {
        try {
            ZipFile(datasetPath).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val outFile = File(cacheDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error extracting preview zip: ${datasetPath.name}")
        }
        return cacheDir
    } else {
        return datasetPath
    }
}

data class GpsPoint(
    val datetimeUtc: String,
    val gpsInterval: Long,
    val accuracy: Float,
    val latitude: Double,
    val longitude: Double
)

fun parseGpsCsv(file: File): List<GpsPoint> {
    val points = mutableListOf<GpsPoint>()
    if (!file.exists() || !file.isFile) return points

    try {
        file.useLines { lines ->
            lines.drop(1).forEach { line ->
                val cols = line.split(",")
                if (cols.size >= 5) {
                    val lat = cols[3].trim().toDoubleOrNull()
                    val lon = cols[4].trim().toDoubleOrNull()
                    if (lat != null && lon != null) {
                        points.add(
                            GpsPoint(
                                datetimeUtc = cols[0].trim(),
                                gpsInterval = cols[1].trim().toLongOrNull() ?: 0L,
                                accuracy = cols[2].trim().toFloatOrNull() ?: 0f,
                                latitude = lat,
                                longitude = lon
                            )
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Error parsing GPS CSV file: ${file.name}")
    }

    return points.sortedBy { it.datetimeUtc }
}

fun getExportFilename(dataset: DatasetSummary): String {
    val baseName = if (dataset.path.name.endsWith(".zip", ignoreCase = true)) {
        dataset.path.nameWithoutExtension
    } else {
        dataset.path.name
    }

    return "$baseName.zip"
}

fun shareDatasetFile(context: Context, file: File) {
    if (!file.exists()) return

    val targetFile = if (file.isDirectory) {
        try {
            val cacheZip = File(context.cacheDir, "$UPLOAD_CACHE_DIR/${file.name}.zip")
            cacheZip.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(cacheZip)).use { zipOut ->
                file.walkTopDown().filter { it.isFile }.forEach { child ->
                    val relativePath = child.relativeTo(file).path
                    zipOut.putNextEntry(ZipEntry(relativePath))
                    child.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
            cacheZip
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error zipping dataset directory for sharing: ${file.name}")
            return
        }
    } else {
        file
    }

    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (targetFile.extension.lowercase() == "zip") "application/zip" else "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, context.getString(R.string.share_dataset_title))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Error sharing dataset file: ${file.name}")
    }
}

class RecoverableDeleteException(val intentSender: android.content.IntentSender) : Exception()

fun deleteDatasetFileOrFolder(context: Context, file: File): Boolean {
    if (!file.exists()) return true

    try {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Standard delete failed for ${file.name}")
    }

    if (!file.exists()) {
        cleanUpEmptyParentDirectories(file.parentFile)
        return true
    }

    try {
        deleteViaMediaStore(context, file)
    } catch (e: RecoverableDeleteException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "MediaStore delete failed for ${file.name}")
    }

    if (!file.exists()) {
        cleanUpEmptyParentDirectories(file.parentFile)
        return true
    }

    try {
        java.nio.file.Files.walk(file.toPath())
            .sorted(Comparator.reverseOrder())
            .forEach { path ->
                try {
                    java.nio.file.Files.deleteIfExists(path)
                } catch (_: Exception) {}
            }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "NIO delete failed for ${file.name}")
    }

    cleanUpEmptyParentDirectories(file.parentFile)

    val isDeleted = !file.exists()
    if (!isDeleted) {
        Timber.tag(TAG).e("Failed to delete file/folder: ${file.absolutePath}")
    }
    return isDeleted
}

private fun deleteViaMediaStore(context: Context, file: File) {
    val resolver = context.contentResolver
    val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
    val selection = "${android.provider.MediaStore.MediaColumns.DATA} = ?"
    val selectionArgs = arrayOf(file.absolutePath)

    val uris = mutableListOf(
        android.provider.MediaStore.Files.getContentUri("external"),
        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        uris.add(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
    }

    for (baseUri in uris) {
        try {
            resolver.query(baseUri, projection, selection, selectionArgs, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                    val deleteUri = android.content.ContentUris.withAppendedId(baseUri, id)
                    try {
                        resolver.delete(deleteUri, null, null)
                    } catch (e: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                            throw RecoverableDeleteException(e.userAction.actionIntent.intentSender)
                        }
                    }
                }
            }
        } catch (e: RecoverableDeleteException) {
            throw e
        } catch (_: Exception) {}
    }

    if (file.isDirectory) {
        val dirSelection = "${android.provider.MediaStore.MediaColumns.DATA} LIKE ?"
        val dirSelectionArgs = arrayOf("${file.absolutePath}/%")
        for (baseUri in uris) {
            try {
                resolver.delete(baseUri, dirSelection, dirSelectionArgs)
            } catch (e: RecoverableDeleteException) {
                throw e
            } catch (_: Exception) {}
        }
    }
}

private fun cleanUpEmptyParentDirectories(dir: File?) {
    var current = dir
    while (current != null && current.isDirectory && current.name != "MultiSensorDC") {
        val children = current.listFiles()
        if (children != null && children.isEmpty()) {
            val parent = current.parentFile
            current.delete()
            current = parent
        } else {
            break
        }
    }
}