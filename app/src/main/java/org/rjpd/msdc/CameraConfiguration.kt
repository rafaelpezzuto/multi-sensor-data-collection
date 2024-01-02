package org.rjpd.msdc

import android.hardware.camera2.CameraCharacteristics

class CameraConfiguration(val cameraId: String, val resolutionWidth: Int, val resolutionHeight: Int, val averageFps: Int, val lensFacing: Int) {
    private val cameraBack = "Back"
    private val cameraFront = "Front"

    fun getLabel() : String {
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return "${cameraId}: $cameraFront - ${resolutionWidth}x${resolutionHeight}@${averageFps}"
        }
        return "${cameraId}: $cameraBack - ${resolutionWidth}x${resolutionHeight}@${averageFps}"
    }
    fun getUniqueId(): String {
        return "${cameraId}_${lensFacing}_${resolutionWidth}x${resolutionHeight}@${averageFps}"
    }

    override fun toString(): String {
        return getLabel()
    }
}