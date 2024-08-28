package org.rjpd.msdc

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.annotation.RequiresApi
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
import androidx.core.app.ActivityCompat
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


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var infoUtils: InfoUtils
    private lateinit var timeUtils: TimeUtils

    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var recording: Recording? = null

    private lateinit var intentConsumptionService: Intent
    private lateinit var intentLocationTrackingService: Intent
    private lateinit var intentSensorsService: Intent
    private lateinit var intentSettings: Intent
    private lateinit var intentExternalSensorsService: Intent

    private lateinit var systemDataDirectory: File
    private lateinit var systemDataInstancePath: File
    private lateinit var downloadOutputDir: File
    private lateinit var userDataInstancePath: File
    private lateinit var mediaDataDirectoryCollecting: File
    private lateinit var tmpFilename: String
    private lateinit var buttonStartDateTime: DateTime
    private lateinit var buttonStopDateTime: DateTime
    private lateinit var videoStartDateTime: DateTime
    private lateinit var videoEndDateTime: DateTime

    private lateinit var angleDetectionService: AngleDetectionService

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        intentSensorsService = Intent(this@MainActivity, SensorsService::class.java)
        intentLocationTrackingService = Intent(this@MainActivity, LocationTrackingService::class.java)
        intentSettings = Intent(this@MainActivity, SettingsActivity::class.java)
        intentConsumptionService = Intent(this@MainActivity, ConsumptionService::class.java)
        intentExternalSensorsService = Intent(this@MainActivity, ExternalSensorsService::class.java)

        try {
            angleDetectionService = AngleDetectionService(this)
            angleDetectionService.create()
            angleDetectionService.start(viewBinding.angleTextview)
        } catch (_: java.lang.NullPointerException) {
            Timber.tag(TAG).d("Angle Detection Service is not supported on this device.")
        }

        if (allPermissionsGranted()){
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        viewBinding.startStopButton.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                viewBinding.settingsButton.isEnabled = false
                viewBinding.dirEdittext.isEnabled = false
                viewBinding.subdirEdittext.isEnabled = false
                viewBinding.externalSensorsButton.isEnabled = false
                viewBinding.startStopButton.backgroundTintList = getColorStateList(R.color.purple_200)
                angleDetectionService?.stop()
                startDataCollecting()
            } else {
                stopDataCollecting()
            }
        }

        viewBinding.settingsButton.setOnClickListener {
            startActivity(intentSettings)
        }

        viewBinding.externalSensorsButton.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                viewBinding.externalSensorsButton.backgroundTintList = getColorStateList(R.color.purple_200)

                tmpFilename = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(TimeUtils.getDateTimeUTC(System.currentTimeMillis()).toDate())
                systemDataInstancePath = createSubDirectory(systemDataDirectory.absolutePath, tmpFilename)
                intentExternalSensorsService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
                intentExternalSensorsService.putExtra("filename", tmpFilename)
                startService(intentExternalSensorsService)
            } else {
                viewBinding.externalSensorsButton.backgroundTintList = getColorStateList(R.color.red_700)
                viewBinding.externalSensorsButton.setTextColor(getColorStateList(R.color.white))
                stopService(intentExternalSensorsService)
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
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

                Timber.tag(TAG).d("A rotation was detected: $rotation")
                angleDetectionService?.setDevicePosition(rotation)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        orientationEventListener.disable()
        angleDetectionService?.stop()
        stopService(intentLocationTrackingService)
        stopService(intentSensorsService)
    }

    override fun onPause() {
        super.onPause()
        Timber.tag(TAG).d("A pause has been detected.")

        viewBinding.startStopButton.isEnabled = true
        viewBinding.startStopButton.text = getText(R.string.start)
        viewBinding.startStopButton.isChecked = false
        viewBinding.settingsButton.isEnabled = true
        viewBinding.dirEdittext.isEnabled = true
        viewBinding.subdirEdittext.isEnabled = true
        viewBinding.externalSensorsButton.isEnabled = true
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
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
                        videoStartDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
                        Timber.tag(TAG).d("The video camera recording has been initialized at $videoStartDateTime.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            videoEndDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())
                            Timber.tag(TAG).d("Data capture succeeded at $videoEndDateTime: ${recordEvent.outputResults.outputUri}.")
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
        timeUtils.startTimer()

        viewBinding.statusTextview.text = getString(R.string.status)

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

        intentSensorsService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentSensorsService.putExtra("filename", tmpFilename)
        intentLocationTrackingService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentLocationTrackingService.putExtra("filename", tmpFilename)
        intentConsumptionService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentConsumptionService.putExtra("filename", tmpFilename)
        intentExternalSensorsService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentExternalSensorsService.putExtra("filename", tmpFilename)

        if (sharedPreferences.getBoolean("sensors", true)) {
            startService(intentSensorsService)
        }

        if (sharedPreferences.getBoolean("gps", true)) {
            startService(intentLocationTrackingService)
        }

        if (sharedPreferences.getBoolean("consumption", true)) {
            startService(intentConsumptionService)
        }

        if (sharedPreferences.getBoolean("external_sensors", false)) {
            startService(intentExternalSensorsService)
        }

        startVideoRecording(tmpFilename)
    }

    private fun finishCollectionCheck(isValid: Boolean, filesSize:Int, deleteDirectory:Boolean, directory:File) {
        if (isValid) {
            viewBinding.statusTextview.text = buildString {
                append(getString(R.string.status_success))
                append("\n")
                append(getString(R.string.status_success_detail, "$filesSize"))
            }

            if (deleteDirectory) {
                Timber.tag(TAG).d("Deleting unzipped data.")
                directory.deleteRecursively()
            }
        } else {
            viewBinding.statusTextview.text = buildString {
                append(getString(R.string.status_error))
                append("\n")
                append(getString(R.string.status_error_detail, "$filesSize"))
            }
        }
    }

    private fun stopDataCollecting() {
        timeUtils.stopTimer()
        buttonStopDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())

        try {
            stopService(intentSensorsService)
            stopService(intentLocationTrackingService)
            stopService(intentConsumptionService)
            stopService(intentExternalSensorsService)
        } catch (e: Exception) {
            Timber.tag(TAG).d("Is was not possible to stop the SensorsService.")
        }

        try {
            recording?.stop()
            recording = null
        } catch (e: Exception) {
            Timber.tag(TAG).d("It was not possible to stop the Recording.")
        }

        Toast.makeText(
            this,
            "Organizing data...",
            Toast.LENGTH_SHORT
        ).show()

        CoroutineScope(Dispatchers.Main).launch {
            while (!mediaDataDirectoryCollecting.resolve("${tmpFilename}.video.mp4").exists()) {
                delay(500)
            }

            val moveJob = async(Dispatchers.IO) {
                moveContent(systemDataInstancePath, userDataInstancePath)
                moveContent(mediaDataDirectoryCollecting.resolve("${tmpFilename}.video.mp4"), userDataInstancePath)
            }

            val moveJobResult = moveJob.await()

            if (moveJobResult) {
                Toast.makeText(
                    this@MainActivity,
                    "Done",
                    Toast.LENGTH_SHORT
                ).show()

                generateMetadata()

                if (sharedPreferences.getBoolean("zip", false)) {
                    val zipTargetFilename = getZipTargetFilename(userDataInstancePath)

                    val zipJob = async(Dispatchers.IO) {
                        zipData(userDataInstancePath, zipTargetFilename)
                    }

                    val zipJobResult = zipJob.await()

                    if (zipJobResult) {
                        Timber.tag(TAG).d("Checking zip file.")
                        val files = listCompressedFiles(zipTargetFilename)
                        val isValidFilesList = isFilesListValid(files)
                        finishCollectionCheck(isValidFilesList, files.size, true, userDataInstancePath)
                    } else {
                        Timber.tag(TAG).d("The zip job is not ready.")
                    }
                } else {
                    Timber.tag(TAG).d("Checking directory.")
                    val files = listFiles(userDataInstancePath)
                    val isValidFilesList = isFilesListValid(files)
                    finishCollectionCheck(isValidFilesList, files.size, false, userDataInstancePath)
                }
                viewBinding.startStopButton.isEnabled = true
                viewBinding.settingsButton.isEnabled = true
                viewBinding.dirEdittext.isEnabled = true
                viewBinding.subdirEdittext.isEnabled = true
                viewBinding.externalSensorsButton.isEnabled = true
                viewBinding.startStopButton.backgroundTintList = getColorStateList(R.color.red_700)
                viewBinding.startStopButton.setTextColor(getColorStateList(R.color.white))
                angleDetectionService?.start(viewBinding.angleTextview)
            } else {
                Timber.tag(TAG).d("The move job is not ready.")
            }
        }
    }

    private fun generateMetadata() {
        Timber.tag(TAG).d("Generating metadata.")
        writeMetadataFile(
            sharedPreferences.all,
            resources.displayMetrics,
            infoUtils.getAvailableSensors(),
            viewBinding.angleTextview.text as String,
            buttonStartDateTime,
            buttonStopDateTime,
            videoStartDateTime,
            videoEndDateTime,
            userDataInstancePath
        )
    }

    private fun zipData (sourceFolder: File, targetZipFilename: String): Boolean {
        Timber.tag(TAG).d("Compacting data.")
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
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WAKE_LOCK,
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
