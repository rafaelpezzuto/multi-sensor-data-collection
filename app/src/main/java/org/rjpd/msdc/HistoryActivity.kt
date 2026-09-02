package org.rjpd.msdc

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import org.rjpd.msdc.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_datasets)

        val datasets = scanCollectedDatasets(this)

        if (datasets.isEmpty()) {
            binding.emptyTextview.visibility = View.VISIBLE
            binding.historyRecyclerview.visibility = View.GONE
        } else {
            binding.emptyTextview.visibility = View.GONE
            binding.historyRecyclerview.visibility = View.VISIBLE
            binding.historyRecyclerview.layoutManager = LinearLayoutManager(this)
            binding.historyRecyclerview.addItemDecoration(
                DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
            )
            binding.historyRecyclerview.adapter = HistoryAdapter(datasets) { dataset ->
                showDatasetSummaryDialog(dataset)
            }
        }
    }

    private fun showDatasetSummaryDialog(dataset: DatasetSummary) {
        val details = buildString {
            append("Name: ").append(dataset.name).append("\n")
            append("Size: ").append(dataset.formattedSize).append("\n")
            append("Path: ").append(dataset.path.absolutePath).append("\n\n")

            if (dataset.metadataMap != null) {
                append("--- Metadata Summary ---\n")
                val timeMap = dataset.metadataMap["time"] as? Map<*, *>
                if (timeMap != null) {
                    append("Start Time: ").append(timeMap["buttonStartDateTime"] ?: "N/A").append("\n")
                    append("Stop Time: ").append(timeMap["buttonStopDateTime"] ?: "N/A").append("\n")
                }
                val deviceMap = dataset.metadataMap["device"] as? Map<*, *>
                if (deviceMap != null) {
                    append("Device: ").append(deviceMap["manufacturer"] ?: "").append(" ").append(deviceMap["model"] ?: "").append("\n")
                }
                append("\n")
            }

            append("--- Files (").append(dataset.fileCount).append(") ---\n")
            dataset.fileList.forEach { fileName ->
                append("• ").append(fileName).append("\n")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(dataset.name)
            .setMessage(details)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
