package com.shelf.reader.smb.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.smb.client.SmbClientEngine
import com.shelf.reader.smb.client.SmbEntryType
import com.shelf.reader.smb.data.SmbServerStore
import com.shelf.reader.library.data.BookImportRepository
import kotlinx.coroutines.flow.first
import java.io.File

class SmbSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "smb_sync_channel"
        const val NOTIF_ID = 3002
        private const val WORK_NAME = "shelf_smb_periodic_sync"

        fun schedule(context: Context, wifiOnly: Boolean = true, intervalHours: Long = 4) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val req = androidx.work.PeriodicWorkRequestBuilder<SmbSyncWorker>(
                intervalHours, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun runNow(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val req = androidx.work.OneTimeWorkRequestBuilder<SmbSyncWorker>()
                .addTag("smb_sync_now")
                .build()
            workManager.enqueue(req)
        }
    }

    override suspend fun doWork(): Result {
        ensureChannel()
        runCatching { setForeground(getForegroundInfo()) }

        val store = SmbServerStore(appContext)
        val servers = store.servers.first()
        if (servers.isEmpty()) return Result.success()

        val engine = SmbClientEngine()
        val db = ShelfDatabase.getInstance(appContext)
        val repo = BookImportRepository(appContext, db, DefaultDispatcherProvider)
        var totalImported = 0
        val now = System.currentTimeMillis()

        for (server in servers) {
            val ok = engine.connect(
                host = server.host,
                port = server.port,
                shareName = server.shareName,
                domain = server.domain,
                username = server.username,
                password = server.password,
                smbVersion = server.smbVersion,
                enableEncryption = server.enableEncryption
            )
            if (!ok) continue

            try {
                val entries = engine.listDirectory(server.defaultRemotePath)
                val fileEntries = entries.filter { it.type == SmbEntryType.FILE }
                if (fileEntries.isEmpty()) continue

                val dirName = "${server.host}_${server.shareName}".replace("[:/\\\\]".toRegex(), "_")
                val downloadDir = File(appContext.filesDir, "smb/$dirName").apply { mkdirs() }
                val importedUris = mutableListOf<Uri>()

                for (entry in fileEntries) {
                    val basename = entry.path.substringAfterLast('/').ifEmpty { entry.name }
                    val ext = basename.substringAfterLast('.', "").lowercase()
                    if (ext !in setOf("epub", "pdf", "cbz", "cbr", "fb2", "m4b", "m4a", "mp3", "aac", "flac", "mobi", "azw", "azw3", "txt")) continue

                    val localFile = File(downloadDir, basename)
                    if (localFile.exists() && localFile.length() == entry.sizeBytes) continue

                    val bytes = engine.downloadFile(entry.path, localFile)
                    if (bytes > 0) importedUris.add(Uri.fromFile(localFile))
                }

                if (importedUris.isNotEmpty()) {
                    repo.importUris(importedUris, ImportSourceEntity.SMB_DOWNLOAD)
                    totalImported += importedUris.size
                }

                runCatching { db.smbServerDao().markSynced(0, now) }
            } catch (_: Exception) {
            } finally {
                runCatching { engine.disconnect() }
            }
        }

        return Result.success()
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("SMB Synkronisering")
                    .setDescription("SMB/Windows-fildeling synkronisering")
                    .build()
                nm.createNotificationChannel(chan)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SMB Synkronisering")
            .setContentText("Ser etter nye bøker på nettverksdisker…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}
