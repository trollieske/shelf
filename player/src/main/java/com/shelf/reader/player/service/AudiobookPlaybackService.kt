package com.shelf.reader.player.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.FormatEntity
import com.shelf.reader.data.local.entity.ReadingProgressEntity
import com.shelf.reader.player.engine.AudiobookChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class AudiobookPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "AudiobookPlaybackService"
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 8888
        const val ACTION_LOAD_BOOK = "com.shelf.reader.player.LOAD_BOOK"
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L

        const val CMD_SPEED = "CMD_SET_SPEED"
        const val CMD_SKIP_BACK = "CMD_SKIP_BACK"
        const val CMD_SKIP_FORWARD = "CMD_SKIP_FORWARD"
        const val CMD_SET_SLEEP = "CMD_SET_SLEEP"

        const val ACTION_SKIP_BACK = "com.shelf.reader.player.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "com.shelf.reader.player.SKIP_FORWARD"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var librarySession: MediaLibraryService.MediaLibrarySession? = null
    private var currentBookId: Long = -1L
    private var db: ShelfDatabase? = null
    private val binder = LocalBinder()
    private var sleepTimer: android.os.CountDownTimer? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudiobookPlaybackService = this@AudiobookPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: action=${intent?.action}")
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        db = ShelfDatabase.getInstance(applicationContext)
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK or C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                if (id.startsWith("book_")) {
                    val bookId = runCatching { id.removePrefix("book_").toLong() }.getOrNull() ?: return
                    if (currentBookId != bookId) {
                        val startPlaying = exo.playWhenReady
                        loadBook(bookId)
                        if (startPlaying) serviceScope.launch(Dispatchers.Main) { player?.playWhenReady = true }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: $playbackState")
                maybePersistProgress()
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        serviceScope.launch { saveProgress(1.0f) }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $isPlaying")
                maybePersistProgress()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "onPlayerError: ${error.message}", error)
            }
        })
        player = exo

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_SPEED, Bundle()))
                    .add(SessionCommand(CMD_SKIP_BACK, Bundle()))
                    .add(SessionCommand(CMD_SKIP_FORWARD, Bundle()))
                    .add(SessionCommand(CMD_SET_SLEEP, Bundle()))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val p = player ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return when (customCommand.customAction) {
                    CMD_SPEED -> {
                        val speed = args.getFloat("speed", 1.0f).coerceIn(0.5f, 2f)
                        p.setPlaybackSpeed(speed)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SKIP_BACK -> {
                        p.seekTo((p.currentPosition - SEEK_BACK_MS).coerceAtLeast(0L))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SKIP_FORWARD -> {
                        p.seekTo((p.currentPosition + SEEK_FORWARD_MS).coerceAtMost(p.duration.coerceAtLeast(0L)))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SET_SLEEP -> {
                        val minutes = args.getInt("minutes", -1)
                        if (minutes >= 0) {
                            startSleepTimer(minutes)
                        } else {
                            cancelSleepTimer()
                        }
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
            }
        }

        val libraryCallback = object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId("__ROOT__")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Shelf Bibliotek")
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val db = db ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                return Futures.immediateFuture(
                    runCatching {
                        val audioFormats = setOf(
                            FormatEntity.M4B, FormatEntity.M4A, FormatEntity.MP3,
                            FormatEntity.AAC, FormatEntity.FLAC, FormatEntity.OGG,
                            FormatEntity.OPUS, FormatEntity.OGG_OPUS, FormatEntity.WAV
                        )
                        val allBooks = runBlocking(Dispatchers.IO) {
                            db.bookDao().observeAll().first().filter { it.format in audioFormats }
                        }
                        val sorted = allBooks.sortedWith(
                            compareByDescending<com.shelf.reader.data.local.entity.BookEntity> { it.lastOpenedAt ?: 0L }
                                .thenBy { it.title }
                        )
                        val items = sorted.map { book ->
                            MediaItem.Builder()
                                .setMediaId("book_${book.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(book.title)
                                        .setDisplayTitle(book.title)
                                        .setArtist(book.author)
                                        .setAlbumTitle(book.title)
                                        .setSubtitle(book.author)
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .build()
                                )
                                .build()
                        }
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    }.getOrElse { t ->
                        Log.e(TAG, "onGetChildren failed", t)
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                    }
                )
            }
        }

        val sessionIntent = Intent().apply {
            setClassName(this@AudiobookPlaybackService, "com.shelf.reader.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val layoutButtons = listOf(
            CommandButton.Builder()
                .setDisplayName("Tilbake 10s").setIconResId(android.R.drawable.ic_media_rew)
                .setSessionCommand(SessionCommand(CMD_SKIP_BACK, Bundle()))
                .build(),
            CommandButton.Builder().setDisplayName("Forrige").setIconResId(android.R.drawable.ic_media_previous)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build(),
            CommandButton.Builder().setDisplayName("Spill").setIconResId(android.R.drawable.ic_media_play)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE).build(),
            CommandButton.Builder().setDisplayName("Neste").setIconResId(android.R.drawable.ic_media_next)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build(),
            CommandButton.Builder()
                .setDisplayName("Frem 30s").setIconResId(android.R.drawable.ic_media_ff)
                .setSessionCommand(SessionCommand(CMD_SKIP_FORWARD, Bundle()))
                .build()
        )

        // ─── ENESTE SESSION! MediaLibrarySession ER en MediaSession (alt fungerer: notif, BT, Auto) ───
        librarySession = MediaLibraryService.MediaLibrarySession.Builder(this, exo, libraryCallback)
            .setSessionActivity(pi)
            .setId("shelf_audio")
            .build()
        session = librarySession

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                session: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val appIcon = android.R.drawable.ic_media_play

                val skipBackIntent = Intent(this@AudiobookPlaybackService, AudiobookPlaybackService::class.java)
                    .setAction(ACTION_SKIP_BACK)
                val skipBackPi = PendingIntent.getService(
                    this@AudiobookPlaybackService,
                    1001,
                    skipBackIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val skipForwardIntent = Intent(this@AudiobookPlaybackService, AudiobookPlaybackService::class.java)
                    .setAction(ACTION_SKIP_FORWARD)
                val skipForwardPi = PendingIntent.getService(
                    this@AudiobookPlaybackService,
                    1002,
                    skipForwardIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val notification = androidx.core.app.NotificationCompat.Builder(this@AudiobookPlaybackService, CHANNEL_ID)
                    .setContentTitle(session.player.mediaMetadata.title ?: "Lydbok")
                    .setContentText(session.player.mediaMetadata.artist ?: "")
                    .setSmallIcon(appIcon)
                    .setSubText(session.player.mediaMetadata.albumTitle)
                    .setOngoing(session.player.isPlaying)
                    .setContentIntent(session.sessionActivity)
                    .addAction(android.R.drawable.ic_media_previous, "Forrige",
                        actionFactory.createMediaActionPendingIntent(session, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM.toLong()))
                    .addAction(android.R.drawable.ic_media_rew, "Tilbake 10s", skipBackPi)
                    .addAction(android.R.drawable.ic_media_play, "Spill",
                        actionFactory.createMediaActionPendingIntent(session, Player.COMMAND_PLAY_PAUSE.toLong()))
                    .addAction(android.R.drawable.ic_media_ff, "Frem 30s", skipForwardPi)
                    .addAction(android.R.drawable.ic_media_next, "Neste",
                        actionFactory.createMediaActionPendingIntent(session, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM.toLong()))
                    .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(1, 2, 3))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                    .build()
                return MediaNotification(NOTIFICATION_ID, notification)
            }

            override fun handleCustomCommand(session: MediaSession, action: String, args: Bundle): Boolean = false
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0

        val initialNotif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Laster lydbok…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initialNotif, type)
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }

        when (intent?.action) {
            ACTION_LOAD_BOOK -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                if (bookId > 0) loadBook(bookId)
            }
            ACTION_SKIP_BACK -> {
                val p = player
                if (p != null) {
                    p.seekTo((p.currentPosition - SEEK_BACK_MS).coerceAtLeast(0L))
                }
            }
            ACTION_SKIP_FORWARD -> {
                val p = player
                if (p != null) {
                    p.seekTo((p.currentPosition + SEEK_FORWARD_MS).coerceAtMost(p.duration.coerceAtLeast(0L)))
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private var activeChapters: List<AudiobookChapter> = emptyList()

    private fun loadBook(bookId: Long) {
        currentBookId = bookId
        val p = player ?: return
        serviceScope.launch {
            val db = db ?: return@launch
            val book = db.bookDao().getById(bookId) ?: return@launch
            val prog = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f
            val source = run {
                val fp = book.filePath
                val fu = book.fileUri
                when {
                    fp != null && fp.isNotBlank() && java.io.File(fp).canRead() ->
                        Uri.fromFile(java.io.File(fp)).toString()
                    fu != null && fu.isNotBlank() -> fu
                    else -> null
                }
            }

            activeChapters = parseChapters(book.chaptersJson ?: "")

            if (activeChapters.isNotEmpty()) {
                val mediaItems = activeChapters.map { ch ->
                    val uriStr = ch.mediaUri ?: source ?: ""
                    MediaItem.Builder()
                        .setUri(Uri.parse(uriStr))
                        .setMediaId("${book.id}_${ch.index}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(ch.title)
                                .setArtist(book.author)
                                .setAlbumArtist(book.author)
                                .setAlbumTitle(book.title)
                                .setDisplayTitle(ch.title)
                                .setSubtitle("Kapittel ${ch.index + 1} av ${activeChapters.size}")
                                .build()
                        )
                        .build()
                }

                withContext(Dispatchers.Main) {
                    p.setMediaItems(mediaItems)
                    val totalDur = book.durationMs ?: activeChapters.lastOrNull()?.endMs ?: 0L
                    if (prog > 0f && totalDur > 0L) {
                        val targetMs = (prog * totalDur).toLong()
                        val targetIdx = activeChapters.indexOfLast { it.startMs <= targetMs }.coerceAtLeast(0)
                        val offsetMs = targetMs - activeChapters[targetIdx].startMs
                        p.seekTo(targetIdx, offsetMs.coerceAtLeast(0L))
                    }
                    p.prepare()
                }
            } else if (source != null) {
                val dur = book.durationMs ?: C.TIME_UNSET
                val item = MediaItem.Builder()
                    .setUri(Uri.parse(source))
                    .setMediaId(book.id.toString())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(book.title)
                            .setArtist(book.author)
                            .setAlbumArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setDisplayTitle(book.title)
                            .setSubtitle(book.format.name + " – Lydbok")
                            .build()
                    )
                    .build()
                
                withContext(Dispatchers.Main) {
                    p.setMediaItem(item, (prog * (if (dur == C.TIME_UNSET) 0L else dur).toDouble()).toLong().coerceAtLeast(0L))
                    p.prepare()
                }
            }
        }
    }

    private fun maybePersistProgress() {
        if (currentBookId < 0) return
        val p = player ?: return
        
        // Ensure currentPosition is read on Main thread
        if (p.applicationLooper.thread != Thread.currentThread()) {
            serviceScope.launch(Dispatchers.Main) { maybePersistProgress() }
            return
        }

        val duration = p.duration
        if (duration <= 0L) return
        val pos = p.currentPosition
        val pct = pos.toFloat() / duration
        serviceScope.launch { saveProgress(pct.coerceIn(0f, 1f)) }
    }

    private suspend fun saveProgress(pct: Float) {
        if (currentBookId < 0) return
        db?.progressDao()?.insertOrReplace(
            ReadingProgressEntity(bookId = currentBookId, progressPercent = pct)
        )
    }

    private fun ensureChannel() {
        val mgr = NotificationManagerCompat.from(this)
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Avspilling")
                .setDescription("Lydbok-avspilling")
                .setShowBadge(false)
                .build()
            mgr.createNotificationChannel(channel)
        }
    }

    private var sleepTimerEndTimeMs: Long = 0L

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        val totalMs = minutes * 60L * 1000L
        sleepTimerEndTimeMs = System.currentTimeMillis() + totalMs

        sleepTimer = object : android.os.CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                // Fade out volume during last 30 seconds
                val p = player ?: return
                if (millisUntilFinished < 30_000L && millisUntilFinished > 0L) {
                    val fadeVol = (millisUntilFinished.toFloat() / 30_000f).coerceIn(0.05f, 1.0f)
                    p.volume = fadeVol
                } else if (p.volume < 1.0f && millisUntilFinished >= 30_000L) {
                    p.volume = 1.0f
                }
            }

            override fun onFinish() {
                val p = player
                if (p != null) {
                    p.volume = 1.0f
                    if (p.isPlaying) p.playWhenReady = false
                }
                
                maybePersistProgress()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                sleepTimer = null
                sleepTimerEndTimeMs = 0L
                Log.i(TAG, "Sleep timer expired, playback stopped.")
            }
        }.start()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerEndTimeMs = 0L
        player?.volume = 1.0f
    }

    fun sleepTimerRemainingMs(): Long {
        if (sleepTimer == null || sleepTimerEndTimeMs <= 0L) return 0L
        return (sleepTimerEndTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val p = player ?: return
        if (!p.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        cancelSleepTimer()
        serviceScope.launch(Dispatchers.Main) {
            maybePersistProgress()
            session?.run {
                release()
            }
            player?.run {
                stop()
                release()
            }
            session = null
            player = null
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = librarySession

    fun currentBookId(): Long = currentBookId
    fun currentPositionMs(): Long {
        val p = player ?: return 0L
        val idx = p.currentMediaItemIndex
        val currentTrackMs = p.currentPosition.coerceAtLeast(0L)
        if (activeChapters.isNotEmpty() && idx in activeChapters.indices) {
            return activeChapters[idx].startMs + currentTrackMs
        }
        return currentTrackMs
    }

    fun durationMs(): Long {
        if (activeChapters.isNotEmpty()) {
            val last = activeChapters.last()
            return last.endMs ?: (last.startMs + 300_000L)
        }
        return player?.duration?.takeIf { it > 0L } ?: C.TIME_UNSET
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun playbackSpeed(): Float = player?.playbackParameters?.speed ?: 1f
    fun bookTitle(): String? = session?.player?.mediaMetadata?.title?.toString()
    fun bookAuthor(): String? = session?.player?.mediaMetadata?.artist?.toString()
    fun playPause() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            player?.let { it.playWhenReady = !it.playWhenReady }
        } else {
            serviceScope.launch(Dispatchers.Main) {
                player?.let { it.playWhenReady = !it.playWhenReady }
            }
        }
    }

    fun seekTo(ms: Long) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val p = player ?: return
            if (activeChapters.isNotEmpty()) {
                val idx = activeChapters.indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
                val trackMs = (ms - activeChapters[idx].startMs).coerceAtLeast(0L)
                p.seekTo(idx, trackMs)
            } else {
                p.seekTo(ms.coerceAtLeast(0L).let { if (durationMs() != C.TIME_UNSET) it.coerceAtMost(durationMs()) else it })
            }
        } else {
            serviceScope.launch(Dispatchers.Main) { seekTo(ms) }
        }
    }

    fun setSpeed(speed: Float) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            player?.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
        } else {
            serviceScope.launch(Dispatchers.Main) { setSpeed(speed) }
        }
    }
    fun chapters(): List<AudiobookChapter> = activeChapters
    fun currentChapterIndex(): Int = player?.currentMediaItemIndex ?: 0

    private fun parseChapters(json: String): List<AudiobookChapter> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudiobookChapter(
                    index = obj.optInt("index", i),
                    title = obj.optString("title", "Kapittel ${i + 1}"),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L),
                    mediaUri = if (obj.has("mediaUri") && !obj.isNull("mediaUri")) obj.getString("mediaUri") else null
                )
            }
        }.getOrElse { emptyList() }
    }
}
