package com.shelf.reader.ftp.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.ftp.client.FtpClientEngine
import com.shelf.reader.ftp.client.FtpEntry
import com.shelf.reader.ftp.client.FtpEntryType
import com.shelf.reader.ftp.client.FtpProtocol
import com.shelf.reader.ftp.data.FtpSavedServer
import com.shelf.reader.ftp.data.FtpServerStore
import com.shelf.reader.library.data.BookImportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

import android.util.Log
import com.shelf.reader.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

enum class SyncStage {
    IDLE,
    CONNECTING,
    SCANNING,
    DOWNLOADING,
    COVER_FETCHING,
    FINISHED,
    FAILED
}

data class FtpUiState(
    val server: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    val protocol: FtpProtocol = FtpProtocol.FTP,
    val usePassiveMode: Boolean = true,
    val currentPath: String = "/",
    val entries: List<FtpEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val downloadProgressText: String? = null,
    val error: String? = null,
    val isConnected: Boolean = false,
    val savedServers: List<FtpSavedServer> = emptyList(),
    val activeServerId: Long? = null,
    val maxConcurrency: Int = 6,
    val activeDownloadsCount: Int = 0,
    val totalFilesToSync: Int = 0,
    val syncedFilesCount: Int = 0,
    val estimatedRemainingSec: Int = 0,
    val syncStage: SyncStage = SyncStage.IDLE,
    val bytesPerSec: Long = 0L,
    val transferredBytesTotal: Long = 0L,
    val totalBytesToSync: Long = 0L,
    val activeFileNames: List<String> = emptyList(),
    val failedFilesCount: Int = 0,
    val retryCount: Int = 0
) {
    val useTls: Boolean
        get() = protocol.isSecure
}

class FtpViewModel(
    application: Application,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val engine = FtpClientEngine()
    private val store = FtpServerStore(application.applicationContext)
    private val prefs = UserPreferencesRepository(application.applicationContext)

    private val formState = MutableStateFlow(FtpUiState())
    private val _state: StateFlow<FtpUiState> =
        combine(formState, store.servers, prefs.ftpMaxConcurrency) { form, servers, maxConc ->
            form.copy(savedServers = servers, maxConcurrency = maxConc)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FtpUiState())
    val state: StateFlow<FtpUiState> = _state

    fun updateMaxConcurrency(count: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpMaxConcurrency(count)
    }

    fun updateServer(server: String) {
        formState.value = formState.value.copy(server = server, activeServerId = null)
    }

    fun updatePort(port: Int) {
        formState.value = formState.value.copy(port = port)
    }

    fun updateUsername(username: String) {
        formState.value = formState.value.copy(username = username)
    }

    fun updateCurrentPath(path: String) {
        val normalized = if (path.isBlank()) "/" else path
        formState.value = formState.value.copy(currentPath = normalized)
        val currentId = formState.value.activeServerId
        if (currentId != null) {
            val server = store.get(currentId) ?: return
            store.save(server.copy(defaultRemotePath = normalized))
        }
    }

    fun updatePassword(password: String) {
        formState.value = formState.value.copy(password = password)
    }

    fun updateProtocol(protocol: FtpProtocol) {
        val current = formState.value
        val newPort = if (current.port == 21 || current.port == 990 || current.port == 22) {
            protocol.defaultPort
        } else {
            current.port
        }
        formState.value = current.copy(protocol = protocol, port = newPort)
    }

    fun updateUsePassiveMode(usePassiveMode: Boolean) {
        formState.value = formState.value.copy(usePassiveMode = usePassiveMode)
        val currentId = formState.value.activeServerId
        if (currentId != null) {
            val server = store.get(currentId) ?: return
            store.save(server.copy(usePassiveMode = usePassiveMode))
        }
    }

    fun updateUseTls(useTls: Boolean) {
        val proto = if (useTls) FtpProtocol.FTPS_EXPLICIT else FtpProtocol.FTP
        updateProtocol(proto)
    }

    fun loadServer(id: Long) {
        val saved = store.get(id) ?: return
        formState.value = formState.value.copy(
            server = saved.server,
            port = saved.port,
            username = saved.username,
            password = saved.password,
            protocol = saved.protocol,
            usePassiveMode = saved.usePassiveMode,
            currentPath = saved.defaultRemotePath,
            activeServerId = saved.id
        )
    }

    fun saveCurrentAs(name: String) {
        if (formState.value.server.isBlank()) return
        val s = FtpSavedServer(
            id = formState.value.activeServerId ?: 0L,
            name = name.ifBlank { formState.value.server },
            server = formState.value.server,
            port = formState.value.port,
            username = formState.value.username,
            password = formState.value.password,
            protocol = formState.value.protocol,
            usePassiveMode = formState.value.usePassiveMode,
            defaultRemotePath = formState.value.currentPath
        )
        val saved = store.save(s)
        formState.value = formState.value.copy(activeServerId = saved.id)
    }

    fun deleteSaved(id: Long) {
        store.delete(id)
        if (formState.value.activeServerId == id) {
            formState.value = formState.value.copy(activeServerId = null)
        }
    }

    fun connect() = viewModelScope.launch(dispatchers.io) {
        val curr = formState.value
        if (curr.server.isBlank()) {
            formState.value = curr.copy(error = "Vennligst oppgi vert / IP-adresse")
            return@launch
        }
        formState.value = curr.copy(isLoading = true, error = null)

        val ok = engine.connect(
            curr.server,
            curr.port,
            curr.username,
            curr.password,
            curr.protocol,
            curr.usePassiveMode
        )

        if (ok) {
            try {
                val pathToList = if (curr.currentPath.isBlank()) "/" else curr.currentPath
                val entries = engine.listDirectory(pathToList)
                formState.value = formState.value.copy(
                    isConnected = true,
                    isLoading = false,
                    entries = entries,
                    error = null
                )
            } catch (e: Exception) {
                formState.value = formState.value.copy(
                    isConnected = true,
                    isLoading = false,
                    entries = emptyList(),
                    error = "Tilkoblet, men kunne ikke hente mappeliste: ${e.message}"
                )
            }
        } else {
            formState.value = formState.value.copy(
                isConnected = false,
                isLoading = false,
                entries = emptyList(),
                error = "Kan ikke koble til serveren (${curr.protocol.displayName} på port ${curr.port}). Sjekk vert, brukernavn og passord."
            )
        }
    }

    fun disconnect() = viewModelScope.launch(dispatchers.io) {
        engine.disconnect()
        formState.value = formState.value.copy(
            isConnected = false,
            entries = emptyList(),
            selected = emptySet()
        )
    }

    fun navigateTo(entry: FtpEntry) = viewModelScope.launch(dispatchers.io) {
        if (entry.type != FtpEntryType.FOLDER) return@launch
        formState.value = formState.value.copy(isLoading = true, error = null)
        try {
            val entries = engine.listDirectory(entry.path)
            formState.value = formState.value.copy(
                currentPath = entry.path,
                entries = entries,
                selected = emptySet(),
                isLoading = false
            )
        } catch (e: Exception) {
            formState.value = formState.value.copy(
                isLoading = false,
                error = "Kunne ikke åpne mappen '${entry.name}': ${e.message}"
            )
        }
    }

    fun navigateUp() = viewModelScope.launch(dispatchers.io) {
        val current = formState.value.currentPath
        if (current == "/" || current.isBlank()) return@launch
        val trimmed = current.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        val parentPath = if (lastSlash <= 0) "/" else trimmed.substring(0, lastSlash)
        formState.value = formState.value.copy(isLoading = true, error = null)
        try {
            val entries = engine.listDirectory(parentPath)
            formState.value = formState.value.copy(
                currentPath = parentPath,
                entries = entries,
                selected = emptySet(),
                isLoading = false
            )
        } catch (e: Exception) {
            formState.value = formState.value.copy(
                isLoading = false,
                error = "Kunne ikke gå opp i mappen: ${e.message}"
            )
        }
    }

    fun navigateTo(targetPath: String) = viewModelScope.launch(dispatchers.io) {
        val normalized = if (targetPath.isBlank()) "/" else targetPath.trimEnd('/').ifBlank { "/" }
        formState.value = formState.value.copy(isLoading = true, error = null)
        try {
            val entries = engine.listDirectory(normalized)
            formState.value = formState.value.copy(
                currentPath = normalized,
                entries = entries,
                selected = emptySet(),
                isLoading = false
            )
        } catch (e: Exception) {
            formState.value = formState.value.copy(
                isLoading = false,
                error = "Kunne ikke navigere til '${normalized}': ${e.message}"
            )
        }
    }

    fun toggleSelect(name: String) {
        val current = formState.value.selected
        val newSelected = if (current.contains(name)) {
            current - name
        } else {
            current + name
        }
        formState.value = formState.value.copy(selected = newSelected)
    }

    fun updateDefaultRemotePath(path: String) = viewModelScope.launch(dispatchers.io) {
        val currentId = formState.value.activeServerId
        if (currentId != null) {
            val server = store.get(currentId) ?: return@launch
            store.save(server.copy(defaultRemotePath = path))
            formState.value = formState.value.copy(currentPath = path)
        } else {
            formState.value = formState.value.copy(currentPath = path)
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        formState.value = formState.value.copy(
            isLoading = false,
            syncStage = SyncStage.IDLE,
            downloadProgressText = null,
            activeDownloadsCount = 0,
            activeFileNames = emptyList(),
            error = "Synkronisering avbrutt"
        )
    }

    fun syncCurrentFolderNow(ctx: Context, onResult: (Int) -> Unit = {}) {
        val curr = formState.value
        syncJob?.cancel()
        syncJob = viewModelScope.launch(dispatchers.io) {
            performParallelSync(
                ctx = ctx,
                serverHost = curr.server,
                port = curr.port,
                user = curr.username,
                pass = curr.password,
                protocol = curr.protocol,
                usePassiveMode = curr.usePassiveMode,
                serverId = curr.activeServerId,
                remoteSyncPath = curr.currentPath.ifBlank { "/" },
                onResult = onResult
            )
        }
    }

    fun syncServerNow(serverId: Long, ctx: Context, onResult: (Int) -> Unit = {}) = viewModelScope.launch(dispatchers.io) {
        val server = store.servers.value.firstOrNull { it.id == serverId } ?: return@launch
        syncJob?.cancel()
        syncJob = viewModelScope.launch(dispatchers.io) {
            performParallelSync(
                ctx = ctx,
                serverHost = server.server,
                port = server.port,
                user = server.username,
                pass = server.password,
                protocol = server.protocol,
                usePassiveMode = server.usePassiveMode,
                serverId = server.id,
                remoteSyncPath = if (server.defaultRemotePath.isNotBlank()) server.defaultRemotePath else "/",
                onResult = onResult
            )
        }
    }

    private suspend fun performParallelSync(
        ctx: Context,
        serverHost: String,
        port: Int,
        user: String,
        pass: String,
        protocol: FtpProtocol,
        usePassiveMode: Boolean,
        serverId: Long?,
        remoteSyncPath: String,
        onResult: (Int) -> Unit
    ) {
        val concurrency = formState.value.maxConcurrency.coerceIn(1, 15)
        val semaphore = Semaphore(concurrency)

        formState.value = formState.value.copy(
            isLoading = true,
            syncStage = SyncStage.CONNECTING,
            downloadProgressText = "Kobler til FTP-server...",
            error = null
        )

        val allFiles = try {
            if (!engine.isConnected && serverHost.isNotBlank()) {
                engine.connect(serverHost, port, user, pass, protocol, usePassiveMode)
            }
            formState.value = formState.value.copy(
                syncStage = SyncStage.SCANNING,
                downloadProgressText = "Sammenligner og analyserer filer i '$remoteSyncPath'..."
            )
            engine.listDirectoryRecursive(remoteSyncPath, maxDepth = 4)
        } catch (e: Exception) {
            formState.value = formState.value.copy(
                isLoading = false,
                syncStage = SyncStage.IDLE,
                downloadProgressText = null,
                error = "Kunne ikke hente filliste: ${e.message}"
            )
            onResult(0)
            return
        }

        val bookExtensions = setOf("epub", "pdf", "cbz", "cbr", "fb2", "m4b", "mp3", "m4a", "flac", "ogg", "opus", "wav")
        val bookFiles = allFiles.filter { entry ->
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            ext in bookExtensions
        }

        if (bookFiles.isEmpty()) {
            formState.value = formState.value.copy(
                isLoading = false,
                syncStage = SyncStage.IDLE,
                downloadProgressText = null,
                error = "Fant ingen lydbøker eller e-bøker i '$remoteSyncPath'"
            )
            onResult(0)
            return
        }

        val totalFiles = bookFiles.size
        val totalBytesExpected = bookFiles.sumOf { it.sizeBytes }
        val serverDir = (serverHost.ifBlank { "server" }).replace("[:/\\\\]".toRegex(), "_")
        val downloadDir = File(ctx.filesDir, "ftp/$serverDir").apply { mkdirs() }
        downloadDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
        val db = ShelfDatabase.getInstance(ctx)
        val repo = BookImportRepository(ctx, db, DefaultDispatcherProvider)

        val completedCount = AtomicInteger(0)
        val activeWorkersCount = AtomicInteger(0)
        val importedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val totalRetriesCount = AtomicInteger(0)
        val totalBytesDownloaded = java.util.concurrent.atomic.AtomicLong(0L)
        val activeNamesSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        val startTimeMs = System.currentTimeMillis()

        formState.value = formState.value.copy(
            syncStage = SyncStage.DOWNLOADING,
            totalFilesToSync = totalFiles,
            syncedFilesCount = 0,
            totalBytesToSync = totalBytesExpected,
            transferredBytesTotal = 0L,
            bytesPerSec = 0L,
            activeFileNames = emptyList(),
            failedFilesCount = 0,
            retryCount = 0,
            downloadProgressText = "Synkroniserer (0/$totalFiles) • 0 aktiver • Beregner tid..."
        )

        coroutineScope {
            val jobs = bookFiles.map { fileEntry ->
                launch(dispatchers.io) {
                    semaphore.withPermit {
                        val activeNow = activeWorkersCount.incrementAndGet()
                        activeNamesSet.add(fileEntry.name)
                        try {
                            val syncBase = if (remoteSyncPath.isBlank() || remoteSyncPath == "/") "/" else remoteSyncPath.trimEnd('/')
                            val relativePath = if (syncBase != "/" && fileEntry.path.startsWith(syncBase)) {
                                fileEntry.path.removePrefix(syncBase).removePrefix("/")
                            } else {
                                fileEntry.path.removePrefix("/")
                            }
                            val localFile = File(downloadDir, relativePath.ifBlank { fileEntry.name })
                            localFile.parentFile?.mkdirs()

                            val fileNeedsDownload = !localFile.exists() || (fileEntry.sizeBytes > 0L && localFile.length() != fileEntry.sizeBytes)

                            var success = false
                            if (!fileNeedsDownload) {
                                val existing = db.bookDao().getByPath(localFile.absolutePath)
                                if (existing == null) {
                                    repo.importUris(
                                        uris = listOf(Uri.fromFile(localFile)),
                                        source = ImportSourceEntity.FTP_DOWNLOAD,
                                        serverId = serverId,
                                        remotePath = fileEntry.path,
                                        filePathOverride = localFile.absolutePath
                                    )
                                    importedCount.incrementAndGet()
                                }
                                totalBytesDownloaded.addAndGet(localFile.length())
                                success = true
                            } else {
                                var attempts = 0
                                val maxAttempts = 3
                                while (attempts < maxAttempts && !success) {
                                    attempts++
                                    if (attempts > 1) {
                                        totalRetriesCount.incrementAndGet()
                                    }
                                    val workerEngine = FtpClientEngine()
                                    try {
                                        val connected = workerEngine.connect(serverHost, port, user, pass, protocol, usePassiveMode)
                                        if (connected) {
                                            val bytes = workerEngine.downloadFile(fileEntry.path, localFile)
                                            if (bytes > 0 && localFile.exists()) {
                                                totalBytesDownloaded.addAndGet(bytes)
                                                repo.importUris(
                                                    uris = listOf(Uri.fromFile(localFile)),
                                                    source = ImportSourceEntity.FTP_DOWNLOAD,
                                                    serverId = serverId,
                                                    remotePath = fileEntry.path,
                                                    filePathOverride = localFile.absolutePath
                                                )
                                                importedCount.incrementAndGet()
                                                success = true
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("FtpParallelSync", "Forsøk $attempts feilet for ${fileEntry.name}: ${e.message}")
                                    } finally {
                                        workerEngine.disconnect()
                                    }

                                    if (!success && attempts < maxAttempts) {
                                        kotlinx.coroutines.delay(attempts * 500L)
                                    }
                                }
                            }

                            if (!success) {
                                failedCount.incrementAndGet()
                            }
                        } finally {
                            activeNamesSet.remove(fileEntry.name)
                            val curActive = activeWorkersCount.decrementAndGet()
                            val doneCount = completedCount.incrementAndGet()
                            val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)
                            val bytesTotal = totalBytesDownloaded.get()
                            val speedBps = (bytesTotal * 1000L / elapsedMs).coerceAtLeast(0L)
                            val filesPerSec = (doneCount.toDouble() / (elapsedMs / 1000.0)).coerceAtLeast(0.01)
                            val remainingFiles = totalFiles - doneCount
                            val etaSec = (remainingFiles / filesPerSec).toInt()

                            val etaString = if (etaSec > 60) "~${etaSec / 60}m ${etaSec % 60}s gjenstår" else "~$etaSec sek gjenstår"

                            formState.value = formState.value.copy(
                                syncedFilesCount = doneCount,
                                activeDownloadsCount = curActive,
                                estimatedRemainingSec = etaSec,
                                bytesPerSec = speedBps,
                                transferredBytesTotal = bytesTotal,
                                activeFileNames = activeNamesSet.toList().take(2),
                                failedFilesCount = failedCount.get(),
                                retryCount = totalRetriesCount.get(),
                                downloadProgressText = "Synkroniserer ($doneCount/$totalFiles) • $curActive aktiver • $etaString"
                            )
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        formState.value = formState.value.copy(
            syncStage = SyncStage.COVER_FETCHING,
            downloadProgressText = "Samler og oppdaterer lydbok-kapitler og bokomslag..."
        )
        repo.consolidateFragmentedAudiobooks()

        val totalImported = importedCount.get()
        val totalFailed = failedCount.get()

        formState.value = formState.value.copy(
            isLoading = false,
            syncStage = SyncStage.IDLE,
            downloadProgressText = null,
            activeDownloadsCount = 0,
            activeFileNames = emptyList(),
            error = if (totalFailed > 0) "$totalImported filer synkronisert ($totalFailed feilet)" else null
        )

        onResult(totalImported)
    }

    fun downloadSelected(ctx: Context, onComplete: (Int) -> Unit = {}) = viewModelScope.launch(dispatchers.io) {
        val curr = formState.value
        formState.value = curr.copy(isLoading = true, downloadProgressText = "Forbereder nedlasting...", error = null)
        try {
            if (!engine.isConnected) {
                val ok = engine.connect(
                    curr.server,
                    curr.port,
                    curr.username,
                    curr.password,
                    curr.protocol
                )
                if (!ok) {
                    formState.value = formState.value.copy(
                        isLoading = false,
                        downloadProgressText = null,
                        error = "Kan ikke koble til for nedlasting"
                    )
                    return@launch
                }
            }

            val selectedNames = formState.value.selected
            val targetEntries = formState.value.entries.filter { it.name in selectedNames && it.type == FtpEntryType.FILE }
            val serverDir = curr.server.replace("[:/\\\\]".toRegex(), "_")
            val downloadDir = File(ctx.filesDir, "ftp/$serverDir").apply { mkdirs() }
            var successCount = 0

            for ((idx, entry) in targetEntries.withIndex()) {
                formState.value = formState.value.copy(
                    downloadProgressText = "Laster ned (${idx + 1}/${targetEntries.size}): ${entry.name}"
                )
                val syncBase = if (curr.currentPath.isBlank() || curr.currentPath == "/") "/" else curr.currentPath.trimEnd('/')
                val relativePath = if (syncBase != "/" && entry.path.startsWith(syncBase)) {
                    entry.path.removePrefix(syncBase).removePrefix("/")
                } else {
                    entry.path.removePrefix("/")
                }
                val localFile = File(downloadDir, relativePath.ifBlank { entry.name })
                localFile.parentFile?.mkdirs()
                val bytes = engine.downloadFile(entry.path, localFile)
                if (bytes > 0 && localFile.exists()) {
                    val db = ShelfDatabase.getInstance(ctx)
                    val repo = BookImportRepository(ctx, db, DefaultDispatcherProvider)
                    repo.importUris(
                        uris = listOf(Uri.fromFile(localFile)),
                        source = ImportSourceEntity.FTP_DOWNLOAD,
                        serverId = curr.activeServerId,
                        remotePath = entry.path,
                        filePathOverride = localFile.absolutePath
                    )
                    successCount++
                }
            }

            if (successCount > 0) {
                formState.value = formState.value.copy(
                    downloadProgressText = "Samler lydbok-kapitler..."
                )
                val db = ShelfDatabase.getInstance(ctx)
                val repo = BookImportRepository(ctx, db, DefaultDispatcherProvider)
                repo.consolidateFragmentedAudiobooks()
            }

            formState.value = formState.value.copy(
                selected = emptySet(),
                downloadProgressText = null,
                error = if (successCount == 0 && targetEntries.isNotEmpty()) "Nedlasting feilet for valgte filer" else null
            )
            onComplete(successCount)
        } catch (e: Exception) {
            formState.value = formState.value.copy(
                downloadProgressText = null,
                error = "Nedlasting feilet: ${e.message}"
            )
        } finally {
            formState.value = formState.value.copy(isLoading = false, downloadProgressText = null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(dispatchers.io) {
            engine.disconnect()
        }
    }
}
