package com.shelf.reader.podcast.domain

import com.shelf.reader.data.repository.ActivePlaybackState
import com.shelf.reader.data.repository.PodcastPlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Podcast playback progress must stay separate from ebook/audiobook progress.
 * The two mini-player states are also mutually exclusive.
 */
class PodcastPlaybackStateIsolationTest {

    @After
    fun tearDown() {
        ActivePlaybackState.clear()
        PodcastPlaybackState.clear()
    }

    @Test
    fun `starting a podcast clears the audiobook state`() {
        ActivePlaybackState.update(
            bookId = 42L,
            title = "Audiobook",
            author = "Author",
            isPlaying = true,
            progressPercent = 0.5f,
            sleepTimerMinutes = null,
            sleepTimerRemainingMs = 0L
        )
        assertNotNull(ActivePlaybackState.state.value)

        PodcastPlaybackState.update(
            episodeId = 7L,
            feedId = 3L,
            title = "Episode",
            podcastTitle = "Podcast",
            artworkUrl = null,
            isPlaying = true,
            progressPercent = 0.1f,
            positionMs = 1000L,
            durationMs = 10_000L
        )
        assertNull(ActivePlaybackState.state.value)
        assertNotNull(PodcastPlaybackState.state.value)
    }

    @Test
    fun `starting an audiobook clears the podcast state`() {
        PodcastPlaybackState.update(
            episodeId = 7L,
            feedId = 3L,
            title = "Episode",
            podcastTitle = "Podcast",
            artworkUrl = null,
            isPlaying = true,
            progressPercent = 0.1f,
            positionMs = 1000L,
            durationMs = 10_000L
        )
        assertNotNull(PodcastPlaybackState.state.value)

        ActivePlaybackState.update(
            bookId = 42L,
            title = "Audiobook",
            author = "Author",
            isPlaying = true,
            progressPercent = 0.5f,
            sleepTimerMinutes = null,
            sleepTimerRemainingMs = 0L
        )
        assertNull(PodcastPlaybackState.state.value)
        assertNotNull(ActivePlaybackState.state.value)
    }

    @Test
    fun `clearing podcast state leaves audiobook state untouched`() {
        ActivePlaybackState.update(
            bookId = 42L,
            title = "Audiobook",
            author = "Author",
            isPlaying = false,
            progressPercent = 0.5f,
            sleepTimerMinutes = null,
            sleepTimerRemainingMs = 0L
        )
        PodcastPlaybackState.clear()
        assertNotNull(ActivePlaybackState.state.value)
        assertEquals(42L, ActivePlaybackState.state.value!!.bookId)
    }
}