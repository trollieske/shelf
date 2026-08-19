package com.shelf.reader.core.gamification

import com.shelf.reader.core.gamification.model.SessionSource
import kotlinx.coroutines.flow.SharedFlow

interface ReadingTrackerFacade {
    val goalMetEvents: SharedFlow<Unit>

    fun initialize()
    fun cleanup()

    fun startSession(bookId: String, source: SessionSource)
    fun endSession()

    fun onUserInteraction()
    fun updateTtsPlaybackState(isPlaying: Boolean)
}
