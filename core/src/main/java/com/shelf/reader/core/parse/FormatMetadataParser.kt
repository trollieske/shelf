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
        return fallbackMetadata(filename)
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
        return fallbackMetadata(filename)
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
            val artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull()

            val fallback = fallbackMetadata(filename)
            return fallback.copy(
                title = album?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: fallback.title,
                author = artist?.takeIf { it.isNotBlank() } ?: fallback.author,
                durationMs = durationMs
            )
        } catch (_: Exception) {
            return fallbackMetadata(filename)
        } finally {
            try { mmr?.release() } catch (_: Exception) {}
        }
    }
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
