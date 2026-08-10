package com.shelf.reader.reader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.BookTypeEntity
import com.shelf.reader.data.local.entity.FormatEntity
import com.shelf.reader.data.local.entity.ReadingProgressEntity
import com.shelf.reader.reader.engine.BookLoaderEngine
import com.shelf.reader.reader.engine.ReaderBookState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    application: Application,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : AndroidViewModel(application) {

    private val engine = BookLoaderEngine(getApplication(), db)

    private val _currentBookId = MutableStateFlow(0L)

    private val _state = MutableStateFlow(
        ReaderBookState(
            bookTitle        = "",
            author           = "",
            format           = FormatEntity.UNKNOWN,
            type             = BookTypeEntity.EBOOK,
            chapters         = emptyList(),
            currentChapterIndex = 0,
            percent          = 0f,
            scrollPct        = 0f,
            // Page-turn engine state
            currentPage      = 0,
            totalPages       = 0,
            error            = null,
        )
    )
    val state: StateFlow<ReaderBookState> = _state.asStateFlow()

    // ── Book loading ──────────────────────────────────────────────────────────

    fun load(bookId: Long) {
        _currentBookId.value = bookId
        viewModelScope.launch(dispatchers.io) {
            val existing    = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
            val engineState = engine.loadBook(bookId)
            _state.value = engineState.copy(
                percent   = existing?.progressPercent ?: engineState.percent,
                scrollPct = existing?.scrollPct ?: 0f,
                currentPage = 0,
                totalPages  = 0,   // will be filled in by HtmlPageRenderer via onPageCountKnown
            )
        }
    }

    // ── Page-turn engine callbacks ────────────────────────────────────────────

    /** Called by [PageCurlReader] once the renderer knows the total page count. */
    fun onPageCountKnown(count: Int) {
        val prior = _state.value
        val pending = prior.pendingRepositionPct
        if (pending != null && count > 0) {
            val newPage = (pending * count.toFloat()).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
            _state.value = prior.copy(
                totalPages = count,
                currentPage = newPage,
                percent = pending,
                pendingRepositionPct = null,
            )
        } else {
            _state.value = prior.copy(totalPages = count)
        }
    }

    /** Called each time the user completes a page turn. Updates progress. */
    fun onPageTurned(page: Int) {
        if (page == _state.value.currentPage) return
        val pct = if (_state.value.totalPages > 0)
            page.toFloat() / _state.value.totalPages.toFloat()
        else 0f
        _state.value = _state.value.copy(currentPage = page, percent = pct)
        persistProgress(pct)
    }

    // ── Remaining API (used by BottomBar seek slider) ─────────────────────────

    fun updateProgress(pct: Float) {
        if (pct == _state.value.percent) return
        _state.value = _state.value.copy(percent = pct)
        persistProgress(pct)
    }

    fun seekToPercent(pct: Float) {
        val page = if (_state.value.totalPages > 0)
            (pct * _state.value.totalPages).toInt().coerceIn(0, _state.value.totalPages - 1)
        else 0
        _state.value = _state.value.copy(currentPage = page, percent = pct)
        persistProgress(pct)
    }

    fun setCurrentChapter(index: Int) {
        if (index < 0 || index >= _state.value.chapters.size) return
        if (index == _state.value.currentChapterIndex) return
        val pct = index.toFloat() / _state.value.chapters.size.toFloat()
        _state.value = _state.value.copy(
            currentChapterIndex = index,
            currentPage = 0,
            percent = pct,
        )
        persistProgress(pct)
    }

    fun nextChapter() {
        val next = _state.value.currentChapterIndex + 1
        if (next < _state.value.chapters.size) {
            setCurrentChapter(next)
        }
    }

    fun previousChapter() {
        val prev = _state.value.currentChapterIndex - 1
        if (prev >= 0) {
            setCurrentChapter(prev)
        }
    }

    fun setFontSize(sp: Int) {
        val prior = _state.value
        val currentPct = if (prior.totalPages > 0)
            prior.currentPage.toFloat() / prior.totalPages.toFloat()
        else prior.percent
        _state.value = prior.copy(
            fontSizeSp = sp.coerceIn(12, 36),
            pendingRepositionPct = currentPct.coerceIn(0f, 1f),
        )
    }

    fun setTheme(theme: String) {
        val prior = _state.value
        val currentPct = if (prior.totalPages > 0)
            prior.currentPage.toFloat() / prior.totalPages.toFloat()
        else prior.percent
        _state.value = prior.copy(
            readerTheme = theme,
            pendingRepositionPct = currentPct.coerceIn(0f, 1f),
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun persistProgress(pct: Float) {
        val bookId = _currentBookId.value
        if (bookId == 0L) return
        viewModelScope.launch(dispatchers.io) {
            val prior = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
            db.progressDao().insertOrReplace(
                (prior ?: ReadingProgressEntity(bookId = bookId)).copy(
                    progressPercent = pct,
                    updatedAt       = System.currentTimeMillis(),
                )
            )
        }
    }
}
