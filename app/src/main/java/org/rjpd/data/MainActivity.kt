package org.rjpd.data

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rjpd.data.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var intentLocationTrackingService: Intent
    private lateinit var intentSensorsService: Intent

    private lateinit var systemDataDirectory: File
    private lateinit var systemDataDirectoryCollecting: File
    private lateinit var downloadOutputDir: File
    private lateinit var downloadOutputDirCollecting: File
    private lateinit var mediaDataDirectoryCollecting: File
    private lateinit var filename: String

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        systemDataDirectory = getExternalFilesDir("MultiSensorDC")!!
        downloadOutputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("MultiSensorDC")
        mediaDataDirectoryCollecting = Environment.getExternalStorageDirectory().resolve("Movies/MultiSensorDC/")

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        intentSensorsService = Intent(this@MainActivity, SensorsService::class.java)
        intentLocationTrackingService = Intent(this@MainActivity, LocationTrackingService::class.java)

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
                filename = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis())

                systemDataDirectoryCollecting = createSubDirectory(systemDataDirectory.absolutePath, filename)
                downloadOutputDirCollecting = createSubDirectory(downloadOutputDir.absolutePath, filename)

                intentSensorsService.putExtra("outputDirectory", systemDataDirectoryCollecting.absolutePath)
                intentSensorsService.putExtra("filename", filename)
                intentLocationTrackingService.putExtra("outputDirectory", systemDataDirectoryCollecting.absolutePath)
                intentLocationTrackingService.putExtra("filename", filename)

                if (sharedPreferences.getBoolean("sensors", false)) {
                    startService(intentSensorsService)
                }

                if (sharedPreferences.getBoolean("gps", false)) {
                    startService(intentLocationTrackingService)
                }

                captureVideo(filename)
                updateButtonsState()
            } else {
                if (sharedPreferences.getBoolean("sensors", false)) {
                    stopService(intentSensorsService)
                }

                if (sharedPreferences.getBoolean("gps", false)) {
                    stopService(intentLocationTrackingService)
                }

                recording?.stop()

                Toast.makeText(
                    this,
                    "Preparing data for exporting...",
                    Toast.LENGTH_SHORT
                ).show()

                CoroutineScope(Dispatchers.Main).launch {
                    viewBinding.startStopButton.isEnabled = false
                    while (!mediaDataDirectoryCollecting.resolve("${filename}.mp4").exists()) {
                        delay(500)
                    }
                    moveContent(systemDataDirectoryCollecting, downloadOutputDirCollecting)
                    moveContent(
                        mediaDataDirectoryCollecting.resolve("${filename}.mp4"),
                        downloadOutputDirCollecting
                    )
                    updateButtonsState()
                    viewBinding.startStopButton.isEnabled = true

                    Toast.makeText(
                        this@MainActivity,
                        "Done",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        viewBinding.settingsButton.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }

        viewBinding.exportButton.setOnClickListener {
            // ToDo: compactar diretorio downloadOutputDirCollecting
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopService(intentLocationTrackingService)
        stopService(intentSensorsService)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture,
                )
            } catch (exc: Exception) {
                Log.d(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureVideo(filename: String) {
        val videoCapture = this.videoCapture ?: return

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
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
                        viewBinding.startStopButton.apply {
                            text = getString(R.string.stop)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Data capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }

                        viewBinding.startStopButton.apply {
                            text = getString(R.string.start)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun updateButtonsState() {
        viewBinding.exportButton.isEnabled = !viewBinding.exportButton.isEnabled
        viewBinding.settingsButton.isEnabled = !viewBinding.settingsButton.isEnabled
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
        private const val TAG = "MultiSensorDataCollector"
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
