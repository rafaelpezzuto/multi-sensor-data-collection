package org.rjpd.msdc

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.rjpd.msdc.databinding.ItemDatasetBinding
import java.util.Date

class HistoryAdapter(
    private val datasets: List<DatasetSummary>,
    private val onItemClick: (DatasetSummary) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.DatasetViewHolder>() {

    class DatasetViewHolder(val binding: ItemDatasetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DatasetViewHolder {
        val binding = ItemDatasetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DatasetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DatasetViewHolder, position: Int) {
        val dataset = datasets[position]
        holder.binding.datasetNameTextview.text = dataset.name
        holder.binding.datasetSizeTextview.text = "${dataset.formattedSize} • " +
                holder.itemView.context.getString(R.string.dataset_file_count_format, dataset.fileCount)
        holder.binding.datasetDateTextview.text = DateFormat.getDateFormat(holder.itemView.context).format(Date(dataset.lastModifiedMillis)) + " " +
                DateFormat.getTimeFormat(holder.itemView.context).format(Date(dataset.lastModifiedMillis))

        if (dataset.isZip) {
            holder.binding.datasetTypeBadgeTextview.text = holder.itemView.context.getString(R.string.dataset_type_zip)
        } else {
            holder.binding.datasetTypeBadgeTextview.text = holder.itemView.context.getString(R.string.dataset_type_folder)
        }

        holder.itemView.setOnClickListener {
            onItemClick(dataset)
        }
    }

    override fun getItemCount(): Int = datasets.size
}
