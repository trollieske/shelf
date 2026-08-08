package com.shelf.reader.torrent.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.torrent.engine.TorrentEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class TorrentDownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "torrent_channel"
        const val NOTIF_ID = 3004
        private const val WORK_NAME = "shelf_torrent_worker"

        fun schedule(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val req = androidx.work.PeriodicWorkRequestBuilder<TorrentDownloadWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun runNow(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val req = androidx.work.OneTimeWorkRequestBuilder<TorrentDownloadWorker>()
                .addTag("torrent_run_now")
                .build()
            workManager.enqueue(req)
        }
    }

    override suspend fun doWork(): Result {
        ensureChannel()
        runCatching { setForeground(getForegroundInfo()) }

        val db = ShelfDatabase.getInstance(appContext)
        val engine = TorrentEngine.getInstance(appContext)
        engine.start()

        val timeoutMs = 10 * 60 * 1000L
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val hasActive = try {
                val running = db.torrentDownloadDao().getRunning()
                val nextPending = db.torrentDownloadDao().getNextPending()
                running.isNotEmpty() || nextPending != null
            } catch (_: Exception) { false }

            if (!hasActive) {
                break
            }
            delay(5000L)
        }

        return Result.success()
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Torrent Nedlastinger")
                    .setDescription("Viser fremdrift for torrent-nedlastinger")
                    .build()
                nm.createNotificationChannel(chan)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Torrent-nedlastinger")
            .setContentText("Torrent-klient kjører i bakgrunnen…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}
