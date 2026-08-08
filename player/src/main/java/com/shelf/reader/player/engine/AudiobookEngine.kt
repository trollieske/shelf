package com.shelf.reader.player.engine

import android.content.Context
import android.net.Uri
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.BookEntity
import com.shelf.reader.data.local.entity.BookTypeEntity
import com.shelf.reader.data.local.entity.FormatEntity
import java.io.File
import kotlin.math.max

data class AudiobookChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long? = null,
    val mediaUri: String? = null
)

data class AudiobookState(
    val title: String,
    val author: String,
    val format: FormatEntity,
    val type: BookTypeEntity,
    val mediaUri: String? = null,
    val durationMs: Long = 0L,
    val currentMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val chapters: List<AudiobookChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val percent: Float = 0f,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0L,
    val error: String? = null
)

class AudiobookEngine(
    private val ctx: Context,
    private val db: ShelfDatabase
) {

    suspend fun loadBook(bookId: Long): AudiobookState {
        val book = db.bookDao().getById(bookId)
            ?: return AudiobookState(
                title = "",
                author = "",
                format = FormatEntity.UNKNOWN,
                type = BookTypeEntity.AUDIOBOOK,
                error = "Fant ikke boken"
            )

        val prog = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f

        val mediaUri = resolveSourceForPlayback(ctx, book)

        val durationMs = book.durationMs ?: estimateDuration(ctx, book, mediaUri)

        val chapters = book.chaptersJson?.let { parseChapters(it) }
            ?: buildStubChapters(book.title, durationMs)

        val currentMs = (prog * durationMs).toLong()
            .coerceAtMost(max(durationMs - 5_000L, 0L))

        val currentChapterIndex = chapters
            .indexOfLast { it.startMs <= currentMs }
            .coerceAtLeast(0)

        return AudiobookState(
            title = book.title,
            author = book.author,
            format = book.format,
            type = book.type,
            mediaUri = mediaUri,
            durationMs = durationMs,
            currentMs = currentMs,
            isPlaying = false,
            playbackSpeed = 1.0f,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            percent = prog,
            sleepTimerMinutes = null,
            error = null
        )
    }

    private fun resolveSourceForPlayback(ctx: Context, book: BookEntity): String? {
        book.filePath?.let { path ->
            val file = File(path)
            if (file.canRead()) {
                return path
            }
        }
        book.fileUri?.let { uri ->
            return try {
                Uri.parse(uri).toString()
            } catch (_: Exception) {
                uri
            }
        }
        return null
    }

    private fun estimateDuration(ctx: Context, book: BookEntity, mediaUri: String?): Long {
        return 2L * 60L * 60L * 1000L
    }

    private fun parseChapters(json: String): List<AudiobookChapter> {
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudiobookChapter(
                    index = obj.optInt("index", i),
                    title = obj.optString("title", "Kapittel ${i + 1}"),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L),
                    mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun buildStubChapters(bookTitle: String, durationMs: Long): List<AudiobookChapter> {
        val chapterCount = (durationMs / 2_700_000L).coerceAtLeast(1)
        val chapterDuration = durationMs / chapterCount
        return (0 until chapterCount.toInt()).map { i ->
            val start = i * chapterDuration
            val end = if (i == chapterCount.toInt() - 1) durationMs else (i + 1) * chapterDuration
            AudiobookChapter(
                index = i,
                title = "Kapittel ${i + 1}",
                startMs = start,
                endMs = end
            )
        }
    }

    suspend fun readDurationStub(book: BookEntity, uri: String?): Long {
        return book.durationMs ?: (120L * 60L * 1000L)
    }
}
