package com.shelf.reader.webdav.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.library.data.BookImportRepository
import com.shelf.reader.webdav.client.WebdavClientEngine
import com.shelf.reader.webdav.client.WebdavEntry
import com.shelf.reader.webdav.client.WebdavEntryType
import com.shelf.reader.webdav.data.WebdavSavedServer
import com.shelf.reader.webdav.data.WebdavServerStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class WebdavUiState(
    val displayName: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val bearerToken: String = "",
    val authType: String = "BASIC",
    val trustAllCertificates: Boolean = false,
    val basePath: String = "/remote.php/dav/files/",

    val currentPath: String = "/",
    val entries: List<WebdavEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConnected: Boolean = false,
    val savedServers: List<WebdavSavedServer> = emptyList(),
    val activeServerId: Long? = null,
    val downloading: Map<String, Float> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
class WebdavViewModel(
    application: Application,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val engine = WebdavClientEngine()
    private val store = WebdavServerStore(application.applicationContext)
    private val db = ShelfDatabase.getInstance(application.applicationContext)
    private val importRepo = BookImportRepository(application.applicationContext, db, dispatchers)

    private val formState = MutableStateFlow(WebdavUiState())
    private val _state: StateFlow<WebdavUiState> =
        combine(formState, store.servers) { form, servers ->
            form.copy(savedServers = servers)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WebdavUiState())
    val state: StateFlow<WebdavUiState> = _state

    fun updateDisplayName(v: String) { formState.value = formState.value.copy(displayName = v) }
    fun updateBaseUrl(v: String) { formState.value = formState.value.copy(baseUrl = v, activeServerId = null) }
    fun updateUsername(v: String) { formState.value = formState.value.copy(username = v) }
    fun updatePassword(v: String) { formState.value = formState.value.copy(password = v) }
    fun updateBearerToken(v: String) { formState.value = formState.value.copy(bearerToken = v) }
    fun updateAuthType(v: String) { formState.value = formState.value.copy(authType = v) }
    fun updateTrustAllCerts(v: Boolean) { formState.value = formState.value.copy(trustAllCertificates = v) }
    fun updateBasePath(v: String) { formState.value = formState.value.copy(basePath = v) }

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
            baseUrl = saved.baseUrl,
            username = saved.username,
            password = saved.password,
            bearerToken = saved.bearerToken,
            authType = saved.authType,
            trustAllCertificates = saved.trustAllCertificates,
            currentPath = saved.defaultRemotePath,
            activeServerId = saved.id
        )
    }

    fun saveCurrentAs(name: String? = null) {
        if (formState.value.baseUrl.isBlank()) return
        val s = WebdavSavedServer(
            id = formState.value.activeServerId ?: 0L,
            displayName = name?.ifBlank { formState.value.baseUrl }
                ?: formState.value.displayName.ifBlank { formState.value.baseUrl },
            baseUrl = formState.value.baseUrl,
            username = formState.value.username,
            password = formState.value.password,
            bearerToken = formState.value.bearerToken,
            authType = formState.value.authType,
            trustAllCertificates = formState.value.trustAllCertificates,
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
        val base = formState.value.baseUrl.trimEnd('/')
        val userBasePath = formState.value.basePath.trimStart('/')
        val fullUrl = if (userBasePath.isNotBlank()) "$base/$userBasePath${formState.value.username}" else base

        val ok = engine.connect(
            baseUrl = fullUrl,
            username = formState.value.username,
            password = formState.value.password.ifBlank { null },
            bearerToken = formState.value.bearerToken.ifBlank { null },
            authType = formState.value.authType,
            trustAllCertificates = formState.value.trustAllCertificates,
            userAgent = "ShelfReader/1.0"
        )
        val entries = if (ok) engine.listDirectory("/") else emptyList()
        formState.value = formState.value.copy(
            isConnected = ok,
            isLoading = false,
            entries = entries,
            currentPath = "/",
            error = if (!ok) "Kan ikke koble til" else null
        )
    }

    fun disconnect() = viewModelScope.launch(dispatchers.io) {
        engine.disconnect()
        formState.value = formState.value.copy(
            isConnected = false, entries = emptyList(), selected = emptySet()
        )
    }

    fun navigateTo(entry: WebdavEntry) = viewModelScope.launch(dispatchers.io) {
        if (entry.type != WebdavEntryType.FOLDER) return@launch
        formState.value = formState.value.copy(isLoading = true, error = null)
        val entries = engine.listDirectory(entry.path)
        formState.value = formState.value.copy(
            currentPath = entry.path, entries = entries, isLoading = false, selected = emptySet()
        )
    }

    fun navigateUp() = viewModelScope.launch(dispatchers.io) {
        val current = formState.value.currentPath
        if (current == "/" || current.isBlank()) return@launch
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        formState.value = formState.value.copy(isLoading = true)
        val entries = engine.listDirectory(parent)
        formState.value = formState.value.copy(
            currentPath = parent, entries = entries, isLoading = false, selected = emptySet()
        )
    }

    fun downloadAndImportSelected() = viewModelScope.launch(dispatchers.io) {
        val selected = formState.value.selected.toList()
        if (selected.isEmpty()) return@launch
        val downloadsDir = getDownloadsDir()

        selected.forEach { remotePath ->
            val name = remotePath.substringAfterLast('/')
            if (!engine.matchesFormat(name)) return@forEach

            val localFile = File(downloadsDir, "dav_${System.currentTimeMillis()}_$name")
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
                        sizeBytes = bytes, downloadedBytes = bytes,
                        status = DownloadStatusEntity.COMPLETED, completedAt = System.currentTimeMillis()
                    ) ?: return@forEach
                )
                importRepo.importUris(
                    listOf(Uri.fromFile(localFile)),
                    source = ImportSourceEntity.WEBDAV_DOWNLOAD,
                    serverId = formState.value.activeServerId,
                    remotePath = remotePath,
                    filePathOverride = localFile.absolutePath
                )
            } else {
                db.downloadTaskDao().update(
                    db.downloadTaskDao().getById(taskId)?.copy(
                        status = DownloadStatusEntity.FAILED, errorMessage = "Nedlasting feilet"
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
        val files = formState.value.entries
            .filter { it.type == WebdavEntryType.FILE && engine.matchesFormat(it.name) }
            .map { it.path }
        if (files.isEmpty()) return@launch
        formState.value = formState.value.copy(selected = files.toSet())
        downloadAndImportSelected()
    }

    private fun getDownloadsDir(): File {
        val ctx = getApplication<Application>().applicationContext
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "shelf_webdav")
        dir.mkdirs()
        return dir
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(dispatchers.io) { runCatching { engine.disconnect() } }
    }
}
