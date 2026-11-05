package org.rjpd.msdc

import android.content.Context
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import timber.log.Timber

internal const val PREF_AUTO_UPLOAD = "auto_upload"
internal const val PREF_UPLOAD_WIFI_ONLY = "upload_wifi_only"
internal const val PREF_UPLOAD_CHARGING_ONLY = "upload_charging_only"
private const val TAG = "SyncWorker"
private const val WORK_NAME = "msdc-sync"

/**
 * Background sync under the user's network and charging constraints. It always finishes what a killed
 * sync left behind (queued collections and unfinished multipart uploads) and, with automatic upload on,
 * every pending collection. Without a foreground service it gets a limited run; uploads are saved part by
 * part, so a stopped run resumes where it was.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cancellationContext = currentCoroutineContext()
        val context = applicationContext
        if (CloudSyncManager.getCloudSyncMode(context) == CloudSyncMode.NOT_CONFIGURED) {
            return Result.success()
        }
        if (CloudSyncManager.recordingInProgress || !CloudSyncManager.syncMutex.tryLock()) {
            return Result.retry()
        }
        var started = false
        var successCount = 0
        var failureCount = 0

        try {
            val autoUpload = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO_UPLOAD, false)
            val attempted = mutableSetOf<String>()

            while (true) {
                cancellationContext.ensureActive()
                // Rescanned after each pass so collections recorded meanwhile are picked up. A queued
                // collection may already be synced: the user asked to sync it again.
                val pending = scanCollectedDatasets(context).filter {
                    it.name !in attempted && (CloudSyncManager.isDatasetQueued(context, it.name) ||
                            autoUpload && !CloudSyncManager.isDatasetSynced(context, it.name))
                }
                cancellationContext.ensureActive()
                if (pending.isEmpty()) return Result.success()

                if (!started) {
                    started = true
                    CloudSyncManager.startProgress(pending.size, pending.first().name)
                }

                for ((index, dataset) in pending.withIndex()) {
                    cancellationContext.ensureActive()
                    if (CloudSyncManager.recordingInProgress) return Result.retry()

                    attempted += dataset.name
                    CloudSyncManager.updateProgress(index + 1, pending.size, dataset.name)

                    var lastUpdate = 0L
                    val result = CloudSyncManager.syncDataset(context, dataset) { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 500 || done == total) {
                            lastUpdate = now
                            CloudSyncManager.updateBytes(done, total)
                        }
                    }

                    cancellationContext.ensureActive()
                    CloudSyncManager.recordDatasetCompleted(dataset.name, result)
                    if (result.success) successCount++ else failureCount++
                    if (result.stopRun) {
                        Timber.tag(TAG).w("Stopping background sync: ${result.message}")
                        return Result.retry()
                    }

                    CloudSyncManager.dequeueDataset(context, dataset.name)
                }
            }
        } finally {
            if (started) {
                if (cancellationContext.isActive) CloudSyncManager.finishSync(successCount, failureCount)
                else CloudSyncManager.resetProgress()
            }
            CloudSyncManager.syncMutex.unlock()
        }
    }

    companion object {
        /** [replace] applies changed constraints; otherwise an already scheduled run is kept. */
        fun schedule(context: Context, replace: Boolean = false) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (prefs.getBoolean(PREF_UPLOAD_WIFI_ONLY, true)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(prefs.getBoolean(PREF_UPLOAD_CHARGING_ONLY, false))
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
