package org.rjpd.msdc

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONObject
import org.rjpd.msdc.databinding.ActivityCollectionDetailBinding
import java.io.File

class CollectionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val datasetPath = intent.getStringExtra(EXTRA_DATASET_PATH)?.let { File(it) }
        val datasetName = intent.getStringExtra(EXTRA_DATASET_NAME) ?: datasetPath?.name ?: ""
        val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, true)

        supportActionBar?.title = datasetName

        if (datasetPath != null && datasetPath.exists()) {
            val previewDir = prepareDatasetPreview(this, datasetPath, isZip)
            setupSummaryCard(datasetName, datasetPath, previewDir)
            setupFilesList(previewDir)
        } else {
            finish()
        }
    }

    private fun setupSummaryCard(datasetName: String, datasetPath: File, previewDir: File) {
        val files = previewDir.walkTopDown().filter { it.isFile }.toList()
        val totalSizeBytes = if (datasetPath.isFile) datasetPath.length() else files.sumOf { it.length() }
        val formattedSize = Formatter.formatFileSize(this, totalSizeBytes)

        var metadataStr: String? = null
        val metadataFile = previewDir.walkTopDown().firstOrNull { it.name.endsWith("metadata.json") }
        if (metadataFile != null && metadataFile.exists()) {
            try {
                metadataStr = metadataFile.readText()
            } catch (_: Exception) {}
        }

        val details = buildString {
            append("Type: ").append(if (datasetPath.extension.lowercase() == "zip") "ZIP Archive" else "Directory").append("\n")
            append("Size: ").append(formattedSize).append(" (").append(files.size).append(" files)\n")

            if (!metadataStr.isNullOrBlank()) {
                try {
                    val json = JSONObject(metadataStr)
                    val time = json.optJSONObject("time")
                    if (time != null) {
                        append("Start: ").append(time.optString("buttonStartDateTime", "N/A")).append("\n")
                        append("Stop: ").append(time.optString("buttonStopDateTime", "N/A")).append("\n")
                    }
                    val device = json.optJSONObject("device")
                    if (device != null) {
                        append("Device: ").append(device.optString("manufacturer", "")).append(" ").append(device.optString("model", "")).append("\n")
                    }
                } catch (_: Exception) {}
            }
            append("Location: ").append(datasetPath.parent ?: "")
        }

        binding.summaryTitleTextview.text = datasetName
        binding.summaryDetailsTextview.text = details
    }

    private fun setupFilesList(previewDir: File) {
        val files = previewDir.walkTopDown().filter { it.isFile }.toList().sortedBy { it.name }
        val items = files.map { file ->
            CollectionFileItem(
                name = file.name,
                file = file,
                extension = file.extension,
                sizeBytes = file.length()
            )
        }

        binding.filesRecyclerview.layoutManager = LinearLayoutManager(this)
        binding.filesRecyclerview.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.filesRecyclerview.adapter = CollectionFilesAdapter(items) { item ->
            onFileItemClicked(item)
        }
    }

    private fun onFileItemClicked(item: CollectionFileItem) {
        when (item.extension.lowercase()) {
            "mp4", "3gp", "mkv", "m4a", "aac", "wav", "mp3" -> {
                showMediaPlayerDialog(item)
            }
            "json" -> {
                showPrettyJsonDialog(item)
            }
            "csv" -> {
                showCsvPreviewDialog(item)
            }
            else -> {
                showTextPreviewDialog(item)
            }
        }
    }

    private fun showCsvPreviewDialog(item: CollectionFileItem) {
        val lines = try {
            item.file.useLines { seq -> seq.take(100).toList() }
        } catch (_: Exception) {
            emptyList()
        }

        if (lines.isEmpty()) {
            showTextPreviewDialog(item)
            return
        }

        val tableLayout = android.widget.TableLayout(this).apply {
            setPadding(16, 16, 16, 16)
        }

        lines.forEachIndexed { rowIndex, line ->
            val columns = line.split(",")
            val tableRow = android.widget.TableRow(this).apply {
                setPadding(0, 4, 0, 4)
            }

            columns.forEach { cellText ->
                val cellTextView = TextView(this).apply {
                    text = cellText.trim()
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 12f
                    setPadding(12, 8, 12, 8)
                    if (rowIndex == 0) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(getColor(R.color.red_700))
                    } else {
                        setTextColor(getColor(android.R.color.tab_indicator_text))
                    }
                }
                tableRow.addView(cellTextView)
            }
            tableLayout.addView(tableRow)
        }

        val horizontalScrollView = android.widget.HorizontalScrollView(this).apply {
            addView(tableLayout)
        }

        val verticalScrollView = ScrollView(this).apply {
            addView(horizontalScrollView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(verticalScrollView)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()
        setDialogWindowSize(dialog)
    }

    private fun showMediaPlayerDialog(item: CollectionFileItem) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_media_player)

        val videoView = dialog.findViewById<VideoView>(R.id.dialog_video_view)
        val closeButton = dialog.findViewById<android.widget.ImageButton>(R.id.dialog_close_button)

        val mediaController = MediaController(this)
        videoView.setMediaController(mediaController)
        mediaController.setAnchorView(videoView)
        videoView.setVideoURI(Uri.fromFile(item.file))

        videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoView.start()
            mediaController.show(0)
        }

        videoView.setOnCompletionListener {
            videoView.seekTo(1)
            mediaController.show(0)
        }

        videoView.setOnErrorListener { _, _, _ ->
            true
        }

        closeButton.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            videoView.stopPlayback()
        }

        dialog.show()
    }

    private fun showPrettyJsonDialog(item: CollectionFileItem) {
        val jsonContent = try {
            val raw = item.file.readText()
            JSONObject(raw).toString(2)
        } catch (e: Exception) {
            "Error parsing JSON: ${e.message}"
        }

        val textView = TextView(this).apply {
            text = jsonContent
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()
        setDialogWindowSize(dialog)
    }

    private fun showTextPreviewDialog(item: CollectionFileItem) {
        val textContent = try {
            val lines = item.file.useLines { seq ->
                seq.take(100).joinToString("\n")
            }
            if (item.file.length() > 50000) {
                "$lines\n\n... [Preview truncated to first 100 lines]"
            } else {
                lines
            }
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }

        val textView = TextView(this).apply {
            text = textContent
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()
        setDialogWindowSize(dialog)
    }

    private fun setDialogWindowSize(dialog: AlertDialog) {
        dialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.92).toInt()
            val height = (displayMetrics.heightPixels * 0.85).toInt()
            window.setLayout(width, height)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_DATASET_PATH = "extra_dataset_path"
        const val EXTRA_DATASET_NAME = "extra_dataset_name"
        const val EXTRA_IS_ZIP = "extra_is_zip"
    }
}
