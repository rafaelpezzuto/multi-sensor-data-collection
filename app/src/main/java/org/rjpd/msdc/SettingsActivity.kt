package org.rjpd.msdc

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

internal const val EXTRA_OPEN_UPLOAD_SETTINGS = "open_upload_settings"

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.settings_toolbar))

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
            if (intent.getBooleanExtra(EXTRA_OPEN_UPLOAD_SETTINGS, false)) {
                openFragment(UploadSettingsFragment(), Bundle())
            }
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentClass = pref.fragment ?: return false
        openFragment(supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass), pref.extras)
        return true
    }

    fun openFragment(fragment: Fragment, args: Bundle) {
        fragment.arguments = args
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

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
        }

        override fun onResume() {
            super.onResume()
            (activity as? SettingsActivity)?.title = getString(R.string.title_activity_settings)
            findPreference<Preference>("upload_settings")?.summary = uploadSettingsSummary(requireContext())
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