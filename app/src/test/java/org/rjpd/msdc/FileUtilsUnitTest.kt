package org.rjpd.msdc

import org.junit.Test
import org.junit.Assert.*

class FileUtilsUnitTest {
    @Test
    fun generateFilenameNoSpace_isCorrect() {
        val filenameWithSpace = "sidewalk material"
        assertEquals("sidewalkmaterial", generateFilename(filenameWithSpace))
    }

    @Test
    fun generateFilenameNoDot_isCorrect() {
        val filenameWithDoubleQuotes = "category name"
        assertEquals("categoryname", generateFilename(filenameWithDoubleQuotes))
    }
}