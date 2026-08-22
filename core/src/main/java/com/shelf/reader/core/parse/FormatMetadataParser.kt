package com.shelf.reader.core.parse

import android.content.Context
import android.net.Uri
import com.shelf.reader.core.domain.model.BookFormat
import com.shelf.reader.core.domain.model.BookMetadata
import com.shelf.reader.core.domain.model.ChapterInfo
import java.io.InputStream
import java.util.zip.ZipFile

interface FormatMetadataParser {
    suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata?
}

private fun filenameWithoutExtension(filename: String): String {
    val lower = filename.lowercase()
    if (lower.endsWith(".fb2.zip")) {
        return filename.substring(0, filename.length - 8)
    }
    val dot = filename.lastIndexOf('.')
    return if (dot < 0) filename else filename.substring(0, dot)
}

private fun fallbackMetadata(filename: String): BookMetadata {
    return BookMetadata(
        title = filenameWithoutExtension(filename),
        author = "",
        series = null,
        seriesIndex = null,
        description = null,
        publisher = null,
        publishedDate = null,
        language = null,
        isbn = null,
        pageCount = null,
        durationMs = null,
        chapters = emptyList()
    )
}

class EpubMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class PdfMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class MobiMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        val base = fallbackMetadata(filename)
        val bytes = tryReadFirstBytes(ctx, uri, filename, sizeBytes, sourceStreamProvider)
            ?: return base
        return try {
            val meta = MobiUnpack.parseMetadata(bytes)
            base.copy(
                title = meta.title?.ifBlank { null } ?: base.title,
                author = meta.author?.ifBlank { null } ?: base.author,
                publisher = meta.publisher,
                publishedDate = meta.publishedDate,
                language = meta.language,
                isbn = meta.isbn,
                description = meta.description
            )
        } catch (_: Throwable) {
            base
        }
    }
}

class AzwMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        val base = fallbackMetadata(filename)
        val bytes = tryReadFirstBytes(ctx, uri, filename, sizeBytes, sourceStreamProvider)
            ?: return base
        return try {
            val meta = MobiUnpack.parseMetadata(bytes)
            base.copy(
                title = meta.title?.ifBlank { null } ?: base.title,
                author = meta.author?.ifBlank { null } ?: base.author,
                publisher = meta.publisher,
                publishedDate = meta.publishedDate,
                language = meta.language,
                isbn = meta.isbn,
                description = meta.description
            )
        } catch (_: Throwable) {
            base
        }
    }
}

private suspend fun tryReadFirstBytes(
    ctx: Context,
    uri: Uri?,
    filename: String,
    sizeBytes: Long,
    sourceStreamProvider: (suspend () -> InputStream)?
): ByteArray? {
    val hardCap = (sizeBytes.coerceAtMost(16L * 1024L * 1024L)).toInt().coerceAtLeast(4096)
    return try {
        val stream: InputStream? = when {
            sourceStreamProvider != null -> runCatching { sourceStreamProvider() }.getOrNull()
            uri != null -> runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            else -> null
        }
        stream?.use { s ->
            val baos = java.io.ByteArrayOutputStream(hardCap)
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < hardCap) {
                val n = s.read(buf)
                if (n < 0) break
                baos.write(buf, 0, n)
                total += n
            }
            if (total > 0) baos.toByteArray() else null
        }
    } catch (_: Throwable) {
        null
    }
}

class Fb2MetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class CbxMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        var pageCount: Int? = null
        val path = uri?.path
        if (path != null) {
            try {
                ZipFile(path).use { zip ->
                    pageCount = zip.entries().toList().count { !it.isDirectory }
                }
            } catch (_: Exception) {
            }
        }
        return fallbackMetadata(filename).copy(pageCount = pageCount)
    }
}

class TextMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class AudioMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        var mmr: android.media.MediaMetadataRetriever? = null
        var embeddedChapters: List<ChapterInfo> = emptyList()
        var streamDurMs: Long? = null

        // 1. Try embedded MP4/M4B chapter extraction (uses binary container atoms, works offline
        //    and doesn't require ExoPlayer initialization or playback licensing).
        val lower = filename.lowercase()
        if (lower.endsWith(".m4b") || lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            try {
                val stream = when {
                    sourceStreamProvider != null -> sourceStreamProvider()
                    uri != null -> ctx.contentResolver.openInputStream(uri)
                    else -> null
                }
                if (stream != null) {
                    stream.use { s ->
                        val ch = parseMp4Chapters(s, sizeBytes)
                        embeddedChapters = ch.first
                        streamDurMs = ch.second
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            mmr = android.media.MediaMetadataRetriever()
            if (uri != null) {
                if (uri.scheme == "file" && uri.path != null) {
                    mmr.setDataSource(uri.path)
                } else {
                    mmr.setDataSource(ctx, uri)
                }
            }

            val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumArtist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: albumArtist
            val trackTitle = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull() ?: streamDurMs

            val fallback = fallbackMetadata(filename)
            return fallback.copy(
                title = trackTitle?.takeIf { it.isNotBlank() }
                    ?: album?.takeIf { it.isNotBlank() }
                    ?: fallback.title,
                author = artist?.takeIf { it.isNotBlank() } ?: fallback.author,
                durationMs = durationMs,
                chapters = embeddedChapters,
                album = album?.takeIf { it.isNotBlank() },
                albumArtist = albumArtist?.takeIf { it.isNotBlank() } ?: artist?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            return fallbackMetadata(filename).copy(durationMs = streamDurMs, chapters = embeddedChapters)
        } finally {
            try { mmr?.release() } catch (_: Exception) {}
        }
    }
}

/**
 * Lightweight binary MP4/M4B/M4A chapter parser.
 * Reads the 'chpl' (chapters) QuickTime atom inside 'moov' → 'udta' → 'meta' → 'ilst' hierarchy.
 * Never accesses MediaDrm or decodes audio - purely structural parsing of ISOBMFF container atoms.
 *
 * Returns Pair(chapters, totalDurationMillis?).
 *   chapters: zero or more entries, each with start/end millisecond offsets and title.
 *   durationMs: either the last chapter's end, or the 'mvhd' movie timescale duration, if available.
 */
private fun parseMp4Chapters(stream: InputStream, size: Long): Pair<List<ChapterInfo>, Long?> {
    // Read first MB (covers moov header for all typical m4b files).
    val headerLimit = (if (size <= 0L) (2L * 1024L * 1024L) else size.coerceAtMost(8L * 1024L * 1024L)).toInt()
    val buffer = ByteArray(headerLimit)
    var read = 0
    while (read < headerLimit) {
        val n = stream.read(buffer, read, headerLimit - read)
        if (n < 0) break
        read += n
    }
    val file = buffer.copyOf(read)
    var totalDurMs: Long? = null
    val embeddedChapters = mutableListOf<Pair<Long, String>>()

    fun u32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return -1
        return ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)
    }
    fun asciiTag(b: ByteArray, off: Int): String {
        if (off + 4 > b.size) return ""
        return buildString { append(b[off].toInt().toChar()); append(b[off+1].toInt().toChar()); append(b[off+2].toInt().toChar()); append(b[off+3].toInt().toChar()) }
    }
    fun u16(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    // --- 1. Parse mvhd inside moov for total duration --------------------------
    var pos = 0
    var moovStart = -1
    while (pos + 8 <= file.size && moovStart == -1) {
        val atomSize = u32(file, pos)
        val tag = asciiTag(file, pos + 4)
        if (tag == "moov") { moovStart = pos; break }
        if (atomSize < 8 || atomSize > file.size) break
        pos += atomSize.toInt()
    }
    if (moovStart >= 0) {
        var inside = moovStart + 8
        while (inside + 8 <= file.size && inside < moovStart + u32(file, moovStart)) {
            val aSize = u32(file, inside)
            val aTag = asciiTag(file, inside + 4)
            if (aTag == "mvhd" && aSize > 28) {
                val ver = file[inside + 8].toInt() and 0xFF
                val timeScale: Long
                val duration: Long
                if (ver == 1) {
                    timeScale = u32(file, inside + 28)
                    duration = if (inside + 36 < file.size) {
                        ((file[inside + 28 + 4 + 4].toLong() and 0xFFL) shl 56) or
                        ((file[inside + 28 + 4 + 5].toLong() and 0xFFL) shl 48) or
                        ((file[inside + 28 + 4 + 6].toLong() and 0xFFL) shl 40) or
                        ((file[inside + 28 + 4 + 7].toLong() and 0xFFL) shl 32) or
                        ((file[inside + 28 + 4 + 8].toLong() and 0xFFL) shl 24) or
                        ((file[inside + 28 + 4 + 9].toLong() and 0xFFL) shl 16) or
                        ((file[inside + 28 + 4 + 10].toLong() and 0xFFL) shl 8) or
                        (file[inside + 28 + 4 + 11].toLong() and 0xFFL)
                    } else 0L
                } else {
                    timeScale = u32(file, inside + 12)
                    duration = u32(file, inside + 16)
                }
                if (timeScale > 0 && duration > 0) {
                    totalDurMs = (duration * 1000L) / timeScale
                }
                break
            }
            if (aSize < 8L) break
            inside += aSize.toInt()
        }

        // --- 2. Locate chpl inside moov → udta → meta/ilst ----------------------
        // Recursively walk atoms inside moov for the chpl (QuickTime Chapter List) marker.
        fun walk(parentStart: Int, parentEnd: Int, depth: Int) {
            if (depth > 8) return
            var p = parentStart
            while (p + 8 <= file.size && p < parentEnd) {
                val s = u32(file, p)
                val t = asciiTag(file, p + 4)
                if (s < 8L || s > file.size || p + s > file.size) break
                if (t == "chpl") {
                    // Chapter list atom (QuickTime / nero / ffmpeg chapter atom).
                    // Layout (simplified):
                    //   header (atom_size + tag)  -> 8 bytes
                    //   version + flags           -> 4 bytes (version at +8)
                    //   4 bytes reserved          -> +12
                    //   chapter_count             -> 1 byte at +16 for version 0, or 4 bytes u32 depending on muxer
                    //
                    // Some ffmpeg muxers use: version(1) + flags(3) + entry_count(uint32)
                    // Apple iTunes chapter format:
                    //   byte     version = 0
                    //   3 bytes  flags = 0
                    //   4 bytes  reserved
                    //   1 byte   chapter_count
                    //   then N chapters:
                    //     8 bytes start time (uint64, milliseconds since start)
                    //     1 byte  chapter_title length
                    //     N bytes chapter_title (UTF-8)
                    try {
                        val ver = file[p + 8].toInt() and 0xFF
                        var cur = p + 16
                        var count = 0
                        // Try 4 byte count first (ffmpeg/nero)
                        val as4 = u32(file, p + 12).toInt()
                        count = if (as4 in 1..2000) as4 else (file[p + 16].toInt() and 0xFF).also { cur = p + 17 }
                        if (count > 4000) count = 0
                        var index = 0
                        while (index < count && cur + 8 <= p + s) {
                            val hi = u32(file, cur).toLong()
                            val lo = u32(file, cur + 4).toLong() and 0xFFFFFFFFL
                            val startMs = (hi shl 32) or lo
                            cur += 8
                            if (cur + 1 > file.size) break
                            val tLen = file[cur].toInt() and 0xFF
                            cur += 1
                            val title = if (cur + tLen <= file.size) {
                                String(file, cur, tLen, Charsets.UTF_8)
                            } else "Kapittel ${index + 1}"
                            cur += tLen
                            embeddedChapters.add(Pair(startMs, title))
                            index++
                            if (embeddedChapters.size >= 6000) break
                        }
                    } catch (_: Throwable) {}
                    return
                }
                if (t in setOf("moov", "udta", "meta", "ilst", "\u00A9nam", "----")) {
                    // descendable containers
                    val dataSkip = if (t == "meta") 4 else 0 // meta has 4-byte version/flags before children
                    walk(p + 8 + dataSkip, (p + s.toInt()), depth + 1)
                }
                p += s.toInt()
            }
        }
        walk(moovStart, moovStart + u32(file, moovStart).toInt(), 0)
    }

    // --- 3. Build ChapterInfo with proper [start, end) ranges
    val chapters = embeddedChapters.mapIndexed { idx, (startMs, title) ->
        ChapterInfo(
            index = idx,
            title = title.trim().ifBlank { "Kapittel ${idx + 1}" },
            startMs = startMs,
            endMs = null,
            href = null
        )
    }.toMutableList()
    for (i in 0 until chapters.size - 1) {
        chapters[i] = chapters[i].copy(endMs = chapters[i + 1].startMs)
    }
    if (chapters.isNotEmpty()) {
        val end = totalDurMs?.takeIf { it > chapters.last().startMs }
            ?: (chapters.last().startMs + 60L * 60L * 1000L)
        chapters[chapters.lastIndex] = chapters.last().copy(endMs = end)
    }
    return Pair(chapters, totalDurMs ?: chapters.lastOrNull()?.endMs?.takeIf { it > 0 })
}

fun getParserFor(format: BookFormat): FormatMetadataParser = when (format) {
    BookFormat.EPUB -> EpubMetadataParser()
    BookFormat.PDF -> PdfMetadataParser()
    BookFormat.MOBI -> MobiMetadataParser()
    BookFormat.AZW, BookFormat.AZW3 -> AzwMetadataParser()
    BookFormat.FB2 -> Fb2MetadataParser()
    BookFormat.CBZ, BookFormat.CBR -> CbxMetadataParser()
    BookFormat.TXT, BookFormat.MD, BookFormat.HTML, BookFormat.RTF, BookFormat.DOCX -> TextMetadataParser()
    BookFormat.M4B, BookFormat.M4A, BookFormat.MP3, BookFormat.AAC,
    BookFormat.FLAC, BookFormat.OGG, BookFormat.OPUS, BookFormat.WAV -> AudioMetadataParser()
    BookFormat.ZIP, BookFormat.UNKNOWN -> TextMetadataParser()
}
