package org.rjpd.msdc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val PREF_CLOUD_STORAGE_FOLDER = "cloud_storage_folder"
private val UPLOAD_PREFERENCES_REQUIRING_REFRESH = setOf(
    PREF_UPLOAD_DESTINATION_TYPE, PREF_ACTIVE_S3_DESTINATION, PREF_CUSTOM_UPLOAD_URL,
    PREF_CUSTOM_UPLOAD_TOKEN, PREF_AUTO_UPLOAD, PREF_UPLOAD_WIFI_ONLY, PREF_UPLOAD_CHARGING_ONLY
)

internal fun uploadSettingsSummary(context: Context): String {
    val mode = CloudSyncManager.selectedUploadMode(context)
    val label = context.getString(
        when (mode) {
            CloudSyncMode.HTTP_SERVER -> R.string.upload_destination_http
            CloudSyncMode.SAF_STORAGE -> R.string.upload_destination_folder
            CloudSyncMode.S3 -> R.string.upload_destination_s3
            CloudSyncMode.NOT_CONFIGURED -> R.string.upload_destination_none
        }
    )

    if (mode == CloudSyncMode.NOT_CONFIGURED) return label
    if (CloudSyncManager.getCloudSyncMode(context) == CloudSyncMode.NOT_CONFIGURED) {
        return context.getString(R.string.upload_home_incomplete, label)
    }

    val destination = if (mode == CloudSyncMode.S3) {
        CloudSyncManager.getActiveS3Destination(context)?.displayName ?: label
    } else {
        label
    }
    val automatic = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO_UPLOAD, false)

    return context.getString(
        R.string.upload_home_summary, destination,
        context.getString(if (automatic) R.string.upload_auto_enabled else R.string.upload_auto_disabled)
    )
}

class UploadSettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && canChangeDestination()) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                CloudSyncManager.setSafStorageUri(requireContext(), uri)
                refreshDestination()
                SyncWorker.schedule(requireContext(), replace = true)
            } catch (_: SecurityException) {
                Toast.makeText(requireContext(), R.string.upload_folder_permission_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Read and persist the effective legacy choice before preferences can initialize any defaults.
        CloudSyncManager.selectedUploadMode(requireContext())
        setPreferencesFromResource(R.xml.upload_preferences, rootKey)

        for (key in listOf(PREF_UPLOAD_DESTINATION_TYPE, PREF_ACTIVE_S3_DESTINATION)) {
            findPreference<ListPreference>(key)?.setOnPreferenceChangeListener { _, _ -> canChangeDestination() }
        }

        findPreference<EditTextPreference>(PREF_CUSTOM_UPLOAD_URL)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf(String::isNotBlank) ?: getString(R.string.upload_http_url_hint)
            }
            setOnPreferenceChangeListener { _, value ->
                if (!canChangeDestination()) return@setOnPreferenceChangeListener false

                val address = (value as String).trim()
                val parsed = address.toHttpUrlOrNull()
                if (address.isNotEmpty() && (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty())) {
                    Toast.makeText(requireContext(), R.string.upload_http_url_invalid, Toast.LENGTH_LONG).show()
                    return@setOnPreferenceChangeListener false
                }

                if (address != CloudSyncManager.getCustomUploadUrl(requireContext())) {
                    CloudSyncManager.clearSyncedDatasetNames(requireContext(), DESTINATION_HTTP)
                }
                true
            }
        }

        findPreference<EditTextPreference>(PREF_CUSTOM_UPLOAD_TOKEN)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        findPreference<Preference>(PREF_CLOUD_STORAGE_FOLDER)?.setOnPreferenceClickListener {
            if (!canChangeDestination()) return@setOnPreferenceClickListener true
            if (CloudSyncManager.getSafStorageUri(requireContext()) == null) {
                pickFolder()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.upload_folder_title)
                    .setItems(arrayOf(getString(R.string.dialog_change_folder), getString(R.string.dialog_clear_folder))) { _, which ->
                        if (!canChangeDestination()) return@setItems

                        if (which == 0) {
                            pickFolder()
                        } else {
                            CloudSyncManager.setSafStorageUri(requireContext(), null)
                            refreshDestination()
                            SyncWorker.schedule(requireContext(), replace = true)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }

        refreshDestination()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        (activity as? SettingsActivity)?.title = getString(R.string.upload_settings_title)
        refreshDestination()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!isAdded || key !in UPLOAD_PREFERENCES_REQUIRING_REFRESH) return

        // Preferences persist their own values. React once, after the new destination is readable.
        refreshDestination()
        SyncWorker.schedule(requireContext(), replace = true)
    }

    private fun canChangeDestination(): Boolean {
        if (!CloudSyncManager.syncProgress.value.isSyncing) return true

        Toast.makeText(requireContext(), R.string.upload_settings_busy, Toast.LENGTH_LONG).show()
        return false
    }

    private fun pickFolder() {
        folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }

    private fun refreshDestination() {
        val context = requireContext()
        val selected = CloudSyncManager.selectedUploadMode(context)
        findPreference<PreferenceCategory>("upload_http_settings")?.isVisible = selected == CloudSyncMode.HTTP_SERVER
        findPreference<PreferenceCategory>("upload_folder_settings")?.isVisible = selected == CloudSyncMode.SAF_STORAGE
        findPreference<PreferenceCategory>("upload_s3_settings")?.isVisible = selected == CloudSyncMode.S3
        findPreference<PreferenceCategory>("upload_background")?.isVisible = selected != CloudSyncMode.NOT_CONFIGURED

        findPreference<Preference>("upload_status")?.summary = getString(
            when {
                selected == CloudSyncMode.NOT_CONFIGURED -> R.string.upload_destination_disabled
                CloudSyncManager.getCloudSyncMode(context) == CloudSyncMode.NOT_CONFIGURED -> R.string.upload_destination_setup_help
                else -> R.string.upload_destination_ready
            }
        )

        val profiles = S3Destinations.list(context)
        findPreference<ListPreference>(PREF_ACTIVE_S3_DESTINATION)?.apply {
            val labels = mutableListOf(getString(R.string.upload_s3_choose_profile))
            val ids = mutableListOf("")

            for (profile in profiles) {
                val name = profile.displayName.ifBlank { getString(R.string.s3_unnamed_destination) }
                labels += if (S3Destinations.isComplete(context, profile)) name else getString(R.string.upload_s3_profile_incomplete, name)
                ids += profile.id
            }

            val current = sharedPreferences?.getString(PREF_ACTIVE_S3_DESTINATION, "").orEmpty()
            if (current !in ids) {
                labels += getString(R.string.upload_s3_profile_missing)
                ids += current
            }

            entries = labels.toTypedArray()
            entryValues = ids.toTypedArray()
            isEnabled = profiles.isNotEmpty()
            summary = if (profiles.isEmpty()) getString(R.string.upload_s3_no_profiles) else labels[ids.indexOf(current)]
        }

        val uri = CloudSyncManager.getSafStorageUri(context)
        findPreference<Preference>(PREF_CLOUD_STORAGE_FOLDER)?.summary = if (uri == null) {
            getString(R.string.cloud_storage_folder_not_set)
        } else {
            val name = try {
                DocumentFile.fromTreeUri(context, uri)?.name
            } catch (_: SecurityException) {
                null
            }
            getString(R.string.upload_folder_selected, name ?: uri.lastPathSegment ?: uri.toString())
        }
    }
}
