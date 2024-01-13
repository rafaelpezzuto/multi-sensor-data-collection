package org.rjpd.msdc

import org.junit.Test
import org.junit.Assert.*

class FileUtilsUnitTest {
    @Test
    fun generateInstanceNameNoSpace_isCorrect() {
        val instanceNameWithSpace = "sidewalk material"
        assertEquals("sidewalkmaterial", generateInstanceName(instanceNameWithSpace))
    }

    @Test
    fun generateInstanceNameNoDot_isCorrect() {
        val instanceNameWithDoubleQuotes = "category name"
        assertEquals("categoryname", generateInstanceName(instanceNameWithDoubleQuotes))
    }
}