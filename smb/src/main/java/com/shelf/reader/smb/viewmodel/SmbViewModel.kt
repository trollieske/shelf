package com.shelf.reader.smb.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.data.local.entity.SmbServerEntity
import com.shelf.reader.library.data.BookImportRepository
import com.shelf.reader.smb.client.SmbClientEngine
import com.shelf.reader.smb.client.SmbEntry
import com.shelf.reader.smb.client.SmbEntryType
import com.shelf.reader.smb.data.SmbSavedServer
import com.shelf.reader.smb.data.SmbServerStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class SmbUiState(
    val displayName: String = "",
    val host: String = "",
    val port: Int = 445,
    val shareName: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val smbVersion: String = "AUTO",
    val enableEncryption: Boolean = false,

    val currentPath: String = "/",
    val entries: List<SmbEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConnected: Boolean = false,
    val savedServers: List<SmbSavedServer> = emptyList(),
    val activeServerId: Long? = null,
    val downloading: Map<String, Float> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
class SmbViewModel(
    application: Application,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val engine = SmbClientEngine()
    private val store = SmbServerStore(application.applicationContext)
    private val db = ShelfDatabase.getInstance(application.applicationContext)
    private val importRepo = BookImportRepository(application.applicationContext, db, dispatchers)

    private val formState = MutableStateFlow(SmbUiState())
    private val _state: StateFlow<SmbUiState> =
        combine(formState, store.servers) { form, servers ->
            form.copy(savedServers = servers)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SmbUiState())
    val state: StateFlow<SmbUiState> = _state

    fun updateDisplayName(v: String) { formState.value = formState.value.copy(displayName = v) }
    fun updateHost(v: String) { formState.value = formState.value.copy(host = v, activeServerId = null) }
    fun updatePort(v: Int) { formState.value = formState.value.copy(port = v.coerceIn(1, 65535)) }
    fun updateShareName(v: String) { formState.value = formState.value.copy(shareName = v) }
    fun updateDomain(v: String) { formState.value = formState.value.copy(domain = v) }
    fun updateUsername(v: String) { formState.value = formState.value.copy(username = v) }
    fun updatePassword(v: String) { formState.value = formState.value.copy(password = v) }
    fun updateSmbVersion(v: String) { formState.value = formState.value.copy(smbVersion = v) }
    fun updateEnableEncryption(v: Boolean) { formState.value = formState.value.copy(enableEncryption = v) }

    fun toggleSelected(path: String) {
        val current = formState.value.selected.toMutableSet()
        if (current.contains(path)) current.remove(path) else current.add(path)
        formState.value = formState.value.copy(selected = current)
    }

    fun clearSelection() { formState.value = formState.value.copy(selected = emptySet()) }

    fun loadServer(id: Long) {
        val saved = store.get(id) ?: return
        formState.value = formState.value.copy(
            displayName = saved.displayName,
            host = saved.host,
            port = saved.port,
            shareName = saved.shareName,
            domain = saved.domain ?: "",
            username = saved.username,
            password = saved.password,
            smbVersion = saved.smbVersion,
            enableEncryption = saved.enableEncryption,
            currentPath = saved.defaultRemotePath,
            activeServerId = saved.id
        )
    }

    fun saveCurrentAs(name: String? = null) {
        if (formState.value.host.isBlank() || formState.value.shareName.isBlank()) return
        val s = SmbSavedServer(
            id = formState.value.activeServerId ?: 0L,
            displayName = name?.ifBlank { formState.value.host } ?: formState.value.displayName.ifBlank { formState.value.host },
            host = formState.value.host,
            port = formState.value.port,
            shareName = formState.value.shareName,
            domain = formState.value.domain.ifBlank { null },
            username = formState.value.username,
            password = formState.value.password,
            smbVersion = formState.value.smbVersion,
            enableEncryption = formState.value.enableEncryption,
            defaultRemotePath = formState.value.currentPath
        )
        val saved = store.save(s)
        formState.value = formState.value.copy(activeServerId = saved.id, displayName = saved.displayName)
    }

    fun deleteSaved(id: Long) {
        store.delete(id)
        if (formState.value.activeServerId == id) {
            formState.value = formState.value.copy(activeServerId = null)
        }
    }

    fun connect() = viewModelScope.launch(dispatchers.io) {
        formState.value = formState.value.copy(isLoading = true, error = null)
        val ok = engine.connect(
            host = formState.value.host,
            port = formState.value.port,
            shareName = formState.value.shareName,
            domain = formState.value.domain.ifBlank { null },
            username = formState.value.username,
            password = formState.value.password,
            smbVersion = formState.value.smbVersion,
            enableEncryption = formState.value.enableEncryption
        )
        val entries = if (ok) engine.listDirectory(formState.value.currentPath) else emptyList()
        formState.value = formState.value.copy(
            isConnected = ok,
            isLoading = false,
            entries = entries,
            error = if (!ok) "Kan ikke koble til" else null
        )
    }

    fun disconnect() = viewModelScope.launch(dispatchers.io) {
        engine.disconnect()
        formState.value = formState.value.copy(
            isConnected = false,
            entries = emptyList(),
            selected = emptySet()
        )
    }

    fun navigateTo(entry: SmbEntry) = viewModelScope.launch(dispatchers.io) {
        if (entry.type != SmbEntryType.FOLDER) return@launch
        formState.value = formState.value.copy(isLoading = true, error = null)
        val entries = engine.listDirectory(entry.path)
        formState.value = formState.value.copy(
            currentPath = entry.path,
            entries = entries,
            isLoading = false,
            selected = emptySet()
        )
    }

    fun navigateUp() = viewModelScope.launch(dispatchers.io) {
        val current = formState.value.currentPath
        if (current == "/" || current.isBlank()) return@launch
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        formState.value = formState.value.copy(isLoading = true)
        val entries = engine.listDirectory(parent)
        formState.value = formState.value.copy(currentPath = parent, entries = entries, isLoading = false, selected = emptySet())
    }

    fun downloadAndImportSelected() = viewModelScope.launch(dispatchers.io) {
        val selected = formState.value.selected.toList()
        if (selected.isEmpty()) return@launch
        val downloadsDir = getDownloadsDir()
        val results = mutableListOf<Long>()

        selected.forEach { remotePath ->
            val name = remotePath.substringAfterLast('/')
            if (!engine.matchesFormat(name)) return@forEach

            val localFile = File(downloadsDir, "smb_${System.currentTimeMillis()}_$name")
            formState.value = formState.value.copy(
                downloading = formState.value.downloading + (remotePath to 0f)
            )

            val taskId = db.downloadTaskDao().insert(
                com.shelf.reader.data.local.entity.DownloadTaskEntity(
                    remotePath = remotePath,
                    remoteName = name,
                    localPath = localFile.absolutePath,
                    status = DownloadStatusEntity.RUNNING,
                    autoImport = true
                )
            )

            val bytes = engine.downloadFile(remotePath, localFile) { downloaded, total ->
                val pct = if (total > 0) downloaded.toFloat() / total else 0f
                formState.value = formState.value.copy(
                    downloading = formState.value.downloading + (remotePath to pct)
                )
            }

            if (bytes > 0) {
                db.downloadTaskDao().update(
                    db.downloadTaskDao().getById(taskId)?.copy(
                        sizeBytes = bytes,
                        downloadedBytes = bytes,
                        status = DownloadStatusEntity.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    ) ?: return@forEach
                )
                val imported = importRepo.importUris(
                    listOf(android.net.Uri.fromFile(localFile)),
                    source = ImportSourceEntity.SMB_DOWNLOAD,
                    serverId = formState.value.activeServerId,
                    remotePath = remotePath,
                    filePathOverride = localFile.absolutePath
                )
                results.addAll(imported)
            } else {
                db.downloadTaskDao().update(
                    db.downloadTaskDao().getById(taskId)?.copy(
                        status = DownloadStatusEntity.FAILED,
                        errorMessage = "Nedlasting feilet"
                    ) ?: return@forEach
                )
            }

            val newMap = formState.value.downloading.toMutableMap()
            newMap.remove(remotePath)
            formState.value = formState.value.copy(downloading = newMap)
        }

        formState.value = formState.value.copy(selected = emptySet())
    }

    fun downloadAndImportCurrentFolder() = viewModelScope.launch(dispatchers.io) {
        val formatFiles = formState.value.entries
            .filter { it.type == SmbEntryType.FILE && engine.matchesFormat(it.name) }
            .map { it.path }
        if (formatFiles.isEmpty()) return@launch
        formState.value = formState.value.copy(selected = formatFiles.toSet())
        downloadAndImportSelected()
    }

    private fun getDownloadsDir(): File {
        val ctx = getApplication<Application>().applicationContext
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "shelf_smb")
        dir.mkdirs()
        return dir
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(dispatchers.io) {
            runCatching { engine.disconnect() }
        }
    }
}
