package org.rjpd.msdc

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import org.rjpd.msdc.databinding.ActivityMainBinding
import timber.log.Timber
import kotlin.toString
import androidx.core.view.isVisible


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var infoUtils: InfoUtils
    private lateinit var timeUtils: TimeUtils

    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var recording: Recording? = null

    private lateinit var intentSensorsService: Intent
    private lateinit var intentGeolocationTrackerService: Intent
    private lateinit var intentBatteryMonitorService: Intent
    private lateinit var intentWiFiNetworkScanService: Intent
    private lateinit var intentCellularNetworkScanService: Intent
    private lateinit var intentAudioRecorderService: Intent
    private lateinit var intentSettings: Intent

    private lateinit var services: List<Pair<Intent, String>>

    private lateinit var systemDataDirectory: File
    private lateinit var systemDataInstancePath: File
    private lateinit var downloadOutputDir: File
    private lateinit var userDataInstancePath: File
    private lateinit var mediaDataDirectoryCollecting: File
    private lateinit var tmpFilename: String
    private lateinit var buttonStartDateTime: DateTime
    private lateinit var buttonStopDateTime: DateTime
    private lateinit var mediaStartDateTime: DateTime
    private lateinit var mediaStopDateTime: DateTime

    private lateinit var deviceAngleDetectorService: DeviceAngleDetectorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!allPermissionsGranted()) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        systemDataDirectory = getExternalFilesDir("MultiSensorDC")!!
        downloadOutputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("MultiSensorDC")
        mediaDataDirectoryCollecting = Environment.getExternalStorageDirectory().resolve("Movies/MultiSensorDC/")

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        infoUtils = InfoUtils(this)
        timeUtils = TimeUtils(Handler(Looper.getMainLooper()), viewBinding.clockTextview)

        intentSettings = Intent(this@MainActivity, SettingsActivity::class.java)

        intentSensorsService = Intent(this@MainActivity, SensorsService::class.java)
        intentGeolocationTrackerService = Intent(this@MainActivity, GeolocationTrackerService::class.java)
        intentBatteryMonitorService = Intent(this@MainActivity, BatteryMonitorService::class.java)
        intentWiFiNetworkScanService = Intent(this@MainActivity, WiFiNetworkScanService::class.java)
        intentCellularNetworkScanService = Intent(this@MainActivity, CellularNetworkScanService::class.java)

        intentAudioRecorderService = Intent(this@MainActivity, AudioRecorderService::class.java)

        services = listOf(
            intentSensorsService to "sensors",
            intentGeolocationTrackerService to "gps",
            intentBatteryMonitorService to "consumption",
            intentWiFiNetworkScanService to "wifi_network",
            intentCellularNetworkScanService to "cell_network",
        )

        try {
            deviceAngleDetectorService = DeviceAngleDetectorService(this)
            deviceAngleDetectorService.create()
            deviceAngleDetectorService.start(viewBinding.angleTextview)
        } catch (_: java.lang.NullPointerException) {
            Timber.tag(TAG).d("Device Angle Detector Service is not supported on this device.")
        }

        viewBinding.recordingModeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_audio_video -> {
                    viewBinding.microphone.visibility = android.view.View.INVISIBLE
                    viewBinding.radioAudioVideo.isChecked = true
                    viewBinding.radioAudio.isChecked = false
                    startCamera()
                }
                R.id.radio_audio -> {
                    viewBinding.microphone.visibility = android.view.View.VISIBLE
                    viewBinding.radioAudioVideo.isChecked = false
                    viewBinding.radioAudio.isChecked = true
                    stopCamera()
                }
            }
        }

        viewBinding.startStopButton.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                startDataCollecting()
            } else {
                stopDataCollecting()
            }
        }

        viewBinding.settingsButton.setOnClickListener {
            startActivity(intentSettings)
        }

        if (viewBinding.radioAudioVideo.isChecked) {
            startCamera()
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        orientationEventListener.enable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        orientationEventListener.disable()
        deviceAngleDetectorService?.stop()

        for ((intent, _) in services) {
            stopService(intent)
        }
    }

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return
                }

                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                videoCapture?.targetRotation = rotation
                preview?.targetRotation = rotation
                deviceAngleDetectorService.setDevicePosition(rotation)
            }
        }
    }

   private fun lockScreenOrientation() {
       val rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
       requestedOrientation = when (rotation) {
           Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
           Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
           Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
           Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
           else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
       }
   }

   private fun unlockScreenOrientation() {
       requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
   }

    private fun startCamera() {
        viewBinding.viewFinder.visibility = android.view.View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = viewBinding.viewFinder.surfaceProvider
                }

            val camQuality = infoUtils.stringToCamQuality(
                sharedPreferences.getString("camera_resolution", "3")
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        camQuality,
                        FallbackStrategy.higherQualityOrLowerThan(camQuality))
                ).build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector: CameraSelector =
                if (sharedPreferences.getBoolean("camera_lens_facing_use_front", false)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture,
                )
            } catch (exc: Exception) {
                Timber.tag(TAG).d(exc, "Use case binding failed.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))

        cameraExecutor.shutdown()
        preview?.surfaceProvider = null
        viewBinding.viewFinder.visibility = android.view.View.INVISIBLE
    }

    private fun startVideoRecording(filename: String) {
        val videoCapture = this.videoCapture ?: return

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$filename.video")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MultiSensorDC")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        mediaStartDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
                        Timber.tag(TAG).d("Data capture started at $mediaStartDateTime.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            mediaStopDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
                            Timber.tag(TAG).d("Data capture succeeded at $mediaStopDateTime: ${recordEvent.outputResults.outputUri}.")
                        } else {
                            recording?.close()
                            recording = null
                            Timber.tag(TAG).e("Video capture ends with error: ${recordEvent.error}")
                        }
                        viewBinding.startStopButton.isEnabled = false
                    }
                }
            }
    }

    private fun startDataCollecting() {
        lockScreenOrientation()
        disableInterfaceElements()

        timeUtils.startTimer()

        val currentTimeMillis = System.currentTimeMillis()
        buttonStartDateTime = TimeUtils.getDateTimeUTC(currentTimeMillis)
        tmpFilename = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(buttonStartDateTime.toDate())

        userDataInstancePath = generateInstancePath(
            downloadOutputDir,
            tmpFilename,
            viewBinding.dirEdittext.text.toString(),
            viewBinding.subdirEdittext.text.toString(),
        )

        systemDataInstancePath = createSubDirectory(systemDataDirectory.absolutePath, tmpFilename)

        services.forEach { (intent, name) ->
            if (sharedPreferences.getBoolean(name, true)) {
                intent.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
                intent.putExtra("filename", tmpFilename)
                startForegroundService(intent)
            }
        }

        if (viewBinding.radioAudioVideo.isChecked) {
           startVideoRecording(tmpFilename)
        } else {
            intentAudioRecorderService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
            intentAudioRecorderService.putExtra("filename", tmpFilename)

            mediaStartDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())

            intentAudioRecorderService.putExtra("audioSamplingRate", sharedPreferences.getString("audio_sampling_rate", "44100")!!.toInt())
            intentAudioRecorderService.putExtra("audioChannels", sharedPreferences.getString("audio_channels", "2")!!.toInt())
            intentAudioRecorderService.putExtra("audioEncodingBitRate", sharedPreferences.getString("audio_encoding_bit_rate", "128000")!!.toInt())

            intentAudioRecorderService.action = AudioRecorderService.ACTION_START_RECORDING
            startForegroundService(intentAudioRecorderService)
        }
    }

    private fun finishCollectionCheck(isValid: Boolean, filesSize:Int, deleteDirectory:Boolean, directory:File) {
        if (isValid) {
            viewBinding.statusTextview.text = buildString {
                append(getString(R.string.status_success, "$filesSize"))
            }

            if (deleteDirectory) {
                Timber.tag(TAG).d("Deleting unzipped data.")
                directory.deleteRecursively()
            }
        } else {
            viewBinding.statusTextview.text = buildString {
                append(getString(R.string.status_error))
            }
        }
    }

    private fun stopDataCollecting() {
        timeUtils.stopTimer()
        buttonStopDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())

        services.forEach { (intent, name) ->
            if (sharedPreferences.getBoolean(name, true)) {
                stopService(intent)
            }
        }

        if (viewBinding.radioAudioVideo.isChecked) {
            recording?.stop()
            recording = null
        } else {
            intentAudioRecorderService.action = AudioRecorderService.ACTION_STOP_RECORDING
            startService(intentAudioRecorderService)

            mediaStopDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
        }

        viewBinding.recordingTextview.text = getString(R.string.recording_status_stopped)
        viewBinding.statusTextview.visibility = android.view.View.VISIBLE
        viewBinding.statusTextview.text = getString(R.string.status_organizing_data)

        CoroutineScope(Dispatchers.Main).launch {
            if (viewBinding.radioAudioVideo.isChecked) {
                while (!mediaDataDirectoryCollecting.resolve("${tmpFilename}.video.mp4").exists()) {
                    delay(500)
                }
                val moveJobVideo = async(Dispatchers.IO) {
                    moveContent(
                        mediaDataDirectoryCollecting.resolve("${tmpFilename}.video.mp4"),
                        userDataInstancePath
                    )
                }

                val moveJobVideoResult = moveJobVideo.await()
                if (moveJobVideoResult) {
                    viewBinding.statusTextview.text = getString(R.string.status_move_video_file)
                }
            }

            val moveJob = async(Dispatchers.IO) {
                moveContent(systemDataInstancePath, userDataInstancePath)
            }

            val moveJobResult = moveJob.await()

            if (moveJobResult) {
                viewBinding.statusTextview.text = getString(R.string.status_move_other_files)

                generateMetadata()

                if (sharedPreferences.getBoolean("zip", false)) {
                    viewBinding.statusTextview.text = getString(R.string.status_compressing_data)

                    val zipTargetFilename = getZipTargetFilename(userDataInstancePath)

                    val zipJob = async(Dispatchers.IO) {
                        zipData(userDataInstancePath, zipTargetFilename)
                    }

                    val zipJobResult = zipJob.await()

                    if (zipJobResult) {
                        Timber.tag(TAG).d("Checking zip file.")
                        val files = listCompressedFiles(zipTargetFilename)
                        val isValidFilesList = isFilesListValid(files, viewBinding.radioAudioVideo.isChecked)
                        finishCollectionCheck(isValidFilesList, files.size, true, userDataInstancePath)
                    } else {
                        Timber.tag(TAG).d("The zip job is not ready.")
                    }
                } else {
                    Timber.tag(TAG).d("Checking directory.")
                    val files = listFiles(userDataInstancePath)
                    val isValidFilesList = isFilesListValid(files, viewBinding.radioAudioVideo.isChecked)
                    finishCollectionCheck(isValidFilesList, files.size, false, userDataInstancePath)
                }

                unlockScreenOrientation()
                enableInterfaceElements()

            } else {
                Timber.tag(TAG).d("The move job is not ready.")
            }
        }
    }

    private fun disableInterfaceElements(){
        deviceAngleDetectorService?.stop()

        viewBinding.settingsButton.isEnabled = false
        viewBinding.startStopButton.backgroundTintList = getColorStateList(R.color.purple_200)
        viewBinding.outputAndRecordingModeSettingsLinearLayout.visibility = android.view.View.INVISIBLE

        viewBinding.recordingTextview.text = getString(R.string.recording_status_recording)
        viewBinding.statusTextview.text = getString(R.string.status)
        viewBinding.statusTextview.visibility = android.view.View.INVISIBLE
    }

    private fun enableInterfaceElements() {
        deviceAngleDetectorService?.start(viewBinding.angleTextview)

        viewBinding.settingsButton.isEnabled = true
        viewBinding.startStopButton.backgroundTintList = getColorStateList(R.color.red_700)
        viewBinding.startStopButton.setTextColor(getColorStateList(R.color.white))
        viewBinding.startStopButton.isEnabled = true
        viewBinding.outputAndRecordingModeSettingsLinearLayout.visibility = android.view.View.VISIBLE
        viewBinding.statusTextview.visibility = android.view.View.VISIBLE
    }

    private fun generateMetadata() {
        Timber.tag(TAG).d("Generating metadata...")
        writeMetadataFile(
            sharedPreferences.all,
            resources.displayMetrics,
            infoUtils.getAvailableSensors(),
            viewBinding.angleTextview.text as String,
            buttonStartDateTime,
            buttonStopDateTime,
            mediaStartDateTime,
            mediaStopDateTime,
            userDataInstancePath
        )
    }

    private fun zipData(sourceFolder: File, targetZipFilename: String): Boolean {
        Timber.tag(TAG).d("Compacting data...")
        return try {
            zipEverything(sourceFolder, targetZipFilename)
            true
        } catch (e: FileNotFoundException) {
            Timber.tag(TAG).e(e, "Output file not found.")
            false
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "I/O error occurred.")
            false
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Permission denied.")
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error occurred.")
            false
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()){
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private const val TAG = "MultiSensorDataCollection"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 33
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CAMERA,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
    }
}
