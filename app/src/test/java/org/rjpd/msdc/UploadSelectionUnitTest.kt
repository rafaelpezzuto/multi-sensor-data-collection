package org.rjpd.msdc

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadSelectionUnitTest {
    @Test
    fun legacySelection_preservesEffectivePriority() {
        assertEquals(CloudSyncMode.S3, resolveUploadSelection(null, true, true, true))
        assertEquals(CloudSyncMode.HTTP_SERVER, resolveUploadSelection(null, false, true, true))
        assertEquals(CloudSyncMode.SAF_STORAGE, resolveUploadSelection(null, false, false, true))
        assertEquals(CloudSyncMode.NOT_CONFIGURED, resolveUploadSelection(null, false, false, false))
    }

    @Test
    fun explicitSelection_neverFallsBackToAnotherConfiguredMethod() {
        assertEquals(CloudSyncMode.S3, resolveUploadSelection("S3", false, true, true))
        assertEquals(CloudSyncMode.HTTP_SERVER, resolveUploadSelection("HTTP_SERVER", true, false, true))
        assertEquals(CloudSyncMode.SAF_STORAGE, resolveUploadSelection("SAF_STORAGE", true, true, false))
    }

    @Test
    fun explicitOff_staysOffEvenWithSavedConnections() {
        assertEquals(CloudSyncMode.NOT_CONFIGURED, resolveUploadSelection("NOT_CONFIGURED", true, true, true))
    }

    @Test
    fun unknownOrEmptySelection_doesNotActivateLegacyFallback() {
        assertEquals(CloudSyncMode.NOT_CONFIGURED, resolveUploadSelection("unavailable", true, true, true))
        assertEquals(CloudSyncMode.NOT_CONFIGURED, resolveUploadSelection("", true, true, true))
    }

    @Test
    fun savedMigration_isUnaffectedByLaterConfigurationChanges() {
        val migrated = resolveUploadSelection(null, false, true, true)
        assertEquals(CloudSyncMode.HTTP_SERVER, resolveUploadSelection(migrated.name, true, false, true))
    }
}
