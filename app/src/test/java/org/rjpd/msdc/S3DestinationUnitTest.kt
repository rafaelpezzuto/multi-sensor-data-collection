package org.rjpd.msdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class S3DestinationUnitTest {
    private val destination = S3Destination(
        id = "profile", endpoint = "https://s3.example.org", bucket = "first"
    )

    @Test
    fun syncState_isScopedToStorageLocationAndLayout() {
        for (changed in listOf(
            destination.copy(endpoint = "https://other.example.org"),
            destination.copy(region = "eu-west-1"),
            destination.copy(bucket = "second"),
            destination.copy(pathStyle = false),
            destination.copy(keyTemplate = "new/{dataset}"),
            destination.copy(zipMode = true),
            destination.copy(id = "another-profile")
        )) {
            assertNotEquals(destination.syncStateId, changed.syncStateId)
        }
    }

    @Test
    fun syncState_survivesCosmeticAndTransferChanges() {
        val changed = destination.copy(name = "Renamed", partSizeMib = 32, overwrite = true)
        assertEquals(destination.syncStateId, changed.syncStateId)
        assertEquals(destination.syncStateId, destination.copy(bucket = "other").copy(bucket = "first").syncStateId)
    }
}
