package com.shelf.reader.reader.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.shelf.reader.core.parse.ParsedBook
import com.shelf.reader.core.parse.buildComicHtml
import com.shelf.reader.core.parse.parseCbz
import com.shelf.reader.core.parse.parseEpub
import com.shelf.reader.core.parse.parseFb2
import com.shelf.reader.core.parse.parsePdf
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.BookTypeEntity
import com.shelf.reader.data.local.entity.FormatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "BookLoaderEngine"

data class ReaderChapter(
    val index: Int,
    val title: String,
    val htmlContent: String,
    val startByte: Int,
    val endByte: Int? = null
)

data class ReaderBookState(
    val bookId: Long = 0L,
    val bookTitle: String,
    val author: String,
    val format: FormatEntity,
    val type: BookTypeEntity,
    val chapters: List<ReaderChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val percent: Float = 0f,
    val scrollPct: Float = 0f,
    val fontSizeSp: Int = 18,
    val readerTheme: String = "sepia",
    /** Global page index within the rendered book (filled by PageCurlReader). */
    val currentPage: Int = 0,
    /** Total page count after off-screen rendering (filled by HtmlPageRenderer). */
    val totalPages: Int = 0,
    val error: String? = null
)

class BookLoaderEngine(
    private val ctx: Context,
    private val db: ShelfDatabase
) {

    suspend fun loadBook(bookId: Long): ReaderBookState = withContext(Dispatchers.IO) {
        val book = db.bookDao().getById(bookId)
            ?: return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = "",
                author = "",
                format = FormatEntity.UNKNOWN,
                type = BookTypeEntity.EBOOK,
                error = "Fant ikke boken"
            )

        val percent = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f

        // SAFETY: If it's an audiobook, do NOT try to read it as text.
        if (book.type == BookTypeEntity.AUDIOBOOK && !isSupportedEbook(book.format)) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Dette er en lydbok. Bruk spilleren i stedet."
            )
        }

        val filePath = book.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.canRead() }
        val fileUri = book.fileUri?.takeIf { it.isNotBlank() }

        val parsed: ParsedBook? = try {
            when (book.format) {
                FormatEntity.EPUB -> {
                    val first = parseEpub(ctx, filePath?.absolutePath, null)
                    if (first == null && filePath != null && filePath.exists()) {
                        openStreamSafely(filePath, fileUri)?.use { s -> parseEpub(ctx, null, s) }
                    } else {
                        first ?: openStreamSafely(null, fileUri)?.use { s -> parseEpub(ctx, null, s) }
                    }
                }
                FormatEntity.FB2 -> {
                    parseFb2(ctx, filePath?.absolutePath, null)
                        ?: openStreamSafely(filePath, fileUri)?.use { s -> parseFb2(ctx, null, s) }
                }
                FormatEntity.PDF -> parsePdf(ctx, filePath?.absolutePath, null)
                    ?: openStreamSafely(filePath, fileUri)?.use { s -> parsePdf(ctx, null, s) }
                FormatEntity.CBZ -> (parseCbz(ctx, filePath?.absolutePath, null)
                    ?: openStreamSafely(filePath, fileUri)?.use { s -> parseCbz(ctx, null, s) })?.let { buildComicHtml(it) }
                FormatEntity.CBR -> (parseCbz(ctx, filePath?.absolutePath, null)
                    ?: openStreamSafely(filePath, fileUri)?.use { s -> parseCbz(ctx, null, s) })?.let { buildComicHtml(it) }
                else -> null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Parser crash", t)
            null
        }

        if (parsed != null && parsed.chapters.isNotEmpty()) {
            val readerChapters = parsed.chapters.map {
                ReaderChapter(it.index, it.title, it.htmlContent, it.startByte, it.startByte + it.byteLength)
            }
            if (book.chaptersJson.isNullOrBlank() || book.chapterCount != parsed.chapters.size) {
                runCatching {
                    val jsonArray = org.json.JSONArray()
                    parsed.chapters.forEach { jsonArray.put(it.title) }
                    val refreshed = book.copy(
                        chapterCount = parsed.chapters.size,
                        chaptersJson = jsonArray.toString(),
                        lastModifiedAt = System.currentTimeMillis()
                    )
                    db.bookDao().update(refreshed)
                }
            }
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = parsed.title?.ifBlank { book.title } ?: book.title,
                author = parsed.author?.ifBlank { book.author } ?: book.author,
                format = book.format,
                type = book.type,
                chapters = readerChapters,
                currentChapterIndex = 0,
                percent = percent,
                error = null
            )
        }

        // Fallback for non-structured or failed parsing
        val bytes: ByteArray = try {
            openStreamSafely(filePath, fileUri)?.use { s -> s.readBytes().takeIf { it.isNotEmpty() } }
                ?: return@withContext ReaderBookState(
                    bookId = bookId,
                    bookTitle = book.title,
                    author = book.author,
                    format = book.format,
                    type = book.type,
                    percent = percent,
                    error = if (fileUri == null && filePath == null) "Fant ikke filen."
                            else "Ingen tilgang til boken. Prøv å importere mappen på nytt."
                )
        } catch (t: Throwable) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Feil ved lesing av fil: ${t.message ?: "Ukjent feil"}"
            )
        }

        // For structured formats that failed, show clear error rather than trying raw text decode
        if (book.format in listOf(FormatEntity.EPUB, FormatEntity.FB2, FormatEntity.MOBI, FormatEntity.CBZ, FormatEntity.CBR, FormatEntity.ZIP, FormatEntity.PDF)) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Klarte ikke å parse ${book.format.name}-filen. Filen kan være skadet, tom eller kryptert (DRM)."
            )
        }

        // Final attempt: treat as raw text/markdown if not already handled
        val rawContent = try {
            val utf8 = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            // Heuristic: if UTF-8 decode produces replacement chars (U+FFFD), try Windows-1252 (Norwegian/Western)
            val decoded = if (utf8.contains('\uFFFD') && bytes.size < 10_000_000) {
                runCatching {
                    bytes.toString(java.nio.charset.Charset.forName("windows-1252"))
                }.getOrElse { utf8 }
            } else {
                utf8
            }
            decoded.replace("\u0000", "")
        } catch (_: Exception) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Klarte ikke å lese filinnholdet som tekst."
            )
        }
        val chapters = buildChapters(book.format, rawContent, bytes.size)

        return@withContext ReaderBookState(
            bookId = bookId,
            bookTitle = book.title,
            author = book.author,
            format = book.format,
            type = book.type,
            chapters = chapters,
            currentChapterIndex = 0,
            percent = percent,
            error = null
        )
    }

    private fun openStreamSafely(filePath: File?, fileUri: String?): InputStream? {
        if (filePath != null && filePath.canRead()) {
            return try {
                FileInputStream(filePath)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open file stream for ${filePath.absolutePath}", t)
                null
            }
        }
        if (!fileUri.isNullOrBlank()) {
            return try {
                ctx.contentResolver.openInputStream(Uri.parse(fileUri))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open URI stream for $fileUri. " +
                        "This usually means persistable permissions were lost.", t)
                null
            }
        }
        return null
    }

    private fun buildChapters(
        format: FormatEntity,
        content: String,
        totalBytes: Int
    ): List<ReaderChapter> {
        if (format == FormatEntity.HTML) {
            return listOf(ReaderChapter(0, "Innhold", content, 0, totalBytes))
        }

        // Split into paragraphs: blank lines separate paragraphs; long lines auto-split at 500 chars
        // NOTE: use literal \r?\n (not double-escaped) to actually match newlines
        val rawLines = content.split(Regex("\r?\n"))
        val paragraphs = mutableListOf<String>()
        var currentP = StringBuilder()

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (currentP.isNotEmpty()) {
                    paragraphs.add(currentP.toString())
                    currentP = StringBuilder()
                }
            } else {
                if (currentP.isNotEmpty()) currentP.append(" ")
                currentP.append(trimmed)
                if (currentP.length > 500) {
                    paragraphs.add(currentP.toString())
                    currentP = StringBuilder()
                }
            }
        }
        if (currentP.isNotEmpty()) {
            paragraphs.add(currentP.toString())
        }

        if (paragraphs.isEmpty()) {
            return listOf(ReaderChapter(0, "Innhold", "<section><p>Tom fil.</p></section>", 0, totalBytes))
        }

        // Chunk paragraphs: 40 paragraphs per chapter gives good page density
        // This is critical for TXT paging to work — too many paragraphs in one chapter
        // causes WebView to measure incorrectly and prevents page turns.
        val chunkSize = 40
        val chunks = paragraphs.chunked(chunkSize)

        val chapters = mutableListOf<ReaderChapter>()
        var cumulativeStart = 0
        chunks.forEachIndexed { i, pList ->
            val htmlBody = pList.joinToString("") { p ->
                val escaped = escapeHtml(p)
                "<p>$escaped</p>"
            }
            val htmlContent = "<section>$htmlBody</section>"
            val byteLength = htmlContent.toByteArray().size
            chapters.add(
                ReaderChapter(
                    index = i,
                    title = if (chunks.size == 1) "Innhold" else "Del ${i + 1}",
                    htmlContent = htmlContent,
                    startByte = cumulativeStart,
                    endByte = cumulativeStart + byteLength
                )
            )
            cumulativeStart += byteLength
        }

        return chapters
    }

    private fun isSupportedEbook(format: FormatEntity): Boolean = when (format) {
        FormatEntity.EPUB, FormatEntity.PDF, FormatEntity.FB2, FormatEntity.MOBI,
        FormatEntity.CBZ, FormatEntity.CBR, FormatEntity.TXT, FormatEntity.MD,
        FormatEntity.HTML, FormatEntity.RTF, FormatEntity.DOCX -> true
        else -> false
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
