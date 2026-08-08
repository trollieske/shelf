package com.shelf.reader.torrent.engine

import android.content.Context
import android.util.Log
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.data.local.entity.TorrentDownloadEntity
import com.shelf.reader.data.local.entity.TorrentPriorityEntity
import com.shelf.reader.data.local.entity.TorrentSourceTypeEntity
import com.shelf.reader.library.data.BookImportRepository
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import java.io.File
import java.security.MessageDigest

data class TorrentRuntimeStats(
    val downloadId: Long,
    val progressPercent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val peersConnected: Int = 0,
    val seedsConnected: Int = 0,
    val etaSeconds: Long? = null,
    val status: DownloadStatusEntity = DownloadStatusEntity.RUNNING,
    val trackerStatus: String = "Søker...",
    val errorMessage: String? = null,
    val completedFiles: List<String> = emptyList()
)

class TorrentEngine(
    private val context: Context,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) {

    companion object {
        private const val TAG = "TorrentEngine"

        @Volatile
        private var INSTANCE: TorrentEngine? = null

        fun getInstance(
            context: Context,
            db: ShelfDatabase = ShelfDatabase.getInstance(context),
            dispatchers: DispatcherProvider = DefaultDispatcherProvider
        ): TorrentEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorrentEngine(context.applicationContext, db, dispatchers).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var running = false

    private val _activeStats = MutableStateFlow<Map<Long, TorrentRuntimeStats>>(emptyMap())
    val activeStats: StateFlow<Map<Long, TorrentRuntimeStats>> = _activeStats.asStateFlow()

    private val _totalDownloadSpeed = MutableStateFlow(0L)
    val totalDownloadSpeed: StateFlow<Long> = _totalDownloadSpeed.asStateFlow()

    private val _totalUploadSpeed = MutableStateFlow(0L)
    val totalUploadSpeed: StateFlow<Long> = _totalUploadSpeed.asStateFlow()

    private val importRepo by lazy { BookImportRepository(context, db, dispatchers) }

    // One shared SessionManager for all torrents
    @Volatile
    private var sessionManager: SessionManager? = null

    // Map from torrent infoHash string -> DB download ID
    private val hashToId = mutableMapOf<String, Long>()

    // Thread-safe map of torrent infoHash -> real-time tracker status message
    private val trackerStatusMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Hold strong reference to TorrentInfo objects to prevent GC from freeing C++ pointers
    private val torrentInfoMap = java.util.concurrent.ConcurrentHashMap<String, TorrentInfo>()

    private fun maskPasskey(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url.replace(Regex("passkey=[a-zA-Z0-9]+"), "passkey=***")
    }

    fun start() {
        if (running) return
        running = true
        scope.launch { initSession() }
        scope.launch { mainLoop() }
        scope.launch { statsTickerLoop() }
    }

    fun stop() {
        running = false
        try {
            sessionManager?.stop()
        } catch (_: Exception) {}
        runCatching { scope.coroutineContext.cancelChildren() }
    }

    private suspend fun initSession() = withContext(dispatchers.io) {
        try {
            val sm = SessionManager()
            sm.addListener(object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    when (alert) {
                        is TrackerAnnounceAlert -> {
                            val url = maskPasskey(alert.trackerUrl())
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            Log.i(TAG, "ALERT [TRACKER_ANNOUNCE]: hash=$hash msg=${alert.message()} url=$url")
                            if (hash != null) trackerStatusMap[hash] = "Announcerer..."
                        }
                        is TrackerReplyAlert -> {
                            val url = maskPasskey(alert.trackerUrl())
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val numPeers = alert.numPeers()
                            Log.i(TAG, "ALERT [TRACKER_REPLY]: hash=$hash peersCount=$numPeers url=$url")
                            if (hash != null) trackerStatusMap[hash] = "Tracker OK ($numPeers peers)"
                        }
                        is TrackerErrorAlert -> {
                            val url = maskPasskey(alert.trackerUrl())
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val err = alert.errorMessage() ?: alert.message() ?: "Ukjent feil"
                            Log.e(TAG, "ALERT [TRACKER_ERROR]: hash=$hash error='$err' url=$url")
                            if (hash != null) trackerStatusMap[hash] = "Tracker feil: $err ($url)"
                        }
                        is TrackerWarningAlert -> {
                            val url = maskPasskey(alert.trackerUrl())
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val warn = alert.message()
                            Log.w(TAG, "ALERT [TRACKER_WARNING]: hash=$hash warning='$warn' url=$url")
                            if (hash != null) trackerStatusMap[hash] = "Tracker advarsel: $warn"
                        }
                        is AddTorrentAlert -> {
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val handle = alert.handle()
                            val isPriv = runCatching { handle?.torrentFile()?.isPrivate() }.getOrNull() ?: false
                            Log.i(TAG, "ALERT [ADD_TORRENT]: hash=$hash isPrivate=$isPriv msg=${alert.message()}")
                            if (handle != null && handle.isValid) {
                                try { handle.resume() } catch (_: Throwable) {}
                                try { handle.forceReannounce() } catch (_: Throwable) {}
                            }
                        }
                        is StateChangedAlert -> {
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            Log.i(TAG, "ALERT [STATE_CHANGED]: hash=$hash msg=${alert.message()}")
                        }
                        is ListenSucceededAlert -> {
                            Log.i(TAG, "ALERT [LISTEN_SUCCEEDED]: msg=${alert.message()}")
                        }
                        is ListenFailedAlert -> {
                            Log.e(TAG, "ALERT [LISTEN_FAILED]: msg=${alert.message()}")
                        }
                    }
                }
            })
            sm.start()
            try { sm.startDht() } catch (_: Throwable) {}

            val sp = SettingsPack()
            // 1. Enable standard status, error, and tracker alert mask (0x1f = status|error|tracker|storage)
            sp.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.alert_mask.swigValue(),
                0x1f
            )

            // 2. Disable anonymous mode & HTTPS cert validation (required by private HTTPS trackers on Android)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.anonymous_mode.swigValue(), false)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.validate_https_trackers.swigValue(), false)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.prefer_udp_trackers.swigValue(), false)

            // 3. User-Agent & Peer Fingerprint (qBittorrent 4.6.3)
            sp.setString(org.libtorrent4j.swig.settings_pack.string_types.user_agent.swigValue(), "qBittorrent/4.6.3")
            sp.setString(org.libtorrent4j.swig.settings_pack.string_types.peer_fingerprint.swigValue(), "-qB4630-")

            // 4. High random listening port (62473) to avoid ISP/router default port blocks
            sp.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:62473,[::]:62473")

            // 5. Session-wide DHT, LSD, UPnP, NAT-PMP settings
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)

            // 6. Max active connections
            sp.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_downloads.swigValue(), 20)
            sp.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_seeds.swigValue(), 20)
            sp.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_limit.swigValue(), 40)

            sm.applySettings(sp)
            sessionManager = sm

            Log.i(TAG, "=== SETTINGS PACK CONFIRMATION DUMP ===")
            Log.i(TAG, "  User-Agent: qBittorrent/4.6.3")
            Log.i(TAG, "  Peer Fingerprint: -qB4630-")
            Log.i(TAG, "  Anonymous Mode: FALSE")
            Log.i(TAG, "  Listen Interfaces: 0.0.0.0:62473,[::]:62473")
            Log.i(TAG, "  DHT Enabled: TRUE, LSD Enabled: TRUE, UPnP Enabled: TRUE")
            Log.i(TAG, "========================================")

            // Re-add any pending/running downloads from DB
            val pending = db.torrentDownloadDao().getAllOnce()
                .filter { (it.status == DownloadStatusEntity.PENDING || it.status == DownloadStatusEntity.RUNNING) && !it.isPaused }
            for (dl in pending) {
                val runningDl = dl.copy(status = DownloadStatusEntity.RUNNING)
                db.torrentDownloadDao().update(runningDl)
                addToSession(sm, runningDl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init libtorrent session", e)
        }
    }

    private fun extractAnnounceUrlsFromBencode(bytes: ByteArray): List<String> {
        val urls = mutableListOf<String>()
        try {
            val str = String(bytes, Charsets.ISO_8859_1)
            var pos = 0
            while (pos < str.length) {
                val announceIdx = str.indexOf("announce", pos)
                if (announceIdx == -1) break
                val colonIdx = str.indexOf(':', announceIdx + 8)
                if (colonIdx != -1 && colonIdx - (announceIdx + 8) in 1..6) {
                    val lenStr = str.substring(announceIdx + 8, colonIdx)
                    val len = lenStr.toIntOrNull()
                    if (len != null && colonIdx + 1 + len <= str.length) {
                        val url = str.substring(colonIdx + 1, colonIdx + 1 + len)
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("udp://")) {
                            if (!urls.contains(url)) urls.add(url)
                        }
                    }
                }
                pos = announceIdx + 8
            }
        } catch (_: Exception) {}
        return urls
    }

    private suspend fun addToSession(sm: SessionManager, dl: TorrentDownloadEntity) = withContext(dispatchers.io) {
        try {
            val saveDir = if (dl.savePath.contains("emulated") || dl.savePath.contains("/storage/")) {
                defaultSaveDir()
            } else {
                File(dl.savePath)
            }
            if (!saveDir.exists()) saveDir.mkdirs()

            when (dl.sourceType) {
                TorrentSourceTypeEntity.MAGNET -> {
                    val magnetWithTrackers = appendFallbackTrackersIfNeeded(dl.sourceData)
                    sm.download(magnetWithTrackers, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    val hash = dl.infoHash ?: extractInfoHashFromMagnet(dl.sourceData)
                    if (hash != null) hashToId[hash.uppercase()] = dl.id
                    Log.i(TAG, "Added magnet to session: ${dl.displayName} (hash=$hash)")
                }
                TorrentSourceTypeEntity.TORRENT_FILE -> {
                    val torrentBytes = dl.sourceData.fromBase64()
                    val ti = TorrentInfo(torrentBytes)
                    val hash = ti.infoHash().toHex().uppercase()
                    torrentInfoMap[hash] = ti

                    Log.i(TAG, "Adding .torrent file: name='${ti.name()}', hash=$hash, size=${ti.totalSize()}")

                    sm.download(ti, saveDir)
                    hashToId[hash] = dl.id
                    db.torrentDownloadDao().update(dl.copy(infoHash = hash, status = DownloadStatusEntity.RUNNING, isPaused = false))

                    val handle = try { sm.find(Sha1Hash.parseHex(hash)) } catch (_: Exception) { null }
                    if (handle != null && handle.isValid) {
                        try { handle.forceReannounce() } catch (_: Throwable) {}
                    }
                }
                TorrentSourceTypeEntity.INFO_HASH -> {
                    val magnet = buildMagnetFromHash(dl.sourceData, dl.displayName, dl.trackersJson)
                    sm.download(magnet, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    hashToId[dl.sourceData.uppercase()] = dl.id
                }
                else -> {
                    // HTTP_URL or unknown â€” treat as magnet/url string
                    sm.download(dl.sourceData, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    val hash = dl.infoHash
                    if (hash != null) hashToId[hash.uppercase()] = dl.id
                    Log.w(TAG, "Unknown source type ${dl.sourceType} for dl=${dl.id}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "addToSession failed for dl=${dl.id}", e)
        }
    }

    suspend fun addFromMagnet(
        magnetUri: String,
        saveDir: File = defaultSaveDir(),
        autoImport: Boolean = true,
        priority: TorrentPriorityEntity = TorrentPriorityEntity.NORMAL
    ): Long = withContext(dispatchers.io) {
        val clean = appendFallbackTrackersIfNeeded(magnetUri)
        val infoHash = extractInfoHashFromMagnet(clean)
        val existing = infoHash?.let { db.torrentDownloadDao().getByInfoHash(it) }
        if (existing != null) {
            val updated = existing.copy(
                sourceData = clean,
                status = DownloadStatusEntity.RUNNING,
                isPaused = false,
                errorMessage = null
            )
            db.torrentDownloadDao().update(updated)
            sessionManager?.let { addToSession(it, updated) }
            return@withContext existing.id
        }

        val displayName = extractNameFromMagnet(clean) ?: "Magnet: ${infoHash?.take(8) ?: "ukjent"}"

        val entity = TorrentDownloadEntity(
            sourceType = TorrentSourceTypeEntity.MAGNET,
            sourceData = clean,
            infoHash = infoHash,
            displayName = displayName,
            savePath = saveDir.absolutePath,
            status = DownloadStatusEntity.RUNNING,
            priority = priority,
            autoImport = autoImport,
            isSequential = true,
            isFirstLastPiecePriority = true,
            wifiOnly = false,
            batteryMinPercent = 20
        )
        val id = db.torrentDownloadDao().insert(entity)
        // Immediately add to session if running
        sessionManager?.let { addToSession(it, entity.copy(id = id)) }
        id
    }

    suspend fun addFromTorrentFile(
        torrentFile: File,
        saveDir: File = defaultSaveDir(),
        autoImport: Boolean = true,
        priority: TorrentPriorityEntity = TorrentPriorityEntity.NORMAL
    ): Long = withContext(dispatchers.io) {
        val bytes = torrentFile.readBytes()
        val ti = runCatching { TorrentInfo(bytes) }.getOrNull()
        val infoHash = ti?.infoHash()?.toHex()?.uppercase()
        val displayName = ti?.name()?.takeIf { it.isNotBlank() }
            ?: parseTorrentName(bytes)
            ?: torrentFile.nameWithoutExtension

        val existing = infoHash?.let { db.torrentDownloadDao().getByInfoHash(it) }
        if (existing != null) {
            val updated = existing.copy(
                sourceData = bytes.toBase64(),
                displayName = displayName,
                status = DownloadStatusEntity.RUNNING,
                isPaused = false,
                errorMessage = null
            )
            db.torrentDownloadDao().update(updated)
            sessionManager?.let { addToSession(it, updated) }
            return@withContext existing.id
        }

        val entity = TorrentDownloadEntity(
            sourceType = TorrentSourceTypeEntity.TORRENT_FILE,
            sourceData = bytes.toBase64(),
            infoHash = infoHash,
            displayName = displayName,
            savePath = saveDir.absolutePath,
            status = DownloadStatusEntity.RUNNING,
            priority = priority,
            autoImport = autoImport,
            isSequential = true,
            isFirstLastPiecePriority = true,
            wifiOnly = false,
            batteryMinPercent = 20
        )
        val id = db.torrentDownloadDao().insert(entity)
        sessionManager?.let { addToSession(it, entity.copy(id = id)) }
        id
    }

    suspend fun addFromInfoHash(
        infoHash: String,
        trackers: List<String> = emptyList(),
        displayName: String? = null,
        saveDir: File = defaultSaveDir(),
        autoImport: Boolean = true
    ): Long = withContext(dispatchers.io) {
        val existing = db.torrentDownloadDao().getByInfoHash(infoHash)
        if (existing != null) return@withContext existing.id

        val entity = TorrentDownloadEntity(
            sourceType = TorrentSourceTypeEntity.INFO_HASH,
            sourceData = infoHash,
            infoHash = infoHash,
            displayName = displayName ?: "Torrent: ${infoHash.take(8)}",
            savePath = saveDir.absolutePath,
            status = DownloadStatusEntity.PENDING,
            autoImport = autoImport,
            isSequential = true,
            trackersJson = if (trackers.isNotEmpty()) trackers.joinToString(",") else null,
            wifiOnly = true,
            batteryMinPercent = 20
        )
        val id = db.torrentDownloadDao().insert(entity)
        sessionManager?.let { addToSession(it, entity.copy(id = id)) }
        id
    }

    suspend fun startDownload(id: Long) = withContext(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        if (dl.status == DownloadStatusEntity.RUNNING) return@withContext
        db.torrentDownloadDao().update(
            dl.copy(status = DownloadStatusEntity.RUNNING, isPaused = false, errorMessage = null)
        )
        // Re-add to session
        sessionManager?.let { sm ->
            // Try to find existing handle first
            val hash = dl.infoHash
            if (hash != null) {
                val handle = sm.find(Sha1Hash.parseHex(hash))
                if (handle != null && handle.isValid) {
                    handle.resume()
                    return@withContext
                }
            }
            addToSession(sm, dl.copy(status = DownloadStatusEntity.RUNNING))
        }
    }

    suspend fun pauseDownload(id: Long) = withContext(dispatchers.io) {
        db.torrentDownloadDao().setPaused(id, true)
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        if (dl.status == DownloadStatusEntity.RUNNING) {
            db.torrentDownloadDao().update(dl.copy(status = DownloadStatusEntity.PAUSED, isPaused = true))
        }
        // Pause in session
        val hash = dl.infoHash ?: return@withContext
        try {
            sessionManager?.find(Sha1Hash.parseHex(hash))?.pause()
        } catch (_: Exception) {}
    }

    suspend fun resumeDownload(id: Long) = withContext(dispatchers.io) {
        db.torrentDownloadDao().setPaused(id, false)
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        if (dl.status == DownloadStatusEntity.PAUSED || dl.status == DownloadStatusEntity.PENDING) {
            db.torrentDownloadDao().update(dl.copy(status = DownloadStatusEntity.RUNNING, isPaused = false))
        }
        val hash = dl.infoHash ?: return@withContext
        try {
            sessionManager?.find(Sha1Hash.parseHex(hash))?.resume()
        } catch (_: Exception) {}
    }

    suspend fun cancelDownload(id: Long) = withContext(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        db.torrentDownloadDao().cancel(id)
        val hash = dl.infoHash ?: return@withContext
        try {
            val handle = sessionManager?.find(Sha1Hash.parseHex(hash))
            if (handle != null && handle.isValid) {
                sessionManager?.remove(handle)
                hashToId.remove(hash.uppercase())
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteDownload(id: Long, withFiles: Boolean = false) = withContext(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        cancelDownload(id)
        if (withFiles) {
            runCatching { File(dl.savePath).deleteRecursively() }
        }
        db.torrentDownloadDao().delete(dl)
    }

    suspend fun pauseAll() = withContext(dispatchers.io) { db.torrentDownloadDao().pauseAll() }
    suspend fun resumeAll() = withContext(dispatchers.io) { db.torrentDownloadDao().resumeAll() }

    // -------- internals --------

    private suspend fun mainLoop() {
        while (running) {
            try {
                tickMain()
            } catch (_: Exception) {}
            delay(2000L)
        }
    }

    private suspend fun statsTickerLoop() {
        while (running) {
            try {
                pollTorrentStats()
            } catch (_: Exception) {}
            delay(1000L)
        }
    }

    private suspend fun tickMain() {
        val sm = sessionManager ?: return
        val activeDls = db.torrentDownloadDao().getAllOnce()
            .filter { !it.isPaused && (it.status == DownloadStatusEntity.RUNNING || it.status == DownloadStatusEntity.PENDING) }

        for (dl in activeDls) {
            val hash = dl.infoHash
            val alreadyInSession = if (hash != null) {
                try { sm.find(Sha1Hash.parseHex(hash))?.isValid == true } catch (_: Exception) { false }
            } else false
            if (!alreadyInSession) {
                db.torrentDownloadDao().update(dl.copy(status = DownloadStatusEntity.RUNNING, isPaused = false))
                addToSession(sm, dl)
            }
        }
    }

    private suspend fun pollTorrentStats() {
        val sm = sessionManager ?: return
        val allDls = db.torrentDownloadDao().getAllOnce()
            .filter { it.status == DownloadStatusEntity.RUNNING || it.status == DownloadStatusEntity.PENDING }

        val newStats = mutableMapOf<Long, TorrentRuntimeStats>()

        for (dl in allDls) {
            val hash = dl.infoHash ?: continue
            val handle = try { sm.find(Sha1Hash.parseHex(hash)) } catch (_: Exception) { null }
            if (handle == null || !handle.isValid) continue

            if (!dl.isPaused) {
                try { handle.resume() } catch (_: Throwable) {}
            }
            val status = handle.status()
            val progress = status.progress()
            val dlSpeed = status.downloadPayloadRate().toLong()
            val ulSpeed = status.uploadPayloadRate().toLong()
            val seeds = status.numSeeds()
            val peers = status.numPeers()
            val totalBytes = handle.torrentFile()?.totalSize() ?: dl.totalSizeBytes.coerceAtLeast(1L)
            val downloaded = (progress * totalBytes).toLong()
            val eta = if (dlSpeed > 0) (totalBytes - downloaded) / dlSpeed else null

            val trackers = runCatching { handle.trackers() }.getOrNull() ?: emptyList()
            val trackerDiagnostic = trackers.joinToString(" | ") { tr ->
                val url = maskPasskey(tr.url())
                "[$url tier=${tr.tier()} verified=${tr.isVerified()}]"
            }
            val isPriv = runCatching { handle.torrentFile()?.isPrivate() }.getOrNull() ?: false
            Log.i(TAG, "DIAGNOSTIC [$hash] '${dl.displayName}': state=${status.state()} progress=$progress peers=$peers seeds=$seeds conn=${status.numConnections()} isPrivate=$isPriv trackers=$trackerDiagnostic")

            val stat = TorrentRuntimeStats(
                downloadId = dl.id,
                progressPercent = progress,
                downloadedBytes = downloaded,
                totalBytes = totalBytes,
                downloadSpeedBps = dlSpeed,
                uploadSpeedBps = ulSpeed,
                seedsConnected = seeds,
                peersConnected = peers,
                etaSeconds = eta,
                status = DownloadStatusEntity.RUNNING,
                trackerStatus = trackerStatusMap[hash] ?: "Søker..."
            )
            newStats[dl.id] = stat

            // Update DB
            db.torrentDownloadDao().update(
                dl.copy(
                    status = DownloadStatusEntity.RUNNING,
                    progressPercent = progress,
                    downloadedBytes = downloaded,
                    totalSizeBytes = totalBytes,
                    downloadSpeedBps = dlSpeed,
                    uploadSpeedBps = ulSpeed,
                    seedsConnected = seeds,
                    peersConnected = peers,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )

            // Check completion
            if (progress >= 1f) {
                onTorrentCompleted(dl, handle)
            }
        }

        _activeStats.value = newStats
        _totalDownloadSpeed.value = newStats.values.sumOf { it.downloadSpeedBps }
        _totalUploadSpeed.value = newStats.values.sumOf { it.uploadSpeedBps }
    }

    private suspend fun onTorrentCompleted(dl: TorrentDownloadEntity, handle: TorrentHandle) {
        Log.i(TAG, "Torrent completed: ${dl.displayName}")
        val final = dl.copy(
            status = DownloadStatusEntity.COMPLETED,
            progressPercent = 1f,
            completedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            downloadSpeedBps = 0L
        )
        db.torrentDownloadDao().update(final)

        // Keep seeding â€” do NOT remove the handle
        if (dl.autoImport) {
            importCompleted(final)
        }
    }

    private suspend fun importCompleted(entity: TorrentDownloadEntity) {
        val dir = File(entity.savePath)
        if (!dir.exists()) return

        Log.i(TAG, "Importing from: ${dir.absolutePath}")
        val imported = importRepo.importDirectoryOrArchive(
            target = dir,
            source = ImportSourceEntity.TORRENT_DOWNLOAD
        )
        Log.i(TAG, "Imported ${imported.size} books from torrent '${entity.displayName}'")

        db.torrentDownloadDao().update(
            entity.copy(
                importedBookIdsJson = imported.joinToString(","),
                importStatus = "IMPORTED:${imported.size}"
            )
        )
    }

    fun defaultSaveDir(): File {
        return File(context.filesDir, "shelf_torrents").apply { mkdirs() }
    }

    private fun appendFallbackTrackersIfNeeded(magnet: String): String {
        if (magnet.contains("&tr=")) return magnet
        val fallbackTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce"
        )
        val sb = StringBuilder(magnet)
        for (tr in fallbackTrackers) {
            sb.append("&tr=").append(java.net.URLEncoder.encode(tr, "UTF-8"))
        }
        return sb.toString()
    }

    private fun extractInfoHashFromMagnet(magnet: String): String? {
        val xt = "xt=urn:btih:"
        val idx = magnet.indexOf(xt)
        if (idx < 0) return null
        val after = magnet.substring(idx + xt.length)
        val end = after.indexOfFirst { it == '&' || it == ' ' }.let { if (it < 0) after.length else it }
        val hash = after.substring(0, end)
        return if (hash.length == 40 || hash.length == 32) hash.uppercase() else null
    }

    private fun extractNameFromMagnet(magnet: String): String? {
        val dn = "dn="
        val idx = magnet.indexOf(dn)
        if (idx < 0) return null
        val after = magnet.substring(idx + dn.length)
        val end = after.indexOfFirst { it == '&' || it == ' ' }.let { if (it < 0) after.length else it }
        return runCatching { java.net.URLDecoder.decode(after.substring(0, end), "UTF-8") }.getOrNull()
    }

    private fun buildMagnetFromHash(hash: String, name: String?, trackersJson: String?): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:$hash")
        if (!name.isNullOrBlank()) sb.append("&dn=").append(java.net.URLEncoder.encode(name, "UTF-8"))
        trackersJson?.split(",")?.forEach { tracker ->
            sb.append("&tr=").append(java.net.URLEncoder.encode(tracker.trim(), "UTF-8"))
        }
        return sb.toString()
    }

    private fun computeTorrentInfoHash(bytes: ByteArray): String? {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(bytes)
            md.digest().joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }

    private fun parseTorrentName(bytes: ByteArray): String? {
        val s = bytes.decodeToString(throwOnInvalidSequence = false)
        val nameIdx = s.indexOf("4:name")
        if (nameIdx < 0) return null
        val after = s.substring(nameIdx + 6)
        val lenMatch = "^(\\d+):".toRegex().find(after) ?: return null
        val len = lenMatch.groupValues[1].toIntOrNull() ?: return null
        val start = lenMatch.range.last + 1
        return after.substring(start, (start + len).coerceAtMost(after.length))
    }

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
}



