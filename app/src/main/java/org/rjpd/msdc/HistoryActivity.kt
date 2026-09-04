package org.rjpd.msdc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rjpd.msdc.databinding.ActivityHistoryBinding

private const val PREF_VIEWED_DATASET_NAMES = "viewed_dataset_names"
private const val PREF_EXPANDED_GROUP_KEYS = "expanded_group_keys"
private const val CONFIRM_DELETE_KEYWORD = "DELETE"

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var historyAdapter: HistoryAdapter? = null
    private var pendingSyncDataset: DatasetSummary? = null

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.collection_deleted_toast, ""), Toast.LENGTH_SHORT).show()
            refreshDatasetsList()
        } else {
            Toast.makeText(this, R.string.collection_delete_failed_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val treeUri = result.data?.data
            if (treeUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                CloudSyncManager.setSafStorageUri(this, treeUri)

                val target = pendingSyncDataset
                pendingSyncDataset = null

                if (target != null) {
                    syncSingleDataset(target)
                } else {
                    syncAllPendingDatasets()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_datasets)

        lifecycleScope.launch {
            CloudSyncManager.syncProgress.collect { state ->
                handleSyncProgress(state)
            }
        }

        refreshDatasetsList()
    }

    override fun onResume() {
        super.onResume()
        refreshDatasetsList()
    }

    private fun markDatasetAsViewed(datasetName: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val currentViewed = sharedPreferences.getStringSet(PREF_VIEWED_DATASET_NAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentViewed.add(datasetName)
        sharedPreferences.edit().putStringSet(PREF_VIEWED_DATASET_NAMES, currentViewed).apply()
    }

    private fun getExpandedGroupKeys(): Set<String> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        return sharedPreferences.getStringSet(PREF_EXPANDED_GROUP_KEYS, null) ?: emptySet()
    }

    private fun saveExpandedGroupKeys(keys: Set<String>) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit().putStringSet(PREF_EXPANDED_GROUP_KEYS, keys).apply()
    }

    private fun saveCurrentExpandedState(directoryGroups: List<DirectoryGroup>) {
        val activeKeys = mutableSetOf<String>()
        for (dirGroup in directoryGroups) {
            if (dirGroup.isExpanded) {
                activeKeys.add(dirGroup.directoryName)
                for (subGroup in dirGroup.subdirectories) {
                    if (subGroup.isExpanded) {
                        activeKeys.add("${dirGroup.directoryName}/${subGroup.subdirectoryName}")
                    }
                }
            }
        }
        saveExpandedGroupKeys(activeKeys)
    }

    private fun showConfigureCloudSyncDialog(forDataset: DatasetSummary? = null) {
        pendingSyncDataset = forDataset

        AlertDialog.Builder(this)
            .setTitle(R.string.cloud_sync_not_configured_title)
            .setMessage(R.string.cloud_sync_not_configured_msg)
            .setPositiveButton(R.string.cloud_sync_option_folder) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                folderPickerLauncher.launch(intent)
            }
            .setNeutralButton(R.string.cloud_sync_option_server) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleSyncProgress(state: SyncProgressState) {
        if (state.isSyncing) {
            binding.syncProgressBar.visibility = View.VISIBLE
            binding.syncStatusBanner.visibility = View.VISIBLE

            if (state.total > 0) {
                binding.syncProgressBar.isIndeterminate = false
                binding.syncProgressBar.max = state.total
                binding.syncProgressBar.progress = state.current
            } else {
                binding.syncProgressBar.isIndeterminate = true
            }

            binding.syncStatusText.text = if (state.total > 1) {
                getString(R.string.sync_all_in_progress_format, state.current, state.total, state.currentDatasetName)
            } else {
                getString(R.string.sync_in_progress_msg, state.currentDatasetName)
            }
        } else {
            binding.syncProgressBar.visibility = View.GONE
            binding.syncStatusBanner.visibility = View.GONE

            if (state.isFinished) {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                val syncedDatasetNames = sharedPreferences.getStringSet(PREF_SYNCED_DATASET_NAMES, emptySet()) ?: emptySet()
                historyAdapter?.updateSyncedDatasetNames(syncedDatasetNames)
                invalidateOptionsMenu()
                CloudSyncManager.resetProgress()

                val currentDatasets = scanCollectedDatasets(this)
                verifyCloudSyncStatusAsync(currentDatasets)
            }
        }

        state.lastCompletedDataset?.let { datasetName ->
            if (state.lastResult?.success == true) {
                historyAdapter?.markDatasetSynced(datasetName)
                invalidateOptionsMenu()
            }
        }
    }

    private fun syncSingleDataset(dataset: DatasetSummary) {
        if (CloudSyncManager.syncProgress.value.isSyncing) {
            Toast.makeText(this, R.string.sync_already_running_toast, Toast.LENGTH_SHORT).show()
            return
        }
        DataSyncService.startSyncSingle(this, dataset.name)
    }

    private fun syncAllPendingDatasets() {
        if (CloudSyncManager.syncProgress.value.isSyncing) {
            Toast.makeText(this, R.string.sync_already_running_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val datasets = scanCollectedDatasets(this)
        val pendingDatasets = datasets.filter { !CloudSyncManager.isDatasetSynced(this, it.name) }
        if (pendingDatasets.isEmpty()) {
            Toast.makeText(this, getString(R.string.sync_all_complete_status), Toast.LENGTH_SHORT).show()
            return
        }
        DataSyncService.startSyncAll(this)
    }

    private fun verifyCloudSyncStatusAsync(datasets: List<DatasetSummary>) {
        if (CloudSyncManager.getCloudSyncMode(this) != CloudSyncMode.SAF_STORAGE) {
            return
        }

        if (CloudSyncManager.syncProgress.value.isSyncing) {
            return
        }

        val treeUri = CloudSyncManager.getSafStorageUri(this) ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@HistoryActivity)
            val currentSynced = sharedPreferences.getStringSet(PREF_SYNCED_DATASET_NAMES, emptySet())?.toMutableSet() ?: mutableSetOf()

            val rootDoc = DocumentFile.fromTreeUri(this@HistoryActivity, treeUri)
            if (rootDoc == null || !rootDoc.exists()) {
                if (currentSynced.isNotEmpty()) {
                    currentSynced.clear()
                    sharedPreferences.edit().remove(PREF_SYNCED_DATASET_NAMES).apply()
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            historyAdapter?.updateSyncedDatasetNames(emptySet())
                            invalidateOptionsMenu()
                        }
                    }
                }
                return@launch
            }

            var changed = false

            for (dataset in datasets) {
                val existsOnCloud = CloudSyncManager.isCollectionPresentInSaf(this@HistoryActivity, dataset, treeUri)
                if (existsOnCloud) {
                    if (currentSynced.add(dataset.name)) {
                        changed = true
                    }
                } else {
                    if (currentSynced.remove(dataset.name)) {
                        changed = true
                    }
                }
            }

            if (changed) {
                sharedPreferences.edit().putStringSet(PREF_SYNCED_DATASET_NAMES, currentSynced).apply()
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        historyAdapter?.updateSyncedDatasetNames(currentSynced)
                        invalidateOptionsMenu()
                    }
                }
            }
        }
    }

    private fun refreshDatasetsList() {
        val datasets = scanCollectedDatasets(this)
        refreshDatasetsListInternal(datasets)
        verifyCloudSyncStatusAsync(datasets)
    }

    private fun refreshDatasetsListInternal(datasets: List<DatasetSummary>) {
        invalidateOptionsMenu()

        if (datasets.isEmpty()) {
            binding.emptyTextview.visibility = View.VISIBLE
            binding.historyRecyclerview.visibility = View.GONE
        } else {
            binding.emptyTextview.visibility = View.GONE
            binding.historyRecyclerview.visibility = View.VISIBLE

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val viewedDatasetNames = sharedPreferences.getStringSet(PREF_VIEWED_DATASET_NAMES, emptySet()) ?: emptySet()
            val syncedDatasetNames = sharedPreferences.getStringSet(PREF_SYNCED_DATASET_NAMES, emptySet()) ?: emptySet()

            val savedExpandedKeys = getExpandedGroupKeys()
            val hasSavedState = savedExpandedKeys.isNotEmpty()

            val dirGroupsMap = datasets.groupBy {
                val dir = it.directory.trim()
                if (dir.isNotEmpty()) dir else "Default"
            }

            val directoryGroups = dirGroupsMap.map { (dirName, dirDatasets) ->
                val subGroupsMap = dirDatasets.groupBy {
                    val subdir = it.subdirectory.trim()
                    if (subdir.isNotEmpty()) subdir else "Default"
                }
                val subdirectories = subGroupsMap.map { (subName, subList) ->
                    val subKey = "$dirName/$subName"
                    val isSubExpanded = if (hasSavedState) savedExpandedKeys.contains(subKey) else false
                    SubdirectoryGroup(
                        subdirectoryName = subName,
                        datasets = subList,
                        isExpanded = isSubExpanded
                    )
                }
                val dirKey = dirName
                val isDirExpanded = if (hasSavedState) savedExpandedKeys.contains(dirKey) else false
                DirectoryGroup(
                    directoryName = dirName,
                    subdirectories = subdirectories,
                    isExpanded = isDirExpanded
                )
            }

            if (!hasSavedState) {
                directoryGroups.firstOrNull()?.let { firstDir ->
                    firstDir.isExpanded = true
                    firstDir.subdirectories.firstOrNull()?.isExpanded = true
                }
            }

            binding.historyRecyclerview.layoutManager = LinearLayoutManager(this)
            if (binding.historyRecyclerview.itemDecorationCount == 0) {
                binding.historyRecyclerview.addItemDecoration(
                    DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
                )
            }

            val adapter = HistoryAdapter(
                directoryGroups = directoryGroups,
                viewedDatasetNames = viewedDatasetNames,
                syncedDatasetNames = syncedDatasetNames,
                onItemClick = { dataset ->
                    markDatasetAsViewed(dataset.name)
                    val intent = Intent(this, CollectionDetailActivity::class.java).apply {
                        putExtra(CollectionDetailActivity.EXTRA_DATASET_PATH, dataset.path.absolutePath)
                        putExtra(CollectionDetailActivity.EXTRA_DATASET_NAME, dataset.name)
                        putExtra(CollectionDetailActivity.EXTRA_IS_ZIP, dataset.isZip)
                    }
                    startActivity(intent)
                },
                onMapClick = { dataset ->
                    markDatasetAsViewed(dataset.name)
                    val previewDir = prepareDatasetPreview(this, dataset.path, dataset.isZip)
                    val gpsFile = previewDir.walkTopDown().firstOrNull { it.name.endsWith("gps.csv") }
                    if (gpsFile != null && gpsFile.exists()) {
                        val intent = Intent(this, MapVisualizationActivity::class.java).apply {
                            putExtra(MapVisualizationActivity.EXTRA_GPS_CSV_PATH, gpsFile.absolutePath)
                            putExtra(MapVisualizationActivity.EXTRA_COLLECTION_NAME, dataset.name)
                        }
                        startActivity(intent)
                    }
                },
                onSyncClick = { dataset ->
                    markDatasetAsViewed(dataset.name)
                    val isAlreadySynced = CloudSyncManager.isDatasetSynced(this@HistoryActivity, dataset.name)

                    val startSync = {
                        when (CloudSyncManager.getCloudSyncMode(this@HistoryActivity)) {
                            CloudSyncMode.NOT_CONFIGURED -> showConfigureCloudSyncDialog(dataset)
                            else -> syncSingleDataset(dataset)
                        }
                    }

                    if (isAlreadySynced) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.collection_already_synced_title)
                            .setMessage(getString(R.string.collection_already_synced_msg, dataset.name))
                            .setPositiveButton(R.string.resync_action) { _, _ -> startSync() }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    } else {
                        startSync()
                    }
                },
                onShareClick = { dataset ->
                    markDatasetAsViewed(dataset.name)
                    shareDatasetFile(this@HistoryActivity, dataset.path)
                },
                onDeleteClick = { dataset ->
                    confirmDeleteDataset(dataset)
                },
                onGroupToggle = {
                    saveCurrentExpandedState(directoryGroups)
                }
            )
            historyAdapter = adapter
            binding.historyRecyclerview.adapter = adapter
        }
    }

    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.storage_permission_required_title)
                .setMessage(R.string.storage_permission_required_msg)
                .setPositiveButton(R.string.open_settings_action) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        }
    }

    private fun confirmDeleteDataset(dataset: DatasetSummary) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
        }

        val warningText = TextView(this).apply {
            text = getString(
                R.string.delete_collection_warning_format,
                dataset.name,
                dataset.formattedSize,
                dataset.fileCount,
                CONFIRM_DELETE_KEYWORD
            )
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }
        container.addView(warningText)

        val inputEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 14f
        }
        container.addView(inputEditText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete_collection_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.delete_permanently_action, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        val deleteButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        deleteButton.isEnabled = false

        inputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                deleteButton.isEnabled = s?.toString()?.trim().equals(CONFIRM_DELETE_KEYWORD, ignoreCase = true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        deleteButton.setOnClickListener {
            try {
                if (deleteDatasetFileOrFolder(this, dataset.path)) {
                    Toast.makeText(this, getString(R.string.collection_deleted_toast, dataset.name), Toast.LENGTH_SHORT).show()
                    refreshDatasetsList()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        requestAllFilesAccessPermission()
                    } else {
                        Toast.makeText(this, R.string.collection_delete_failed_toast, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: RecoverableDeleteException) {
                val request = IntentSenderRequest.Builder(e.intentSender).build()
                deleteLauncher.launch(request)
            }
            dialog.dismiss()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        val syncItem = menu.findItem(R.id.action_sync_all) ?: return true
        val datasets = scanCollectedDatasets(this)

        if (datasets.isEmpty()) {
            syncItem.isVisible = false
            return true
        }

        syncItem.isVisible = true
        val pendingCount = datasets.count { !CloudSyncManager.isDatasetSynced(this, it.name) }
        val iconRes = if (pendingCount > 0) R.drawable.ic_cloud_upload else R.drawable.ic_cloud_done
        val tintColorRes = if (pendingCount > 0) R.color.unsynced_orange else R.color.icon_tint

        val iconDrawable = ContextCompat.getDrawable(this, iconRes)?.mutate()
        iconDrawable?.setTint(ContextCompat.getColor(this, tintColorRes))
        syncItem.icon = iconDrawable

        syncItem.title = if (pendingCount > 0) {
            getString(R.string.sync_all_pending_format, pendingCount)
        } else {
            getString(R.string.sync_all_complete_status)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        } else if (item.itemId == R.id.action_sync_all) {
            when (CloudSyncManager.getCloudSyncMode(this)) {
                CloudSyncMode.NOT_CONFIGURED -> showConfigureCloudSyncDialog()
                else -> syncAllPendingDatasets()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}