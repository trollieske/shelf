package com.shelf.reader.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveAudioState(
    val bookId: Long = 0L,
    val title: String = "",
    val author: String = "",
    val isPlaying: Boolean = false,
    val progressPercent: Float = 0f,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0L
)

object ActivePlaybackState {
    private val _state = MutableStateFlow<ActiveAudioState?>(null)
    val state: StateFlow<ActiveAudioState?> = _state.asStateFlow()

    fun update(
        bookId: Long,
        title: String,
        author: String,
        isPlaying: Boolean,
        progressPercent: Float,
        sleepTimerMinutes: Int?,
        sleepTimerRemainingMs: Long
    ) {
        if (bookId <= 0L) {
            _state.value = null
            return
        }
        _state.value = ActiveAudioState(
            bookId = bookId,
            title = title,
            author = author,
            isPlaying = isPlaying,
            progressPercent = progressPercent,
            sleepTimerMinutes = sleepTimerMinutes,
            sleepTimerRemainingMs = sleepTimerRemainingMs
        )
    }

    fun clear() {
        _state.value = null
    }
}
