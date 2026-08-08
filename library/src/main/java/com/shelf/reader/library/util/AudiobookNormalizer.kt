package com.shelf.reader.library.util

import java.util.Locale

object AudiobookNormalizer {

    private val trackPrefixRegex = Regex(
        "(?i)^(\\d{1,3}[.\\-_\\s]+|track\\s*\\d{1,3}[.\\-_\\s]+|chapter\\s*\\d{1,3}[.\\-_\\s]+|part\\s*\\d{1,3}[.\\-_\\s]+|cd\\s*\\d{1,2}[.\\-_\\s]+)"
    )

    private val extensionRegex = Regex("(?i)\\.(mp3|m4b|m4a|aac|flac|ogg|opus|wav)$")

    private val noiseBracketsRegex = Regex("(?i)\\[(320kbps|audiobook|unabridged|abridged|mp3|m4b|[a-z0-9\\s-]+)\\]|\\((unabridged|abridged|audiobook)\\)")

    private val nonAlphanumericRegex = Regex("[^a-z0-9]+")

    private val genericFolderNames = setOf(
        "ftp", "smb", "webdav", "storage", "files", "download", "downloads",
        "audiobook", "audiobooks", "bøker", "lydbøker", "library", "shelf",
        "manual", "temp", "media", "music", "imports", "documents"
    )

    fun extractCanonicalFolderName(rawPathOrFolder: String?): String {
        if (rawPathOrFolder.isNullOrBlank()) return ""
        val cleanPath = rawPathOrFolder.replace('\\', '/').trimEnd('/')
        val segments = cleanPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return ""

        var parent = segments.last()
        if (extensionRegex.containsMatchIn(parent)) {
            parent = segments.getOrNull(segments.size - 2) ?: parent
        }

        val normParent = parent.lowercase(Locale.ROOT).trim()
        if (normParent in genericFolderNames || normParent.matches(Regex("^(cd|disc|part|vol|volume)?\\s*\\d+$"))) {
            val grandParent = if (segments.size >= 3) segments[segments.size - 3] else if (segments.size >= 2) segments[segments.size - 2] else ""
            if (grandParent.isNotBlank() && grandParent.lowercase(Locale.ROOT) !in genericFolderNames) {
                return "$grandParent $parent"
            }
        }

        return parent
    }

    fun normalizeTitle(rawTitle: String): String {
        var clean = rawTitle.trim()
        clean = clean.replace(extensionRegex, "")
        clean = clean.replace(noiseBracketsRegex, "")
        clean = clean.replace(trackPrefixRegex, "")
        return clean.trim().ifBlank { rawTitle.replace(extensionRegex, "").trim() }
    }

    fun normalizeString(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var clean = raw.lowercase(Locale.ROOT)
        clean = clean.replace(extensionRegex, "")
        clean = clean.replace(noiseBracketsRegex, "")
        clean = clean.replace(trackPrefixRegex, "")
        clean = clean.replace(nonAlphanumericRegex, " ").trim()
        return clean.replace(Regex("\\s+"), " ")
    }

    fun computeGroupKey(
        albumOrTitle: String?,
        authorOrArtist: String?,
        rawPathOrFolder: String?
    ): String {
        val canonicalFolder = extractCanonicalFolderName(rawPathOrFolder)
        val normTitle = normalizeString(albumOrTitle)
        val normAuthor = normalizeString(authorOrArtist)
        val normFolder = normalizeString(canonicalFolder)

        val isFolderGeneric = normFolder in genericFolderNames || normFolder.isBlank()

        return when {
            normTitle.isNotBlank() && normAuthor.isNotBlank() -> "${normTitle}_by_${normAuthor}"
            normTitle.isNotBlank() -> normTitle
            !isFolderGeneric -> normFolder
            else -> "audiobook_unknown"
        }
    }
}
