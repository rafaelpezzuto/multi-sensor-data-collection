package org.rjpd.data

import android.hardware.camera2.CameraCharacteristics

class CameraConfiguration(
    val cameraId: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val averageFps: Int,
    val lensFacing: Int
) {
    val CAMERA_BACK = "Back"
    val CAMERA_FRONTAL = "Frontal"

    fun getLabel() : String {
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return "${cameraId}: $CAMERA_FRONTAL - ${resolutionWidth}x${resolutionHeight}@${averageFps}"
        }
        return "${cameraId}: $CAMERA_BACK - ${resolutionWidth}x${resolutionHeight}@${averageFps}"
    }
    fun getUniqueId(): String {
        return "${cameraId}_${lensFacing}_${resolutionWidth}x${resolutionHeight}@${averageFps}"
    }
}