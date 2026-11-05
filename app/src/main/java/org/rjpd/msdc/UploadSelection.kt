package org.rjpd.msdc

internal const val PREF_UPLOAD_DESTINATION_TYPE = "upload_destination_type"

/** Preserve the effective legacy destination once; an explicit choice never falls back elsewhere. */
internal fun resolveUploadSelection(
    savedType: String?,
    hasCompleteS3: Boolean,
    hasHttpServer: Boolean,
    hasStorageFolder: Boolean
): CloudSyncMode {
    if (savedType != null) {
        return CloudSyncMode.entries.firstOrNull { it.name == savedType } ?: CloudSyncMode.NOT_CONFIGURED
    }

    return when {
        hasCompleteS3 -> CloudSyncMode.S3
        hasHttpServer -> CloudSyncMode.HTTP_SERVER
        hasStorageFolder -> CloudSyncMode.SAF_STORAGE
        else -> CloudSyncMode.NOT_CONFIGURED
    }
}
