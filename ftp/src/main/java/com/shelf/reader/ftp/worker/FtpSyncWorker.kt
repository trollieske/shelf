package com.shelf.reader.ftp.worker

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
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.ftp.client.FtpClientEngine
import com.shelf.reader.ftp.client.FtpEntryType
import com.shelf.reader.ftp.data.FtpServerStore
import com.shelf.reader.library.data.BookImportRepository
import kotlinx.coroutines.flow.first
import java.io.File

class FtpSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "ftp_sync_channel"
        const val NOTIF_ID = 3001
        private const val WORK_NAME = "shelf_ftp_periodic_sync"

        fun schedule(context: Context) {
            val wifiOnly = kotlinx.coroutines.runBlocking { 
                com.shelf.reader.data.prefs.UserPreferencesRepository(context).ftpWifiOnly.first() 
            }

            val workManager = androidx.work.WorkManager.getInstance(context)
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val req = androidx.work.PeriodicWorkRequestBuilder<FtpSyncWorker>(4, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(appContext)
        val isEnabled = prefs.ftpSyncEnabled.first()
        if (!isEnabled) return Result.success()

        ensureChannel()
        runCatching { setForeground(getForegroundInfo()) }

        val store = FtpServerStore(appContext)
        val servers = store.servers.first()
        if (servers.isEmpty()) return Result.success()

        val engine = FtpClientEngine()
        val db = ShelfDatabase.getInstance(appContext)
        val repo = BookImportRepository(appContext, db, DefaultDispatcherProvider)
        var totalImported = 0

        for (server in servers) {
            val ok = engine.connect(server.server, server.port, server.username, server.password, server.protocol)
            if (!ok) continue

            try {
                val remoteSyncPath = if (server.defaultRemotePath.isNotBlank()) server.defaultRemotePath else "/"
                val fileEntries = engine.listDirectoryRecursive(remoteSyncPath, maxDepth = 4)
                if (fileEntries.isEmpty()) continue

                val serverDir = server.server.replace("[:/\\\\]".toRegex(), "_")
                val downloadDir = File(appContext.filesDir, "ftp/$serverDir").apply { mkdirs() }

                val syncBase = if (remoteSyncPath.isBlank() || remoteSyncPath == "/") "/" else remoteSyncPath.trimEnd('/')
                for (entry in fileEntries) {
                    val basename = entry.path.substringAfterLast('/').ifEmpty { entry.name }
                    val ext = basename.substringAfterLast('.', "").lowercase()
                    if (ext !in listOf("epub", "pdf", "cbz", "cbr", "fb2", "m4b", "mp3", "m4a", "flac", "ogg")) continue

                    val relativePath = if (syncBase != "/" && entry.path.startsWith(syncBase)) {
                        entry.path.removePrefix(syncBase).removePrefix("/")
                    } else {
                        entry.path.removePrefix("/")
                    }
                    val localFile = File(downloadDir, relativePath.ifBlank { entry.name })
                    localFile.parentFile?.mkdirs()

                    if (localFile.exists() && localFile.length() == entry.sizeBytes) {
                        continue
                    }

                    val bytes = engine.downloadFile(entry.path, localFile)
                    if (bytes > 0 && localFile.exists()) {
                        repo.importUris(
                            uris = listOf(Uri.fromFile(localFile)),
                            source = ImportSourceEntity.FTP_DOWNLOAD,
                            serverId = server.id,
                            remotePath = entry.path,
                            filePathOverride = localFile.absolutePath
                        )
                        totalImported++
                    }
                }
            } catch (_: Exception) {
            } finally {
                engine.disconnect()
            }
        }

        if (totalImported > 0) {
            repo.consolidateFragmentedAudiobooks()
        }

        return Result.success()
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("FTP Synkronisering")
                    .setDescription("Viser fremdrift for FTP-nedlastinger")
                    .build()
                nm.createNotificationChannel(chan)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FTP Synkronisering")
            .setContentText("Ser etter nye bøker…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}
