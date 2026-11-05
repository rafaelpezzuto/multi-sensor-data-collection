package org.rjpd.msdc

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import java.text.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val ARG_DESTINATION_ID = "destination_id"

/** Lists saved S3 destinations; each opens [S3DestinationFragment]. */
class S3DestinationsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onResume() {
        super.onResume()

        val context = requireContext()
        preferenceScreen.removeAll()

        for (destination in S3Destinations.list(context)) {
            preferenceScreen.addPreference(Preference(context).apply {
                title = destination.displayName.ifBlank { getString(R.string.s3_unnamed_destination) }
                summary = if (S3Destinations.isComplete(context, destination)) {
                    "${destination.endpoint} • ${destination.bucket}"
                } else {
                    getString(R.string.s3_destination_incomplete)
                }
                fragment = S3DestinationFragment::class.java.name
                extras.putString(ARG_DESTINATION_ID, destination.id)
            })
        }

        preferenceScreen.addPreference(Preference(context).apply {
            title = getString(R.string.s3_add_destination)
            setOnPreferenceClickListener {
                val destination = S3Destination()
                S3Destinations.save(context, destination)
                (activity as? SettingsActivity)?.openFragment(S3DestinationFragment(), Bundle().apply {
                    putString(ARG_DESTINATION_ID, destination.id)
                })
                true
            }
        })
    }
}

/** Edits one destination. Secrets are write-only: they are encrypted on entry and never shown again. */
class S3DestinationFragment : PreferenceFragmentCompat() {

    private lateinit var destination: S3Destination

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        destination = S3Destinations.get(context, arguments?.getString(ARG_DESTINATION_ID)) ?: run {
            parentFragmentManager.popBackStack()
            return
        }
        preferenceManager.preferenceDataStore = DestinationStore()
        setPreferencesFromResource(R.xml.s3_destination_preferences, rootKey)

        findPreference<EditTextPreference>("endpoint")?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            setOnPreferenceChangeListener { _, value ->
                val endpoint = (value as String).trim()
                val valid = endpoint.startsWith("https://") && endpoint.toHttpUrlOrNull() != null
                if (!valid) Toast.makeText(context, R.string.s3_endpoint_invalid, Toast.LENGTH_LONG).show()
                valid
            }
        }

        findPreference<EditTextPreference>("key_template")?.setOnPreferenceChangeListener { _, value ->
            val valid = "{dataset}" in value as String || "{timestamp}" in value
            if (!valid) Toast.makeText(context, R.string.s3_key_template_invalid, Toast.LENGTH_LONG).show()
            valid
        }

        findPreference<EditTextPreference>("part_size_mib")?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { getString(R.string.s3_part_size_summary, it.text) }
            setOnPreferenceChangeListener { _, value ->
                val valid = (value as String).trim().toIntOrNull() in 5..5120
                if (!valid) Toast.makeText(context, R.string.s3_part_size_invalid, Toast.LENGTH_LONG).show()
                valid
            }
        }

        setupSecret("access_key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { S3Destinations.hasAccessKey(context, destination.id) }
        setupSecret("secret_key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { S3Destinations.hasSecretKey(context, destination.id) }

        findPreference<Preference>("trust_certificate")?.setOnPreferenceClickListener {
            onTrustCertificateClicked()
            true
        }
        findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
            runConnectionTest(it)
            true
        }
        findPreference<Preference>("delete_destination")?.setOnPreferenceClickListener {
            AlertDialog.Builder(context)
                .setTitle(R.string.s3_delete_destination_title)
                .setMessage(getString(R.string.s3_delete_destination_msg, destination.displayName))
                .setPositiveButton(R.string.s3_delete_destination_title) { _, _ ->
                    val deleted = destination
                    lifecycleScope.launch {
                        // Abort its unfinished uploads while the credentials still exist.
                        withContext(Dispatchers.IO) { S3Sync.abortUnfinishedUploads(context, deleted) }
                        S3Destinations.delete(context, deleted.id)
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        if (prefs.getString(PREF_ACTIVE_S3_DESTINATION, null) == deleted.id) {
                            prefs.edit().remove(PREF_ACTIVE_S3_DESTINATION).apply()
                        }
                        parentFragmentManager.popBackStack()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
        updateTrustSummary()
    }

    private fun setupSecret(key: String, inputType: Int, isSet: () -> Boolean) {
        findPreference<EditTextPreference>(key)?.apply {
            setOnBindEditTextListener { it.inputType = inputType }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                getString(if (isSet()) R.string.s3_secret_set else R.string.s3_secret_not_set)
            }
            setOnPreferenceChangeListener { preference, value ->
                val secret = (value as String).trim()
                if (secret.isNotEmpty()) {
                    if (key == "access_key") {
                        S3Destinations.setAccessKey(requireContext(), destination.id, secret)
                    } else {
                        S3Destinations.setSecretKey(requireContext(), destination.id, secret)
                    }
                }
                (preference as EditTextPreference).text = null
                false
            }
        }
    }

    private fun onTrustCertificateClicked() {
        val context = requireContext()
        val trusted = destination.trustedCertificate
        if (trusted != null) {
            AlertDialog.Builder(context)
                .setTitle(R.string.s3_trusted_certificate_title)
                .setMessage(describe(S3Destinations.decodeCertificate(trusted)))
                .setPositiveButton(R.string.s3_trust_remove) { _, _ -> update(destination.copy(trustedCertificate = null)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        if (destination.endpoint.isBlank()) {
            Toast.makeText(context, R.string.s3_endpoint_invalid, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            val certificate = try {
                withContext(Dispatchers.IO) { S3Client.fetchServerCertificate(destination.endpoint) }
            } catch (e: Exception) {
                Toast.makeText(context, getString(R.string.s3_trust_fetch_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                return@launch
            }

            AlertDialog.Builder(context)
                .setTitle(R.string.s3_trust_confirm_title)
                .setMessage(getString(R.string.s3_trust_warning) + "\n\n" + describe(certificate))
                .setPositiveButton(R.string.s3_trust_confirm) { _, _ ->
                    update(destination.copy(trustedCertificate = S3Destinations.encodeCertificate(certificate)))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun describe(certificate: java.security.cert.X509Certificate): String {
        val date = DateFormat.getDateInstance()
        return getString(
            R.string.s3_certificate_details,
            certificate.subjectX500Principal.name,
            certificate.issuerX500Principal.name,
            date.format(certificate.notAfter),
            S3Destinations.fingerprint(certificate)
        )
    }

    private fun updateTrustSummary() {
        findPreference<Preference>("trust_certificate")?.summary = destination.trustedCertificate?.let {
            getString(R.string.s3_trust_summary_pinned, S3Destinations.fingerprint(S3Destinations.decodeCertificate(it)).take(23))
        } ?: getString(R.string.s3_trust_summary_system)
    }

    private fun runConnectionTest(preference: Preference) {
        val context = requireContext()
        preference.isEnabled = false
        preference.summary = getString(R.string.s3_test_running)

        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { S3Destinations.testConnection(context, destination) }
            preference.isEnabled = true
            preference.summary = getString(R.string.s3_test_connection_summary)

            AlertDialog.Builder(context)
                .setTitle(R.string.s3_test_connection_title)
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun update(updated: S3Destination) {
        destination = updated
        S3Destinations.save(requireContext(), updated)
        updateTrustSummary()
    }

    private inner class DestinationStore : PreferenceDataStore() {
        override fun getString(key: String, defValue: String?): String? = when (key) {
            "name" -> destination.name
            "endpoint" -> destination.endpoint
            "region" -> destination.region
            "bucket" -> destination.bucket
            "key_template" -> destination.keyTemplate
            "part_size_mib" -> destination.partSizeMib.toString()
            else -> defValue
        }

        override fun putString(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            update(
                when (key) {
                    "name" -> destination.copy(name = text)
                    "endpoint" -> destination.copy(endpoint = text.trimEnd('/'))
                    "region" -> destination.copy(region = text)
                    "bucket" -> destination.copy(bucket = text)
                    "key_template" -> destination.copy(keyTemplate = text.trim('/'))
                    "part_size_mib" -> destination.copy(partSizeMib = text.toIntOrNull() ?: destination.partSizeMib)
                    else -> return
                }
            )
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean = when (key) {
            "path_style" -> destination.pathStyle
            "zip_mode" -> destination.zipMode
            "overwrite" -> destination.overwrite
            else -> defValue
        }

        override fun putBoolean(key: String, value: Boolean) {
            update(
                when (key) {
                    "path_style" -> destination.copy(pathStyle = value)
                    "zip_mode" -> destination.copy(zipMode = value)
                    "overwrite" -> destination.copy(overwrite = value)
                    else -> return
                }
            )
        }
    }
}
