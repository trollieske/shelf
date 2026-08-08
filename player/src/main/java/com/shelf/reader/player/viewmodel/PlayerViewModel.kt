package com.shelf.reader.player.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.SessionCommand
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.BookTypeEntity
import com.shelf.reader.data.local.entity.FormatEntity
import com.shelf.reader.data.local.entity.ReadingProgressEntity
import com.shelf.reader.player.engine.AudiobookEngine
import com.shelf.reader.player.engine.AudiobookState
import com.shelf.reader.player.service.AudiobookPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class PlayerViewModel(
    application: Application,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val engine = AudiobookEngine(getApplication(), db)

    private var currentBookId: Long = 0L
    private var currentBookDurationMs: Long = 0L
    private var lastSavedPct: Float = -1f
    private var tickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var sleepStartedAtMs: Long? = null

    private val _serviceBound = MutableStateFlow(false)
    val serviceBound: StateFlow<Boolean> = _serviceBound.asStateFlow()

    private var service: AudiobookPlaybackService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // #region debug-point D:service-connected
            dbg("D", "service-connected", "component=${name?.className ?: ""} binder=${binder?.javaClass?.name ?: "null"}")
            // #endregion
            val localBinder = binder as? AudiobookPlaybackService.LocalBinder
            service = localBinder?.getService()
            _serviceBound.value = true
            startTicker()
            syncStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // #region debug-point D:service-disconnected
            dbg("D", "service-disconnected", "component=${name?.className ?: ""}")
            // #endregion
            service = null
            _serviceBound.value = false
            stopTicker()
        }
    }

    private val _state = MutableStateFlow(
        AudiobookState(
            title = "",
            author = "",
            format = FormatEntity.UNKNOWN,
            type = BookTypeEntity.AUDIOBOOK,
            mediaUri = null,
            durationMs = 0L,
            currentMs = 0L,
            isPlaying = false,
            playbackSpeed = 1.0f,
            chapters = emptyList(),
            currentChapterIndex = 0,
            percent = 0f,
            sleepTimerMinutes = null,
            error = null
        )
    )
    val state: StateFlow<AudiobookState> = _state.asStateFlow()

    fun load(bookId: Long) {
        currentBookId = bookId
        lastSavedPct = -1f

        viewModelScope.launch(dispatchers.io) {
            val initialState = engine.loadBook(bookId)
            // #region debug-point E:player-load-state
            dbg(
                "E",
                "player-load-state",
                "bookId=$bookId mediaUri=${initialState.mediaUri ?: ""} format=${initialState.format} error=${initialState.error ?: ""}"
            )
            // #endregion
            currentBookDurationMs = initialState.durationMs
            _state.value = initialState
        }

        val intent = Intent(getApplication(), AudiobookPlaybackService::class.java).apply {
            action = AudiobookPlaybackService.ACTION_LOAD_BOOK
            putExtra(AudiobookPlaybackService.EXTRA_BOOK_ID, bookId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // #region debug-point D:start-fgs
                dbg("D", "start-foreground-service-attempt", "bookId=$bookId sdk=${Build.VERSION.SDK_INT}")
                // #endregion
                startForegroundServiceCompat(intent)
                // #region debug-point D:start-fgs-ok
                dbg("D", "start-foreground-service-ok", "bookId=$bookId")
                // #endregion
            } catch (t: Throwable) {
                // #region debug-point D:start-fgs-fail
                dbg("D", "start-foreground-service-failed", "bookId=$bookId error=${t::class.java.simpleName}:${t.message}")
                // #endregion
                throw t
            }
        } else {
            getApplication<Application>().startService(intent)
        }

        val bindIntent = Intent(getApplication(), AudiobookPlaybackService::class.java)
        try {
            val bound = getApplication<Application>().bindService(
                bindIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            // #region debug-point D:bind-service
            dbg("D", "bind-service-result", "bookId=$bookId bound=$bound")
            // #endregion
        } catch (t: Throwable) {
            // #region debug-point D:bind-service-fail
            dbg("D", "bind-service-failed", "bookId=$bookId error=${t::class.java.simpleName}:${t.message}")
            // #endregion
            throw t
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundServiceCompat(intent: Intent) {
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    // #region debug-point shared:player-http
    private fun dbg(hypothesisId: String, msg: String, data: String) {
        Thread {
            try {
                val safeMsg = msg.replace("\\", "/").replace("\"", "'").replace("\n", " ")
                val safeData = data.replace("\\", "/").replace("\"", "'").replace("\n", " ")
                val body = """{"sessionId":"ebook-audio-crash","runId":"pre-fix","hypothesisId":"$hypothesisId","location":"PlayerViewModel","msg":"[DEBUG] $safeMsg","data":{"info":"$safeData"},"ts":${System.currentTimeMillis()}}"""
                val conn = (URL("http://192.168.1.10:7777/event").openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                runCatching { conn.inputStream.close() }
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }.start()
    }
    // #endregion

    private fun startTicker() {
        stopTicker()
        tickerJob = viewModelScope.launch(dispatchers.default) {
            while (_serviceBound.value) {
                syncStateFromService()
                delay(500L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun syncStateFromService() {
        val svc = service ?: return
        val current = _state.value

        viewModelScope.launch(dispatchers.main) {
            val currentMs = svc.currentPositionMs()
            val serviceDuration = svc.durationMs()
            val durationMs = if (serviceDuration > 0L) serviceDuration else currentBookDurationMs
            val isPlaying = svc.isPlaying()
            val playbackSpeed = svc.playbackSpeed()

            val title = svc.bookTitle()?.takeIf { it.isNotBlank() } ?: current.title
            val author = svc.bookAuthor()?.takeIf { it.isNotBlank() } ?: current.author

            val percent = if (durationMs > 0L) currentMs.toFloat() / durationMs.toFloat() else 0f

            val svcChapters = svc.chapters().ifEmpty { current.chapters }
            val currentChapterIndex = if (svc.chapters().isNotEmpty()) {
                svc.currentChapterIndex()
            } else {
                current.chapters.indexOfLast { it.startMs <= currentMs }.coerceAtLeast(0)
            }

            val remMs = svc.sleepTimerRemainingMs()
            val sleepMinutes = if (remMs > 0L) (remMs / 60_000L).toInt().coerceAtLeast(1) else null

            _state.value = current.copy(
                title = title,
                author = author,
                durationMs = durationMs,
                currentMs = currentMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                chapters = svcChapters,
                currentChapterIndex = currentChapterIndex,
                percent = percent.coerceIn(0f, 1f),
                sleepTimerMinutes = sleepMinutes,
                sleepTimerRemainingMs = remMs,
                error = null
            )

            if (currentBookId > 0L) {
                com.shelf.reader.data.repository.ActivePlaybackState.update(
                    bookId = currentBookId,
                    title = title,
                    author = author,
                    isPlaying = isPlaying,
                    progressPercent = percent.coerceIn(0f, 1f),
                    sleepTimerMinutes = sleepMinutes,
                    sleepTimerRemainingMs = remMs
                )
            }

            if (percent >= 0f && percent <= 1f) {
                val diff = kotlin.math.abs(percent - lastSavedPct)
                if (lastSavedPct < 0f || diff > 0.01f) {
                    lastSavedPct = percent
                    updateProgressDb(currentBookId, percent)
                }
            }
        }
    }

    fun playPause() {
        service?.playPause()
        syncStateFromService()
    }

    fun seekTo(ms: Long) {
        val duration = _state.value.durationMs
        val clamped = ms.coerceIn(0L, max(duration, 0L))
        service?.seekTo(clamped)
        syncStateFromService()
    }

    fun skipForward(ms: Long = 30_000L) {
        seekTo(_state.value.currentMs + ms)
    }

    fun skipBack(ms: Long = 10_000L) {
        seekTo(_state.value.currentMs - ms)
    }

    fun nextChapter() {
        val current = _state.value
        val nextStart = current.chapters
            .getOrNull(current.currentChapterIndex + 1)
            ?.startMs
            ?: current.durationMs
        seekTo(nextStart)
    }

    fun prevChapter() {
        val current = _state.value
        val prevStart = current.chapters
            .getOrNull(current.currentChapterIndex - 1)
            ?.startMs
            ?: 0L
        seekTo(prevStart)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        service?.setSpeed(clamped)
        syncStateFromService()
    }

    fun setSleepTimer(minutes: Int?) {
        _state.value = _state.value.copy(sleepTimerMinutes = minutes)
        
        viewModelScope.launch(dispatchers.main) {
            val svc = service ?: return@launch
            if (minutes != null) {
                svc.startSleepTimer(minutes)
            } else {
                svc.cancelSleepTimer()
            }
        }
    }

    private fun updateProgressDb(bookId: Long, pct: Float) {
        if (bookId == 0L) return
        viewModelScope.launch(dispatchers.io) {
            db.progressDao().insertOrReplace(
                ReadingProgressEntity(
                    bookId = bookId,
                    progressPercent = pct
                )
            )
        }
    }

    override fun onCleared() {
        stopTicker()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepStartedAtMs = null
        if (_serviceBound.value) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (_: Exception) {
            }
        }
        service = null
        _serviceBound.value = false
        super.onCleared()
    }
}
