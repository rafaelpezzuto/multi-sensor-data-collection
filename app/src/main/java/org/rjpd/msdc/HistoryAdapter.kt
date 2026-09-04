package org.rjpd.msdc

import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.rjpd.msdc.databinding.ItemDatasetBinding
import org.rjpd.msdc.databinding.ItemDatasetGroupHeaderBinding
import org.rjpd.msdc.databinding.ItemDatasetSubgroupHeaderBinding
import java.util.Date

data class SubdirectoryGroup(
    val subdirectoryName: String,
    val datasets: List<DatasetSummary>,
    var isExpanded: Boolean = false
)

data class DirectoryGroup(
    val directoryName: String,
    val subdirectories: List<SubdirectoryGroup>,
    var isExpanded: Boolean = false
)

sealed class HistoryListItem {
    data class DirectoryHeader(val dirGroup: DirectoryGroup) : HistoryListItem()
    data class SubdirectoryHeader(val subGroup: SubdirectoryGroup) : HistoryListItem()
    data class DatasetCard(val dataset: DatasetSummary) : HistoryListItem()
}

class HistoryAdapter(
    private val directoryGroups: List<DirectoryGroup>,
    private val viewedDatasetNames: Set<String> = emptySet(),
    syncedDatasetNames: Set<String> = emptySet(),
    private val onItemClick: (DatasetSummary) -> Unit,
    private val onMapClick: ((DatasetSummary) -> Unit)? = null,
    private val onSyncClick: ((DatasetSummary) -> Unit)? = null,
    private val onShareClick: ((DatasetSummary) -> Unit)? = null,
    private val onDeleteClick: ((DatasetSummary) -> Unit)? = null,
    private val onGroupToggle: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val syncedDatasetNames = syncedDatasetNames.toMutableSet()
    private val displayItems = mutableListOf<HistoryListItem>()

    init {
        rebuildDisplayItems()
    }

    fun markDatasetSynced(datasetName: String) {
        if (syncedDatasetNames.add(datasetName)) {
            val index = displayItems.indexOfFirst { it is HistoryListItem.DatasetCard && it.dataset.name == datasetName }
            if (index != -1) {
                notifyItemChanged(index)
            }
        }
    }

    fun updateSyncedDatasetNames(newSyncedNames: Set<String>) {
        if (syncedDatasetNames != newSyncedNames) {
            syncedDatasetNames.clear()
            syncedDatasetNames.addAll(newSyncedNames)
            notifyDataSetChanged()
        }
    }

    private fun rebuildDisplayItems() {
        displayItems.clear()
        for (dirGroup in directoryGroups) {
            displayItems.add(HistoryListItem.DirectoryHeader(dirGroup))
            if (dirGroup.isExpanded) {
                val hasSingleMatchingSubdir = dirGroup.subdirectories.size == 1 && run {
                    val subName = dirGroup.subdirectories[0].subdirectoryName
                    subName.equals(dirGroup.directoryName, ignoreCase = true) ||
                    subName.equals("Default", ignoreCase = true) ||
                    subName.isEmpty()
                }

                if (hasSingleMatchingSubdir) {
                    for (dataset in dirGroup.subdirectories[0].datasets) {
                        displayItems.add(HistoryListItem.DatasetCard(dataset))
                    }
                } else {
                    for (subGroup in dirGroup.subdirectories) {
                        displayItems.add(HistoryListItem.SubdirectoryHeader(subGroup))
                        if (subGroup.isExpanded) {
                            for (dataset in subGroup.datasets) {
                                displayItems.add(HistoryListItem.DatasetCard(dataset))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is HistoryListItem.DirectoryHeader -> TYPE_DIR_HEADER
            is HistoryListItem.SubdirectoryHeader -> TYPE_SUBDIR_HEADER
            is HistoryListItem.DatasetCard -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DIR_HEADER -> {
                val binding = ItemDatasetGroupHeaderBinding.inflate(inflater, parent, false)
                DirectoryHeaderViewHolder(binding)
            }
            TYPE_SUBDIR_HEADER -> {
                val binding = ItemDatasetSubgroupHeaderBinding.inflate(inflater, parent, false)
                SubdirectoryHeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemDatasetBinding.inflate(inflater, parent, false)
                ItemViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayItems[position]) {
            is HistoryListItem.DirectoryHeader -> (holder as DirectoryHeaderViewHolder).bind(item.dirGroup)
            is HistoryListItem.SubdirectoryHeader -> (holder as SubdirectoryHeaderViewHolder).bind(item.subGroup)
            is HistoryListItem.DatasetCard -> (holder as ItemViewHolder).bind(item.dataset)
        }
    }

    override fun getItemCount(): Int = displayItems.size

    inner class DirectoryHeaderViewHolder(private val binding: ItemDatasetGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(dirGroup: DirectoryGroup) {
            binding.groupTitleTextview.text = dirGroup.directoryName
            val allDatasets = dirGroup.subdirectories.flatMap { it.datasets }
            val totalSizeBytes = allDatasets.sumOf { it.sizeBytes }
            val formattedTotalSize = Formatter.formatFileSize(itemView.context, totalSizeBytes)
            val totalDurationMs = allDatasets.sumOf { it.durationMillis }
            val durationInfo = if (totalDurationMs > 0) " • " + formatDurationMillis(totalDurationMs) else ""
            binding.groupCountTextview.text = "${allDatasets.size} collections • $formattedTotalSize$durationInfo"

            if (dirGroup.isExpanded) {
                binding.groupFolderImageview.setImageResource(R.drawable.ic_folder_open)
                binding.expandArrowImageview.setImageResource(R.drawable.ic_expand_less)
            } else {
                binding.groupFolderImageview.setImageResource(R.drawable.ic_folder_closed)
                binding.expandArrowImageview.setImageResource(R.drawable.ic_expand_more)
            }

            itemView.setOnClickListener {
                dirGroup.isExpanded = !dirGroup.isExpanded
                onGroupToggle?.invoke()
                rebuildDisplayItems()
                notifyDataSetChanged()
            }
        }
    }

    inner class SubdirectoryHeaderViewHolder(private val binding: ItemDatasetSubgroupHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(subGroup: SubdirectoryGroup) {
            binding.subgroupTitleTextview.text = subGroup.subdirectoryName
            val totalSizeBytes = subGroup.datasets.sumOf { it.sizeBytes }
            val formattedTotalSize = Formatter.formatFileSize(itemView.context, totalSizeBytes)
            val totalDurationMs = subGroup.datasets.sumOf { it.durationMillis }
            val durationInfo = if (totalDurationMs > 0) " • " + formatDurationMillis(totalDurationMs) else ""
            binding.subgroupCountTextview.text = "${subGroup.datasets.size} collections • $formattedTotalSize$durationInfo"

            if (subGroup.isExpanded) {
                binding.subgroupFolderImageview.setImageResource(R.drawable.ic_folder_open)
                binding.subgroupExpandArrowImageview.setImageResource(R.drawable.ic_expand_less)
            } else {
                binding.subgroupFolderImageview.setImageResource(R.drawable.ic_folder_closed)
                binding.subgroupExpandArrowImageview.setImageResource(R.drawable.ic_expand_more)
            }

            itemView.setOnClickListener {
                subGroup.isExpanded = !subGroup.isExpanded
                onGroupToggle?.invoke()
                rebuildDisplayItems()
                notifyDataSetChanged()
            }
        }
    }

    inner class ItemViewHolder(private val binding: ItemDatasetBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(dataset: DatasetSummary) {
            binding.datasetNameTextview.text = dataset.name

            val isSynced = syncedDatasetNames.contains(dataset.name)
            if (isSynced) {
                binding.syncButton.setImageResource(R.drawable.ic_cloud_done)
                binding.syncButton.setColorFilter(ContextCompat.getColor(itemView.context, R.color.icon_tint))
            } else {
                binding.syncButton.setImageResource(R.drawable.ic_cloud_upload)
                binding.syncButton.setColorFilter(ContextCompat.getColor(itemView.context, R.color.unsynced_orange))
            }

            val isViewed = viewedDatasetNames.contains(dataset.name)
            if (!isViewed) {
                binding.datasetNewBadgeTextview.visibility = View.VISIBLE
            } else {
                binding.datasetNewBadgeTextview.visibility = View.GONE
            }

            val durationInfo = if (!dataset.formattedDuration.isNullOrEmpty()) " • ${dataset.formattedDuration}" else ""
            binding.datasetSizeTextview.text = "${dataset.formattedSize} • " +
                    itemView.context.getString(R.string.dataset_file_count_format, dataset.fileCount) + durationInfo

            binding.datasetDateTextview.text = DateFormat.getDateFormat(itemView.context).format(Date(dataset.lastModifiedMillis)) + " " +
                    DateFormat.getTimeFormat(itemView.context).format(Date(dataset.lastModifiedMillis))

            if (dataset.isZip) {
                binding.datasetTypeBadgeTextview.text = itemView.context.getString(R.string.dataset_type_zip)
            } else {
                binding.datasetTypeBadgeTextview.text = itemView.context.getString(R.string.dataset_type_folder)
            }

            val hasGps = dataset.fileList.any { it.endsWith("gps.csv") }
            if (hasGps) {
                binding.mapButton.visibility = View.VISIBLE
                binding.mapButton.setOnClickListener {
                    onMapClick?.invoke(dataset)
                }
            } else {
                binding.mapButton.visibility = View.GONE
            }

            binding.syncButton.setOnClickListener {
                onSyncClick?.invoke(dataset)
            }

            binding.shareButton.setOnClickListener {
                onShareClick?.invoke(dataset)
            }

            binding.deleteButton.setOnClickListener {
                onDeleteClick?.invoke(dataset)
            }

            itemView.setOnClickListener {
                onItemClick(dataset)
            }
        }
    }

    companion object {
        private const val TYPE_DIR_HEADER = 0
        private const val TYPE_SUBDIR_HEADER = 1
        private const val TYPE_ITEM = 2
    }
}
