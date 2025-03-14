package org.rjpd.msdc

import org.junit.Test
import org.junit.Assert.*


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
}