package com.shelf.reader.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global "now playing" state for the podcast engine. Deliberately separate from
 * [ActivePlaybackState] (audiobooks). The two are mutually exclusive: updating
 * one clears the other so the app's mini-player can never show both, and podcast
 * progress can never leak into the audiobook resume UI.
 */
data class PodcastActiveState(
    val episodeId: Long = 0L,
    val feedId: Long = 0L,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val progressPercent: Float = 0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

object PodcastPlaybackState {
    private val _state = MutableStateFlow<PodcastActiveState?>(null)
    val state: StateFlow<PodcastActiveState?> = _state.asStateFlow()

    fun update(
        episodeId: Long,
        feedId: Long,
        title: String,
        podcastTitle: String,
        artworkUrl: String?,
        isPlaying: Boolean,
        progressPercent: Float,
        positionMs: Long,
        durationMs: Long
    ) {
        if (episodeId <= 0L) {
            _state.value = null
            return
        }
        // Starting/showing podcast playback always retires the audiobook mini-player.
        ActivePlaybackState.clear()
        _state.value = PodcastActiveState(
            episodeId = episodeId,
            feedId = feedId,
            title = title,
            podcastTitle = podcastTitle,
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            progressPercent = progressPercent.coerceIn(0f, 1f),
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    fun clear() {
        _state.value = null
    }
}