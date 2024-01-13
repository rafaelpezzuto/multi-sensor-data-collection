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
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
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

    private lateinit var systemDataDirectory: File
    private lateinit var systemDataDirectoryCollecting: File
    private lateinit var downloadOutputDir: File
    private lateinit var downloadOutputDirCollecting: File
    private lateinit var mediaDataDirectoryCollecting: File
    private lateinit var filename: String
    private lateinit var buttonStartDateTime: DateTime
    private lateinit var buttonStopDateTime: DateTime
    private lateinit var videoStartDateTime: DateTime
    private lateinit var videoEndDateTime: DateTime

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
        setSpinner()

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        infoUtils = InfoUtils(this)
        timeUtils = TimeUtils(Handler(Looper.getMainLooper()), viewBinding.clockTextview)

        intentSensorsService = Intent(this@MainActivity, SensorsService::class.java)
        intentLocationTrackingService = Intent(this@MainActivity, LocationTrackingService::class.java)
        intentSettings = Intent(this@MainActivity, SettingsActivity::class.java)
        intentConsumptionService = Intent(this@MainActivity, ConsumptionService::class.java)

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
                viewBinding.exportButton.isEnabled = false
                viewBinding.startStopButton.backgroundTintList = getColorStateList(R.color.purple_200)
                startDataCollecting()
            } else {
                stopDataCollecting()
            }
        }

        viewBinding.settingsButton.setOnClickListener {
            startActivity(intentSettings)
        }

        viewBinding.exportButton.setOnClickListener {
            // ToDo: create exporting function
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
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
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
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
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

        viewBinding.statusTextview.text = ""

        val currentTimeMillis = System.currentTimeMillis()
        buttonStartDateTime = TimeUtils.getDateTimeUTC(currentTimeMillis)
        tmpFilename = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(buttonStartDateTime.toDate())

        userDataInstancePath = generateInstancePath(
            downloadOutputDir,
            viewBinding.categorySpinner.selectedItem.toString()
        )

        systemDataInstancePath = createSubDirectory(systemDataDirectory.absolutePath, tmpFilename)

        intentSensorsService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentSensorsService.putExtra("filename", tmpFilename)
        intentLocationTrackingService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentLocationTrackingService.putExtra("filename", tmpFilename)
        intentConsumptionService.putExtra("outputDirectory", systemDataInstancePath.absolutePath)
        intentConsumptionService.putExtra("filename", tmpFilename)

        if (sharedPreferences.getBoolean("sensors", true)) {
            startService(intentSensorsService)
        }

        if (sharedPreferences.getBoolean("gps", true)) {
            startService(intentLocationTrackingService)
        }

        if (sharedPreferences.getBoolean("consumption", true)) {
            startService(intentConsumptionService)
        }

        startVideoRecording(tmpFilename)
    }

    private fun stopDataCollecting() {
        timeUtils.stopTimer()
        buttonStopDateTime = TimeUtils.getDateTimeUTC(System.currentTimeMillis())

        try {
            stopService(intentSensorsService)
            stopService(intentLocationTrackingService)
            stopService(intentConsumptionService)
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

                val zipTargetFilename = getZipTargetFilename(userDataInstancePath)

                val zipJob = async(Dispatchers.IO){
                    zipData(userDataInstancePath, zipTargetFilename)
                }

                val zipJobResult = zipJob.await()

                if (zipJobResult) {
                    Timber.tag(TAG).d("Checking zip file.")
                    val files = listCompressedFiles(zipTargetFilename)
                    val isValidFilesList = isFilesListValid(files)

                    if (isValidFilesList) {
                        viewBinding.statusTextview.text = getString(R.string.success_number_of_files_saved, files.size.toString())

                        Timber.tag(TAG).d("Deleting unzipped data.")
                        downloadOutputDirCollecting.deleteRecursively()
                    } else {
                        viewBinding.statusTextview.text = getString(R.string.error_data_is_missing_number_of_files_saved, files.size.toString())
                    }

                    viewBinding.startStopButton.isEnabled = true
                    viewBinding.settingsButton.isEnabled = true
                    viewBinding.startStopButton.backgroundTintList = getColorStateList(R.color.red_700)
                } else {
                    Timber.tag(TAG).d("The zip job is not ready.")
                }

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
            viewBinding.categorySpinner.selectedItem.toString(),
            buttonStartDateTime,
            buttonStopDateTime,
            videoStartDateTime,
            videoEndDateTime,
            downloadOutputDirCollecting,
            filename,
        )
    }

    private fun setSpinner() {
        val items = resources.getStringArray(R.array.categories)

        val adapter = ArrayAdapter(this, R.layout.custom_spinner_item, items)
        adapter.setDropDownViewResource(R.layout.custom_spinner_item)

        val spinner = viewBinding.categorySpinner
        spinner.adapter = adapter
    }

    private fun zipData (sourceFolder: File, targetZipFilename: String): Boolean {
        Timber.tag(TAG).d("Compacting data.")
        zipEverything(sourceFolder, targetZipFilename)

        return true
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
