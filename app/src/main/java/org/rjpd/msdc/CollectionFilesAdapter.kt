package org.rjpd.msdc

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.rjpd.msdc.databinding.ItemCollectionFileBinding
import java.io.File

data class CollectionFileItem(
    val name: String,
    val file: File,
    val extension: String,
    val sizeBytes: Long
)

class CollectionFilesAdapter(
    private val files: List<CollectionFileItem>,
    private val onItemClick: (CollectionFileItem) -> Unit
) : RecyclerView.Adapter<CollectionFilesAdapter.FileViewHolder>() {

    class FileViewHolder(val binding: ItemCollectionFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemCollectionFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = files[position]
        holder.binding.fileNameTextview.text = item.name
        holder.binding.fileSizeTextview.text = Formatter.formatFileSize(holder.itemView.context, item.sizeBytes)

        when (item.extension.lowercase()) {
            "mp4", "3gp", "mkv" -> {
                holder.binding.fileIconImageview.setImageResource(android.R.drawable.ic_media_play)
                holder.binding.fileTypeBadgeTextview.text = holder.itemView.context.getString(R.string.file_type_video)
            }
            "m4a", "aac", "wav", "mp3" -> {
                holder.binding.fileIconImageview.setImageResource(android.R.drawable.ic_btn_speak_now)
                holder.binding.fileTypeBadgeTextview.text = holder.itemView.context.getString(R.string.file_type_audio)
            }
            "json" -> {
                holder.binding.fileIconImageview.setImageResource(android.R.drawable.ic_menu_info_details)
                holder.binding.fileTypeBadgeTextview.text = holder.itemView.context.getString(R.string.file_type_json)
            }
            "csv" -> {
                holder.binding.fileIconImageview.setImageResource(android.R.drawable.ic_menu_sort_by_size)
                holder.binding.fileTypeBadgeTextview.text = holder.itemView.context.getString(R.string.file_type_csv)
            }
            else -> {
                holder.binding.fileIconImageview.setImageResource(android.R.drawable.ic_menu_save)
                holder.binding.fileTypeBadgeTextview.text = item.extension.uppercase()
            }
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = files.size
}
