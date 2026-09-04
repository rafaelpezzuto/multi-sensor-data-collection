package org.rjpd.msdc

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

private const val PREF_CLOUD_STORAGE_FOLDER = "cloud_storage_folder"

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val treeUri = result.data?.data
                if (treeUri != null) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    CloudSyncManager.setSafStorageUri(requireContext(), treeUri)
                    updateCloudFolderSummary()
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.user_preferences, rootKey)

            val infoUtils = InfoUtils(requireContext())
            setCameras(infoUtils)

            val aboutPreference = findPreference<Preference>("about")
            aboutPreference?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent(activity, AboutActivity::class.java)
                startActivity(intent)
                true
            }

            findPreference<Preference>("camera_lens_facing_use_front")?.setOnPreferenceChangeListener { _, _ ->
                restartActivity()
                true
            }

            findPreference<Preference>("camera_resolution")?.setOnPreferenceChangeListener { _, _ ->
                restartActivity()
                true
            }

            findPreference<Preference>(PREF_CUSTOM_UPLOAD_URL)?.setOnPreferenceChangeListener { _, newValue ->
                val newUrl = (newValue as? String)?.trim() ?: ""
                val oldUrl = CloudSyncManager.getCustomUploadUrl(requireContext())
                if (newUrl != oldUrl) {
                    CloudSyncManager.clearSyncedDatasetNames(requireContext())
                }
                true
            }

            setupCloudStorageFolderPreference()
        }

        override fun onResume() {
            super.onResume()
            updateCloudFolderSummary()
        }

        private fun setupCloudStorageFolderPreference() {
            val folderPref = findPreference<Preference>(PREF_CLOUD_STORAGE_FOLDER)
            folderPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val treeUri = CloudSyncManager.getSafStorageUri(requireContext())
                if (treeUri != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.cloud_storage_folder_title)
                        .setItems(arrayOf(getString(R.string.dialog_change_folder), getString(R.string.dialog_clear_folder))) { _, which ->
                            if (which == 0) {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                }
                                folderPickerLauncher.launch(intent)
                            } else {
                                CloudSyncManager.setSafStorageUri(requireContext(), null)
                                updateCloudFolderSummary()
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                    folderPickerLauncher.launch(intent)
                }
                true
            }
            updateCloudFolderSummary()
        }

        private fun updateCloudFolderSummary() {
            val folderPref = findPreference<Preference>(PREF_CLOUD_STORAGE_FOLDER) ?: return
            val treeUri = CloudSyncManager.getSafStorageUri(requireContext())
            if (treeUri != null) {
                val folderDoc = DocumentFile.fromTreeUri(requireContext(), treeUri)
                val name = folderDoc?.name ?: treeUri.lastPathSegment ?: treeUri.toString()
                folderPref.summary = "Selected: $name\n(Tap to change or remove)"
            } else {
                folderPref.summary = getString(R.string.cloud_storage_folder_not_set)
            }
        }

        private fun restartActivity() {
            val intent = Intent(requireContext(), MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            requireActivity().finish()
        }

        private fun setCameras(infoUtils: InfoUtils) {
            val cameraConfigurations = infoUtils.getAvailableCameraConfigurations()

            val cameraPreference = findPreference<ListPreference>("camera")
            cameraPreference?.entries = cameraConfigurations.map { it.getLabel() }.toTypedArray()
            cameraPreference?.entryValues = cameraConfigurations.map { it.getUniqueId() }.toTypedArray()

            cameraPreference?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                val sharedPreferences = preference.sharedPreferences
                val editor = sharedPreferences?.edit()

                val cameraConfigurationSelected = cameraConfigurations.find{it.getUniqueId() == newValue as String}
                editor?.putString("camera_selected", cameraConfigurationSelected!!.getUniqueId())
                editor?.putString("camera_id", cameraConfigurationSelected!!.cameraId)
                editor?.putInt("camera_fps", cameraConfigurationSelected!!.averageFps)
                editor?.putInt("camera_resolution_width", cameraConfigurationSelected!!.resolutionWidth)
                editor?.putInt("camera_resolution_height", cameraConfigurationSelected!!.resolutionHeight)
                editor?.putInt("camera_lens_facing", cameraConfigurationSelected!!.lensFacing)

                preference.summary = cameraConfigurationSelected!!.getLabel()
                editor?.apply()

                true
            }

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)
            if (sharedPreferences.getString("camera", null) == null) {
                try {
                    val cameraIndex = cameraConfigurations.indexOfFirst {
                        it.resolutionWidth == 1920
                                && it.resolutionHeight == 1080
                                && it.averageFps == 30
                                && it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
                    }
                    cameraPreference?.setValueIndex(cameraIndex)
                } catch (_: Exception) {
                    cameraPreference?.setValueIndex(0)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}