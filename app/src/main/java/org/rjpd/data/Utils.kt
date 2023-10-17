package org.rjpd.data

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
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

fun zipFolder(sourceFolder: File, destinationZipFile: File) {
    val fos = FileOutputStream(destinationZipFile)
    val zos = ZipOutputStream(fos)
    zos.setMethod(ZipOutputStream.DEFLATED)

    zipDirectory(sourceFolder, sourceFolder, zos)

    zos.close()
    fos.close()
}

fun zipDirectory(baseDir: File, sourceFolder: File, zos: ZipOutputStream) {
    val files = sourceFolder.listFiles() ?: return

    for (file in files) {
        if (file.isDirectory) {
            zipDirectory(baseDir, File(sourceFolder, file.name), zos)
        } else {
            val entry = ZipEntry(baseDir.toURI().relativize(file.toURI()).path)
            zos.putNextEntry(entry)

            val fileInputStream = file.inputStream()
            fileInputStream.copyTo(zos)
            fileInputStream.close()
            zos.closeEntry()
        }
    }
}

fun getZipTargetFile(currentOutputDir: File): File {
    val s = currentOutputDir.parent
    val f = currentOutputDir.name

    val destinationZipFile = File(s, "${f}.zip")

    return destinationZipFile
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
    outputDir: String,
    filename: String,
) {
    val fmtDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.getDefault()).format(Date())

    val line = "$fmtDate,$name,$axisData,$accuracy,$timestamp\n"

    try {
        val file = File(outputDir, "${filename}.sensors.csv")
        val writer = BufferedWriter(FileWriter(file, true))
        writer.append(line)
        writer.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}
