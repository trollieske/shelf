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
    val coverPath: String? = null,
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
                coverPath = null,
                error = "Fant ikke boken"
            )

        val prog = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f

        val mediaUri = resolveSourceForPlayback(ctx, book)

        val durationMs = book.durationMs ?: estimateDuration(ctx, book, mediaUri)

        // Kapitler fra chaptersJson; ved ≤ 1 kapittel prøver vi å HELE boken ved å
        // re-parse innebygde kapitler (chpl/CHAP) fra kildefilen og oppdatere DB-en.
        // Dette reparerer bøker importert før chpl-parsingen ble fikset — uten
        // re-import, og uten å røre fremdrift/posisjon.
        var chapters = book.chaptersJson?.let { parseChapters(it) }
            ?: buildStubChapters(book.title, durationMs)
        if (book.type == BookTypeEntity.AUDIOBOOK && chapters.size <= 1) {
            rescanEmbeddedChapters(book, mediaUri)?.let { (fixed, json) ->
                chapters = fixed
                runCatching {
                    db.bookDao().update(
                        book.copy(
                            chaptersJson = json,
                            chapterCount = fixed.size,
                            durationMs = book.durationMs?.takeIf { it > 0L } ?: estimateDuration(ctx, book, mediaUri),
                            lastModifiedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        val currentMs = (prog * durationMs).toLong()
            .coerceAtMost(max(durationMs - 5_000L, 0L))

        val currentChapterIndex = chapters
            .indexOfLast { it.startMs <= currentMs }
            .coerceAtLeast(0)

        val coverPath = resolveCoverPath(ctx, book)

        return AudiobookState(
            title = book.title,
            author = book.author,
            format = book.format,
            type = book.type,
            coverPath = coverPath,
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

    private fun resolveCoverPath(ctx: Context, book: BookEntity): String? {
        book.coverPath?.let { p ->
            if (File(p).exists()) return p
        }
        val generated = File(ctx.filesDir, "covers/book_${book.id}.webp")
        if (generated.exists()) return generated.absolutePath
        return null
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

    /**
     * Auto-reparasjon: hvis en lydbok har ≤ 1 kapittel i DB-en, prøv å re-parse
     * innebygde kapitler fra kildefilen (M4B chpl / MP3 ID3 CHAP). Returnerer
     * (kapitler, chaptersJson) ved suksess (> 1 kapitler), ellers null.
     * Endrer aldri fremdrift, aldrig avspillingsposisjon — kun kapittelmetadata.
     */
    private suspend fun rescanEmbeddedChapters(
        book: BookEntity,
        mediaUri: String?,
    ): Pair<List<AudiobookChapter>, String>? {
        val source = mediaUri?.takeIf { it.isNotBlank() } ?: return null
        val coreFormat = when (book.format) {
            com.shelf.reader.data.local.entity.FormatEntity.M4B -> com.shelf.reader.core.domain.model.BookFormat.M4B
            com.shelf.reader.data.local.entity.FormatEntity.M4A -> com.shelf.reader.core.domain.model.BookFormat.M4A
            com.shelf.reader.data.local.entity.FormatEntity.MP3 -> com.shelf.reader.core.domain.model.BookFormat.MP3
            com.shelf.reader.data.local.entity.FormatEntity.AAC -> com.shelf.reader.core.domain.model.BookFormat.AAC
            else -> return null
        }
        val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
        return runCatching {
            val parser = com.shelf.reader.core.parse.getParserFor(coreFormat)
            val meta = parser.parse(ctx, uri, book.title, 0L, null)
            val list = meta?.chapters.orEmpty().filter { it.startMs >= 0L }
            if (list.size <= 1) return null
            val duration = meta?.durationMs ?: 0L
            val arr = org.json.JSONArray()
            list.forEachIndexed { idx, ch ->
                val end = list.getOrNull(idx + 1)?.startMs?.takeIf { it > list[idx].startMs }
                    ?: (duration.takeIf { it > list[idx].startMs })
                    ?: (list[idx].endMs?.takeIf { it > list[idx].startMs })
                    ?: (list[idx].startMs + 10L * 60L * 1000L)
                val obj = org.json.JSONObject().apply {
                    put("index", idx)
                    put("title", list[idx].title.ifBlank { "Kapittel ${idx + 1}" })
                    put("startMs", list[idx].startMs)
                    put("endMs", end)
                    put("mediaUri", source)
                    put("durationMs", (end - list[idx].startMs).coerceAtLeast(1L))
                }
                arr.put(obj)
            }
            val chapters = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudiobookChapter(
                    index = i,
                    title = obj.optString("title", "Kapittel ${i + 1}"),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L).takeIf { it > 0L },
                    mediaUri = source
                )
            }
            Pair(chapters, arr.toString())
        }.getOrNull()
    }

    private fun parseChapters(json: String): List<AudiobookChapter> {
        val parsed = runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudiobookChapter(
                    index = obj.optInt("index", i),
                    title = obj.optString("title", "Kapittel ${i + 1}"),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L).takeIf { it > 0L },
                    mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
                )
            }
        }.getOrElse { emptyList() }
        return normalizeChapterEnds(parsed)
    }

    /** Fyll inn manglende endMs fra neste kapittel / varighet slik at klipping blir eksakt. */
    private fun normalizeChapterEnds(list: List<AudiobookChapter>): List<AudiobookChapter> {
        if (list.isEmpty()) return list
        return list.mapIndexed { i, ch ->
            val end = ch.endMs ?: list.getOrNull(i + 1)?.startMs?.takeIf { it > ch.startMs }
            ch.copy(endMs = end)
        }
    }

    /**
     * Ingen kapittelinformasjon i det hele tatt: ÉN kapittelpost med boktittelen.
     * Aldri fabrikerte "Kapittel N"-stubber — spilleren viser boken som den er.
     */
    private fun buildStubChapters(bookTitle: String, durationMs: Long): List<AudiobookChapter> {
        return listOf(
            AudiobookChapter(
                index = 0,
                title = bookTitle.ifBlank { "Lydbok" },
                startMs = 0L,
                endMs = durationMs.takeIf { it > 0L }
            )
        )
    }

    suspend fun readDurationStub(book: BookEntity, uri: String?): Long {
        return book.durationMs ?: (120L * 60L * 1000L)
    }
}
