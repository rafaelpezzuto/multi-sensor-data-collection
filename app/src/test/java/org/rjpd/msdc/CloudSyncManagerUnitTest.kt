package org.rjpd.msdc

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncManagerUnitTest {
    @Test
    fun preferencesForMetadata_dropsSecretsAndSyncState() {
        val all = mapOf(
            "gps" to true,
            "camera_fps" to 30,
            PREF_CUSTOM_UPLOAD_URL to "https://example.org/upload",
            PREF_CUSTOM_UPLOAD_TOKEN to "secret-token",
            PREF_SYNCED_DATASET_NAMES to setOf("a", "b")
        )
        assertEquals(
            mapOf("gps" to true, "camera_fps" to 30, PREF_CUSTOM_UPLOAD_URL to "https://example.org/upload"),
            preferencesForMetadata(all)
        )
    }
}
