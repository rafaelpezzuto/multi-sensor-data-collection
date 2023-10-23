package org.rjpd.msdc

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.user_preferences, rootKey)

            val infoUtils = InfoUtils(requireContext())
            val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            setCameras(cameraManager, infoUtils)

            val aboutPreference = findPreference<Preference>("about")
            aboutPreference?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent(activity, AboutActivity::class.java)
                startActivity(intent)
                true
            }
        }

        private fun setCameras(cameraManager: CameraManager, infoUtils: InfoUtils) {
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
                } catch (exc: Exception) {
                    cameraPreference?.setValueIndex(0)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}