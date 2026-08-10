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
    val error: String? = null,
    /** When non-null: the next time onPageCountKnown arrives, re-seek currentPage to this percent.
     *  Set by setFontSize() / setTheme() to preserve the user's reading position when
     *  pagination changes (same relative position in the text, like iBooks does). */
    val pendingRepositionPct: Float? = null,
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

        // Fallback for non-structured or failed parsing: stream in chunks with hard cap to prevent
        // OOM from multi-GB audiobook/PDF files accidentally hitting this fallback.
        data class FallbackPacket(val drmScan: ByteArray, val fullText: ByteArray, val sizeBytes: Long)

        val packet: FallbackPacket = try {
            openStreamSafely(filePath, fileUri)?.use { s ->
                val capForDrmScan = 256 * 1024   // 256 KB suffices for MOBI EXTH + bookmobi magic
                val capForFullText = 320 * 1024 * 1024 // 320 MB hard cap for raw text decode (OOM-safe)
                val drmBuf = ByteArray(capForDrmScan)
                var drmLen = 0
                // Read drm-scan head
                while (drmLen < capForDrmScan) {
                    val n = s.read(drmBuf, drmLen, capForDrmScan - drmLen)
                    if (n < 0) break
                    drmLen += n
                }
                // Remaining → text buffer, bounded
                val textBuf = java.io.ByteArrayOutputStream(drmLen.coerceAtLeast(8192))
                textBuf.write(drmBuf, 0, drmLen)
                val copyBuf = ByteArray(64 * 1024)
                var total = drmLen.toLong()
                while (total < capForFullText) {
                    val n = s.read(copyBuf)
                    if (n < 0) break
                    textBuf.write(copyBuf, 0, n)
                    total += n
                }
                FallbackPacket(
                    drmScan = if (drmLen == drmBuf.size) drmBuf else drmBuf.copyOf(drmLen),
                    fullText = textBuf.toByteArray(),
                    sizeBytes = total
                )
            } ?: return@withContext ReaderBookState(
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
        val bytesForDrm: ByteArray = packet.drmScan
        val bytes: ByteArray = packet.fullText
        val totalBytes = packet.sizeBytes.toInt().coerceAtLeast(bytes.size)

        // Structured format failure handling:
        //  - EPUB/FB2/CBZ/CBR/ZIP/PDF : show structured-parse error (they have actual parsers)
        //  - MOBI/AZW/AZW3          : first try to detect real DRM; if not clearly DRM-encrypted,
        //                              fall through to raw-text decoder so DRM-free PalmDOC books work.
        val definitelyDamagedOrDrm = when {
            book.format in listOf(FormatEntity.EPUB, FormatEntity.FB2, FormatEntity.CBZ,
                FormatEntity.CBR, FormatEntity.ZIP, FormatEntity.PDF) -> true
            book.format == FormatEntity.MOBI || book.format == FormatEntity.AZW
                || book.format == FormatEntity.AZW3 -> mobiHasDrmOrUnreadable(bytesForDrm, bytes, totalBytes.toLong())
            else -> false
        }
        if (definitelyDamagedOrDrm) {
            val drmHint = if (book.format == FormatEntity.MOBI || book.format == FormatEntity.AZW
                || book.format == FormatEntity.AZW3)
                "Filen ser ut til å være beskyttet av Amazon/Kindle DRM (kryptert). Det er ikke tillatt å omgå DRM. Prøv en DRM-fri kopi, EPub-versjonen, eller importer bok fra en ekstern kilde som tilbyr åpne formater."
            else
                "Filen kan være skadet, tom, eller ha et støttet format som ikke kunne tolkes."
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Klarte ikke å åpne ${book.format.name}-filen. $drmHint"
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
        val chapters = buildChapters(book.format, rawContent, totalBytes)

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
        FormatEntity.AZW, FormatEntity.AZW3,
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

    /**
     * Cheap heuristic to detect genuinely DRM-encrypted or unreadable MOBI-family files.
     *
     * Returns true only when the file looks encrypted/corrupted to the point that our
     * raw-text fallback will be useless garbage. Returns false when the file contains
     * meaningful readable plaintext spans (DRM-free MOBI7/PalmDOC always have plenty of
     * readable text in record payloads even without decompression).
     *
     * Detects:
     *  - PalmDOC BOOKMOBI magic missing (probably not a MOBI at all -> show drm error)
     *  - EXTH record type 208 (DRM ServerId) / 206 (DRM count) present -> encrypted
     *  - < 3 readable text runs of >= 60 chars in first 256KB -> definitely encrypted or empty
     */
    private fun mobiHasDrmOrUnreadable(
        drmScanHead: ByteArray,
        fullBytes: ByteArray,
        totalBytes: Long
    ): Boolean {
        if (totalBytes in 1 until 200) return true
        val head = if (drmScanHead.isNotEmpty()) drmScanHead else fullBytes
        if (head.isEmpty()) return true

        val hasBookMobiMagic = (60 + 7 < head.size) &&
            (head[60].toInt() and 0xFF) == 'B'.code && (head[61].toInt() and 0xFF) == 'O'.code &&
            (head[62].toInt() and 0xFF) == 'O'.code && (head[63].toInt() and 0xFF) == 'K'.code &&
            (head[64].toInt() and 0xFF) == 'M'.code && (head[65].toInt() and 0xFF) == 'O'.code &&
            (head[66].toInt() and 0xFF) == 'B'.code && (head[67].toInt() and 0xFF) == 'I'.code
        if (!hasBookMobiMagic) {
            return true
        }

        // 2. Try to locate the EXTH header and check for DRM record types (206, 207, 208, 209, 210)
        try {
            val mobiStart = 78
            if (mobiStart + 92 <= head.size) {
                val mobiHeaderLen = readIntBE(head, mobiStart + 4)
                val exthFlags2 = readIntBE(head, mobiStart + 0x80)
                val hasExt = (exthFlags2 and 0x40) != 0
                if (hasExt && mobiHeaderLen > 0) {
                    val exthStart = mobiStart + mobiHeaderLen
                    if (exthStart + 12 < head.size) {
                        val exthMagicOk = (head[exthStart].toInt() and 0xFF) == 'E'.code &&
                            (head[exthStart + 1].toInt() and 0xFF) == 'X'.code &&
                            (head[exthStart + 2].toInt() and 0xFF) == 'T'.code &&
                            (head[exthStart + 3].toInt() and 0xFF) == 'H'.code
                        if (exthMagicOk) {
                            val recCount = readIntBE(head, exthStart + 4)
                            var recPos = exthStart + 12
                            for (r in 0 until recCount.coerceAtMost(400)) {
                                if (recPos + 8 > head.size) break
                                val recType = readIntBE(head, recPos)
                                val recLen = readIntBE(head, recPos + 4).coerceAtLeast(8)
                                if (recType in 200..220 && recType !in listOf(201, 203, 204, 205)) {
                                    if (recType == 206 || recType == 207 || recType == 208 ||
                                        recType == 209 || recType == 210) {
                                        return true
                                    }
                                }
                                recPos += recLen
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) { /* ignore, fall through to text-scan heuristic */ }

        // 3. Scan first 256KB for 3+ readable text runs of >= 60 chars
        val textBuf: ByteArray = if (fullBytes.size >= head.size) fullBytes else head
        val scanLen = textBuf.size.coerceAtMost(256 * 1024)
        var runCount = 0
        var currentRun = 0
        var i = 0
        while (i < scanLen) {
            val b = textBuf[i]
            val printable = (b in 32..126) || (b >= 0xA0.toByte() && b < 0.toByte()) ||
                b == 0x0A.toByte() || b == 0x0D.toByte() || b == 0x09.toByte() ||
                b.toInt() == 0xC2 || b.toInt() == 0xC3 || b.toInt() == 0xC5 ||  // UTF-8 start bytes for common Western
                b.toInt() == 0xC4 || b.toInt() == 0xC6 || b.toInt() == 0xC9 ||
                b.toInt() == 0xCB || b.toInt() == 0xCC || b.toInt() == 0xCD ||
                b.toInt() == 0x82 || b.toInt() == 0x98 || b.toInt() == 0xA6
            if (printable) {
                currentRun++
                if (currentRun >= 60) {
                    runCount++
                    currentRun = 0
                    if (runCount >= 3) return false
                }
            } else {
                currentRun = 0
            }
            i++
        }
        return runCount < 3
    }

    private fun readIntBE(bytes: ByteArray, off: Int): Int {
        if (off + 4 > bytes.size) return 0
        return ((bytes[off].toInt() and 0xFF) shl 24) or
            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
            (bytes[off + 3].toInt() and 0xFF)
    }
}
