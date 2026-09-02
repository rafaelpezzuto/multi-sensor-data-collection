package org.rjpd.msdc

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.camera.video.Quality
import timber.log.Timber

import kotlin.math.roundToInt

private const val TAG = "InfoUtils"

class InfoUtils(context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun getEstimatedRecordingMinutes(
        availableBytes: Long,
        isAudioOnly: Boolean,
        cameraResolutionSetting: String?,
        audioBitRateSetting: String?
    ): String {
        if (availableBytes <= 0) return "~0 min"

        val audioBitRate = audioBitRateSetting?.toLongOrNull() ?: 128000L
        val sensorOverheadBps = 50000L

        val totalBitRateBps = if (isAudioOnly) {
            audioBitRate + sensorOverheadBps
        } else {
            val videoBitRateBps = when (cameraResolutionSetting) {
                "1" -> 2_500_000L
                "2" -> 5_000_000L
                "3" -> 12_000_000L
                "4" -> 35_000_000L
                else -> 12_000_000L
            }
            videoBitRateBps + audioBitRate + sensorOverheadBps
        }

        val bytesPerSecond = totalBitRateBps / 8.0
        if (bytesPerSecond <= 0) return "N/A"

        val remainingSeconds = (availableBytes / bytesPerSecond).toLong()
        val remainingMinutes = remainingSeconds / 60

        return if (remainingMinutes < 60) {
            "~$remainingMinutes min"
        } else {
            val hours = remainingMinutes / 60
            "~${hours}h"
        }
    }

    fun getAvailableStorageSpaceFormatted(
        isAudioOnly: Boolean,
        cameraResolutionSetting: String?,
        audioBitRateSetting: String?
    ): String {
        return try {
            val path = Environment.getExternalStorageDirectory().path
            val statFs = StatFs(path)
            val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
            val freeGb = (availableBytes / (1024.0 * 1024.0 * 1024.0)).roundToInt()
            val timeEst = getEstimatedRecordingMinutes(
                availableBytes,
                isAudioOnly,
                cameraResolutionSetting,
                audioBitRateSetting
            )
            "$freeGb GB ($timeEst)"
        } catch (_: Exception) {
            "N/A"
        }
    }

    fun stringToCamQuality(camQuality: String?): Quality {
        return when(camQuality) {
            "1" -> Quality.SD
            "2" -> Quality.HD
            "3" -> Quality.FHD
            "4" -> Quality.UHD
            else -> {
                Quality.LOWEST
            }
        }
    }

    fun getAvailableSensors(): Map<String, Any> {
        val data = mutableMapOf<String, Any>()

        for (sensor in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            data[sensor.name] = mutableMapOf(
                "type" to sensor.type,
                "id" to sensor.id,
                "min_delay" to sensor.minDelay,
                "max_delay" to sensor.maxDelay,
                "resolution" to sensor.resolution,
                "power" to sensor.power,
                "version" to sensor.version,
            )
        }
        return data
    }

    fun getAvailableCameraConfigurations(): ArrayList<CameraConfiguration> {
        val cameraConfigurations = ArrayList<CameraConfiguration>()

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(
                    SurfaceTexture::class.java
                )

            val fpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

            for (i in sizes!!.indices) {
                val size = sizes[i]
                val width = size.width
                val height = size.height

                try {
                    val fpsRange = fpsRanges?.get(i)
                    val averageFps = (fpsRange!!.lower + fpsRange.upper) / 2

                    val cc = CameraConfiguration(
                        cameraId = cameraId,
                        resolutionWidth = width,
                        resolutionHeight = height,
                        averageFps = averageFps,
                        lensFacing = lensFacing!!
                    )

                    cameraConfigurations.add(cc)
                    Timber.tag(TAG).d(cc.getLabel())

                } catch (exc: ArrayIndexOutOfBoundsException) {
                    Timber.tag(TAG).e("Exception: $exc")
                }
            }
        }
        return cameraConfigurations
    }
}