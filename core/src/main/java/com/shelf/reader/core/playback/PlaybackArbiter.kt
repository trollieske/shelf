package com.shelf.reader.core.playback

/**
 * Process-wide arbiter that keeps Shelf's two independent audio engines from
 * ever playing at the same time.
 *
 * Audiobooks and podcasts each own a dedicated playback service with its own
 * MediaSession id. Neither service is aware of the other's internals; they only
 * register a "stop for other media" hook here. When one engine starts, it asks
 * the arbiter to stop every other registered engine.
 *
 * Registration uses a stable media-session id (e.g. "shelf_audio",
 * "shelf_podcast"). Hooks are expected to marshal to their own main thread.
 */
object PlaybackArbiter {

    private val stoppers = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** Media session id for the audiobook engine. */
    const val ID_AUDIOBOOK = "shelf_audio"

    /** Media session id for the podcast engine. */
    const val ID_PODCAST = "shelf_podcast"

    fun register(id: String, stopForOtherMedia: () -> Unit) {
        stoppers[id] = stopForOtherMedia
    }

    fun unregister(id: String) {
        stoppers.remove(id)
    }

    /** Stops every registered engine except [exceptId]. Safe to call from any thread. */
    fun stopOthers(exceptId: String) {
        stoppers.forEach { (id, stop) ->
            if (id != exceptId) {
                runCatching { stop() }
            }
        }
    }
}