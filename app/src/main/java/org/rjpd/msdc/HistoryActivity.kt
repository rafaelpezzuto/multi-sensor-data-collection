package org.rjpd.msdc

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
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
                val intent = Intent(this, CollectionDetailActivity::class.java).apply {
                    putExtra(CollectionDetailActivity.EXTRA_DATASET_PATH, dataset.path.absolutePath)
                    putExtra(CollectionDetailActivity.EXTRA_DATASET_NAME, dataset.name)
                    putExtra(CollectionDetailActivity.EXTRA_IS_ZIP, dataset.isZip)
                }
                startActivity(intent)
            }
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
