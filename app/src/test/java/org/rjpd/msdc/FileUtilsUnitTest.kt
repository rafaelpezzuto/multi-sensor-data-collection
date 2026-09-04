package org.rjpd.msdc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class FileUtilsUnitTest {
    @Test
    fun generateInstanceNameNoSpace_isCorrect() {
        val instanceNameWithSpace = "sidewalk material"
        assertEquals("SidewalkMaterial", generateInstanceName(instanceNameWithSpace))
    }

    @Test
    fun generateInstanceNameNoDot_isCorrect() {
        val instanceNameWithDoubleQuotes = "category name"
        assertEquals("CategoryName", generateInstanceName(instanceNameWithDoubleQuotes))
    }

    @Test
    fun detectFileHeader_gps() {
        val header = detectFileHeader("gps", "")
        assertEquals(FILE_HEADER_GPS, header)
    }

    @Test
    fun detectFileHeader_consumption() {
        val header = detectFileHeader("consumption", "")
        assertEquals(FILE_HEADER_CONSUMPTION, header)
    }

    @Test
    fun detectFileHeader_sensor_withValidPostfix() {
        val header = detectFileHeader("sensor", "three")
        assertEquals(headerMap.getValue("three"), header) // Assuming "three" exists in headerMap
    }

    @Test
    fun detectFileHeader_sensor_withInvalidPostfix() {
        val header = detectFileHeader("sensor", "invalid")
        assertEquals(FILE_HEADER_SENSOR_ONE, header)
    }

    @Test
    fun detectFileHeader_default() {
        val header = detectFileHeader("invalid", "")
        assertEquals(FILE_HEADER_SENSOR_ONE, header)
    }

    @Test
    fun extractSensorPostfixFilename_one() {
        val postfix = extractSensorPostfixFilename("123")
        assertEquals("one", postfix)
    }

    @Test
    fun extractSensorPostfixFilename_three() {
        val postfix = extractSensorPostfixFilename("123,456,789")
        assertEquals("three", postfix)
    }

    @Test
    fun extractSensorPostfixFilename_threeUncalibrated() {
        val postfix = extractSensorPostfixFilename("1,2,3,4,5,6")
        assertEquals("three.uncalibrated", postfix)
    }

    @Test
    fun extractSensorPostfixFilename_unknown() {
        val postfix = extractSensorPostfixFilename("1,2,3,4")
        assertEquals("unknown", postfix)
    }

    @Test
    fun getExportFilename_zipDataset() {
        val dataset = DatasetSummary(
            name = "SegmentOne-2026-09-03-20-00-00.zip",
            path = File("/mock/RouteAlpha/SegmentOne-2026-09-03-20-00-00.zip"),
            directory = "route alpha",
            subdirectory = "segment one",
            sizeBytes = 1024L,
            formattedSize = "1 KB",
            lastModifiedMillis = 0L,
            fileCount = 5,
            isZip = true,
            fileList = emptyList(),
            metadataMap = null
        )
        val exportFilename = getExportFilename(dataset)
        assertEquals("SegmentOne-2026-09-03-20-00-00.zip", exportFilename)
    }

    @Test
    fun getExportFilename_folderDataset() {
        val dataset = DatasetSummary(
            name = "SegmentOne-2026-09-03-20-00-00",
            path = File("/mock/RouteAlpha/SegmentOne-2026-09-03-20-00-00"),
            directory = "route alpha",
            subdirectory = "segment one",
            sizeBytes = 1024L,
            formattedSize = "1 KB",
            lastModifiedMillis = 0L,
            fileCount = 5,
            isZip = false,
            fileList = emptyList(),
            metadataMap = null
        )
        val exportFilename = getExportFilename(dataset)
        assertEquals("SegmentOne-2026-09-03-20-00-00.zip", exportFilename)
    }

    @Test
    fun generateInstancePath_emptyDirAndSubdir() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val result = generateInstancePath(tempDir, "2026-09-03-21-00-00", "", "")
            assertEquals(tempDir.resolve("2026-09-03-21-00-00").absolutePath, result.absolutePath)
            assertTrue(result.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun generateInstancePath_withDirAndSubdir() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val result = generateInstancePath(tempDir, "2026-09-03-21-00-00", "route alpha", "segment one")
            assertEquals(
                tempDir.resolve("RouteAlpha").resolve("SegmentOne-2026-09-03-21-00-00").absolutePath,
                result.absolutePath
            )
            assertTrue(result.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun generateInstancePath_withDirAndNoSubdir() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val result = generateInstancePath(tempDir, "2026-09-03-21-00-00", "route alpha", "")
            assertEquals(
                tempDir.resolve("RouteAlpha-2026-09-03-21-00-00").absolutePath,
                result.absolutePath
            )
            assertTrue(result.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractDirAndSubdir_withDirAndNoSubdir() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val datasetFile = tempDir.resolve("RouteAlpha-2026-09-03-21-00-00.zip")
            val metadata = mapOf<String, Any>(
                "directory" to "route alpha",
                "subdirectory" to ""
            )
            val (dir, subdir) = extractDirAndSubdir(datasetFile, tempDir, metadata)
            assertEquals("route alpha", dir)
            assertEquals("", subdir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractDirAndSubdir_withDirAndSubdir() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val subDir = tempDir.resolve("RouteAlpha")
            val datasetFile = subDir.resolve("SegmentOne-2026-09-03-21-00-00.zip")
            val metadata = mapOf<String, Any>(
                "directory" to "route alpha",
                "subdirectory" to "segment one"
            )
            val (dir, subdir) = extractDirAndSubdir(datasetFile, tempDir, metadata)
            assertEquals("route alpha", dir)
            assertEquals("segment one", subdir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractDirAndSubdir_noDirAndNoSubdir() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val datasetFile = tempDir.resolve("2026-09-03-21-00-00.zip")
            val metadata = mapOf<String, Any>(
                "directory" to "",
                "subdirectory" to ""
            )
            val (dir, subdir) = extractDirAndSubdir(datasetFile, tempDir, metadata)
            assertEquals("", dir)
            assertEquals("", subdir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractDirAndSubdir_legacyFileWithoutMetadata_rootLevel() {
        val tempDir = Files.createTempDirectory("test_msdc").toFile()
        try {
            val datasetFile = tempDir.resolve("RouteAlpha-2026-09-03-21-00-00.zip")
            val (dir, subdir) = extractDirAndSubdir(datasetFile, tempDir, null)
            assertEquals("RouteAlpha", dir)
            assertEquals("", subdir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}