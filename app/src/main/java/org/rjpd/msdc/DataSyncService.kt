package org.rjpd.msdc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

private const val TAG = "DataSyncService"
private const val CHANNEL_ID = "DataSyncChannel"
private const val NOTIFICATION_ID = 4001
private const val COMPLETION_NOTIFICATION_ID = 4002

class DataSyncService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var activeSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification(
            title = getString(R.string.data_sync_notification_title),
            content = getString(R.string.data_sync_in_progress),
            current = 0,
            total = 0,
            indeterminate = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        when (intent?.action) {
            ACTION_SYNC_ALL -> performSyncAll()
            ACTION_SYNC_SINGLE -> {
                val datasetName = intent.getStringExtra(EXTRA_DATASET_NAME) ?: ""
                performSyncSingle(datasetName)
            }
            else -> stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.data_sync_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.data_sync_notification_title)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    private fun buildNotification(
        title: String,
        content: String,
        current: Int,
        total: Int,
        indeterminate: Boolean = false
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HistoryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0) {
            builder.setProgress(total, current, indeterminate)
        } else if (indeterminate) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotificationProgress(current: Int, total: Int, datasetName: String) {
        val content = if (total > 1) {
            getString(R.string.sync_all_in_progress_format, current, total, datasetName)
        } else {
            getString(R.string.sync_in_progress_msg, datasetName)
        }

        val notification = buildNotification(
            title = getString(R.string.data_sync_notification_title),
            content = content,
            current = current,
            total = total
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(message: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HistoryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.data_sync_notification_title))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun performSyncAll() {
        if (activeSyncJob?.isActive == true) {
            Timber.tag(TAG).d("Sync job is already running, skipping")
            return
        }

        activeSyncJob = serviceScope.launch {
            try {
                val datasets = scanCollectedDatasets(this@DataSyncService)
                val pendingDatasets = datasets.filter { !CloudSyncManager.isDatasetSynced(this@DataSyncService, it.name) }

                if (pendingDatasets.isEmpty()) {
                    CloudSyncManager.finishSync(0, 0)
                    return@launch
                }

                val total = pendingDatasets.size
                CloudSyncManager.startProgress(total, pendingDatasets.first().name)
                updateNotificationProgress(1, total, pendingDatasets.first().name)

                var successCount = 0
                var failureCount = 0

                for ((index, dataset) in pendingDatasets.withIndex()) {
                    val current = index + 1
                    CloudSyncManager.updateProgress(current, total, dataset.name)
                    updateNotificationProgress(current, total, dataset.name)

                    val result = CloudSyncManager.syncDataset(this@DataSyncService, dataset)
                    if (result.success) {
                        successCount++
                    } else {
                        failureCount++
                    }
                    CloudSyncManager.recordDatasetCompleted(dataset.name, result)
                }

                CloudSyncManager.finishSync(successCount, failureCount)
                val completionMsg = if (failureCount == 0) {
                    getString(R.string.sync_all_summary_format, total)
                } else {
                    getString(R.string.sync_all_partial_failure_format, successCount, failureCount)
                }
                showCompletionNotification(completionMsg)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error occurred during sync all")
                CloudSyncManager.finishSync(0, 1)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun performSyncSingle(datasetName: String) {
        if (activeSyncJob?.isActive == true) {
            Timber.tag(TAG).d("Sync job is already running, skipping")
            return
        }

        activeSyncJob = serviceScope.launch {
            try {
                val datasets = scanCollectedDatasets(this@DataSyncService)
                val target = datasets.firstOrNull { it.name == datasetName }
                if (target == null) {
                    Timber.tag(TAG).w("Dataset $datasetName not found for single sync")
                    CloudSyncManager.finishSync(0, 1)
                    return@launch
                }

                CloudSyncManager.startProgress(1, target.name)
                updateNotificationProgress(1, 1, target.name)

                val result = CloudSyncManager.syncDataset(this@DataSyncService, target)
                val successCount = if (result.success) 1 else 0
                val failureCount = if (result.success) 0 else 1
                CloudSyncManager.recordDatasetCompleted(target.name, result)
                CloudSyncManager.finishSync(successCount, failureCount)

                if (result.success) {
                    showCompletionNotification(
                        getString(R.string.collection_synced_toast, target.name)
                    )
                } else {
                    showCompletionNotification(
                        getString(R.string.sync_single_failed_format, target.name, result.message)
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error occurred during single sync")
                CloudSyncManager.finishSync(0, 1)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    companion object {
        const val ACTION_SYNC_ALL = "org.rjpd.msdc.action.SYNC_ALL"
        const val ACTION_SYNC_SINGLE = "org.rjpd.msdc.action.SYNC_SINGLE"
        const val EXTRA_DATASET_NAME = "org.rjpd.msdc.extra.DATASET_NAME"

        fun startSyncAll(context: Context) {
            val intent = Intent(context, DataSyncService::class.java).apply {
                action = ACTION_SYNC_ALL
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSyncSingle(context: Context, datasetName: String) {
            val intent = Intent(context, DataSyncService::class.java).apply {
                action = ACTION_SYNC_SINGLE
                putExtra(EXTRA_DATASET_NAME, datasetName)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
