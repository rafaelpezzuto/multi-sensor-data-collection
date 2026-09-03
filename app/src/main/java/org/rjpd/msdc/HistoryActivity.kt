package org.rjpd.msdc

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import org.rjpd.msdc.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_datasets)

        refreshDatasetsList()
    }

    override fun onResume() {
        super.onResume()
        refreshDatasetsList()
    }

    private fun markDatasetAsViewed(datasetName: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val currentViewed = sharedPreferences.getStringSet("viewed_dataset_names", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentViewed.add(datasetName)
        sharedPreferences.edit().putStringSet("viewed_dataset_names", currentViewed).apply()
    }

    private fun getExpandedGroupKeys(): Set<String> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        return sharedPreferences.getStringSet("expanded_group_keys", null) ?: emptySet()
    }

    private fun saveExpandedGroupKeys(keys: Set<String>) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit().putStringSet("expanded_group_keys", keys).apply()
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

    private fun refreshDatasetsList() {
        val datasets = scanCollectedDatasets(this)

        if (datasets.isEmpty()) {
            binding.emptyTextview.visibility = View.VISIBLE
            binding.historyRecyclerview.visibility = View.GONE
        } else {
            binding.emptyTextview.visibility = View.GONE
            binding.historyRecyclerview.visibility = View.VISIBLE

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val viewedDatasetNames = sharedPreferences.getStringSet("viewed_dataset_names", emptySet()) ?: emptySet()

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

            binding.historyRecyclerview.adapter = HistoryAdapter(
                directoryGroups = directoryGroups,
                viewedDatasetNames = viewedDatasetNames,
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
                onShareClick = { dataset ->
                    markDatasetAsViewed(dataset.name)
                    shareDatasetFile(this, dataset.path)
                },
                onDeleteClick = { dataset ->
                    confirmDeleteDataset(dataset)
                },
                onGroupToggle = {
                    saveCurrentExpandedState(directoryGroups)
                }
            )
        }
    }

    private fun confirmDeleteDataset(dataset: DatasetSummary) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
        }

        val warningText = android.widget.TextView(this).apply {
            text = "This action permanently deletes the collection '${dataset.name}' (${dataset.formattedSize}, ${dataset.fileCount} files) from device storage.\n\nType DELETE below to confirm:"
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }
        container.addView(warningText)

        val inputEditText = android.widget.EditText(this).apply {
            hint = "Type DELETE to confirm"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 14f
        }
        container.addView(inputEditText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete Collection")
            .setView(container)
            .setPositiveButton("Delete Permanently", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        val deleteButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        deleteButton.isEnabled = false

        inputEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                deleteButton.isEnabled = s?.toString()?.trim().equals("DELETE", ignoreCase = true)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        deleteButton.setOnClickListener {
            if (deleteDatasetFileOrFolder(dataset.path)) {
                Toast.makeText(this, "Collection '${dataset.name}' deleted", Toast.LENGTH_SHORT).show()
                refreshDatasetsList()
            } else {
                Toast.makeText(this, "Failed to delete collection", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
