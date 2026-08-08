package com.shelf.reader.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shelf.reader.data.prefs.UserPreferencesRepository
import androidx.work.WorkManager
import com.shelf.reader.app.workers.ImportWorker
import com.shelf.reader.data.local.entity.ImportSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.shelf.reader.designsystem.components.BookCoverCard
import com.shelf.reader.designsystem.components.BookFormat
import com.shelf.reader.designsystem.components.BookVisual
import com.shelf.reader.designsystem.theme.ShelfColors
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.library.ui.SampleBooks
import java.net.HttpURLConnection
import java.net.URL

private fun spineColorFor(seed: String): Color {
    val palette = listOf(
        ShelfColors.SpineBurgundy,
        ShelfColors.SpineNavy,
        ShelfColors.SpineForest,
        ShelfColors.SpineSienna,
        ShelfColors.SpineSlate,
        ShelfColors.SpineDustyRose,
        ShelfColors.SpineMustard,
        ShelfColors.SpineTeal,
        ShelfColors.SpinePlum,
        ShelfColors.SpineRust
    )
    return palette[seed.hashCode().mod(palette.size)]
}

private fun formatEntityToUiFormat(entity: com.shelf.reader.data.local.entity.FormatEntity): BookFormat =
    try {
        BookFormat.valueOf(entity.name)
    } catch (_: Exception) {
        BookFormat.UNKNOWN
    }

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) "%d t %d min".format(hours, minutes)
    else if (minutes > 0) "%d min %d sek".format(minutes, seconds)
    else "$seconds sek"
}

private fun formatRemaining(ms: Long, pct: Float): String {
    val remainingMs = ((1f - pct.coerceIn(0f, 1f)) * ms).toLong()
    return formatDuration(remainingMs)
}

private fun parseChapters(json: String?): List<String> {
    if (json == null || json.isBlank()) return emptyList()
    return try {
        val result = mutableListOf<String>()
        val array = org.json.JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            if (item is org.json.JSONObject) {
                val t = item.optString("title").takeIf { it.isNotBlank() }
                    ?: item.optString("name").takeIf { it.isNotBlank() }
                    ?: "Kapittel ${i + 1}"
                result.add(t)
            } else if (item != null) {
                val s = item.toString().trim()
                if (s.isNotBlank()) result.add(s)
            }
        }
        result
    } catch (_: Exception) {
        json.split("\",\"")
            .map { it.replace("\"", "").replace("[", "").replace("]", "").trim() }
            .filter { it.isNotBlank() }
    }
}

private fun dbgUi(location: String, hypothesisId: String, msg: String, data: String) {
    Thread {
        try {
            val safeMsg = msg.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val safeData = data.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val body = """{"sessionId":"ebook-audio-crash","runId":"pre-fix","hypothesisId":"$hypothesisId","location":"$location","msg":"[DEBUG] $safeMsg","data":{"info":"$safeData"},"ts":${System.currentTimeMillis()}}"""
            val conn = (URL("http://192.168.1.10:7777/event").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            runCatching { conn.inputStream.close() }
            conn.disconnect()
        } catch (_: Throwable) {
        }
    }.start()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailsScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenPlayer: (Long) -> Unit,
    onOpenBookmark: (Long, Float) -> Unit = { _, _ -> },
    onDeleted: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember { com.shelf.reader.data.local.ShelfDatabase.getInstance(ctx) }
    val bookData by produceState<Pair<com.shelf.reader.data.local.entity.BookEntity?, com.shelf.reader.data.local.entity.ReadingProgressEntity?>>(
        initialValue = null to null,
        key1 = bookId
    ) {
        value = (db.bookDao().getById(bookId) to db.progressDao().getByBook(bookId))
    }

    val bookmarks by produceState<List<com.shelf.reader.data.local.entity.BookmarkEntity>>(
        initialValue = emptyList(),
        key1 = bookId
    ) {
        db.bookmarkDao().observeByBook(bookId).collect { value = it }
    }
    val highlights by produceState<List<com.shelf.reader.data.local.entity.HighlightEntity>>(
        initialValue = emptyList(),
        key1 = bookId
    ) {
        db.highlightDao().observeByBook(bookId).collect { value = it }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    var chaptersExpanded by remember { mutableStateOf(true) }
    var bookmarksExpanded by remember { mutableStateOf(true) }
    var highlightsExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val book = bookData.first
    val progress = bookData.second
    val metaCleaned = remember(book?.title, book?.author, book?.fileUri, book?.filePath) {
        com.shelf.reader.core.parse.MetadataCleaner.clean(
            rawTitle = book?.title,
            rawAuthor = book?.author,
            filename = book?.filePath ?: book?.fileUri
        )
    }
    val pct = progress?.progressPercent ?: 0f
    val isAudio = book?.type == com.shelf.reader.data.local.entity.BookTypeEntity.AUDIOBOOK ||
            book?.type == com.shelf.reader.data.local.entity.BookTypeEntity.MIXED
    val chapters = remember(book?.chaptersJson) { parseChapters(book?.chaptersJson) }

    LaunchedEffect(book?.id, book?.fileUri, book?.filePath, book?.persistableUriPermission) {
        val b = book ?: return@LaunchedEffect
        // #region debug-point UI:book-details-loaded
        dbgUi(
            location = "BookDetailsScreen",
            hypothesisId = "B",
            msg = "book-details-loaded",
            data = "bookId=${b.id} type=${b.type} format=${b.format} fileUri=${b.fileUri ?: ""} filePath=${b.filePath ?: ""} persistable=${b.persistableUriPermission}"
        )
        // #endregion
    }

    fun buildBookVisual(): BookVisual {
        val b = book ?: return BookVisual(
            id = bookId,
            title = "Laster…",
            author = "",
            spineColor = ShelfColors.SpineSlate,
            format = BookFormat.EPUB,
            progress = pct
        )
        val spineColor = b.spineColor?.let { Color(it) }
            ?: spineColorFor("${b.title}|${b.author}")
        return BookVisual(
            id = b.id,
            title = b.title,
            author = b.author,
            spineColor = spineColor,
            coverImagePath = b.coverPath,
            format = formatEntityToUiFormat(b.format),
            progress = pct
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { snackbarHostState.showSnackbar("Flytt til hylle") }
                    }) { Icon(Icons.AutoMirrored.Filled.Label, null) }
                    IconButton(onClick = {
                        val b = book ?: return@IconButton
                        val title = b.title
                        val author = b.author
                        val filePath = b.filePath
                        val file = filePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.canRead() }
                        if (file != null) {
                            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                file
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(android.content.Intent.EXTRA_TITLE, title)
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "\"$title\" – $author\n(hentet via Shelf-appen)"
                                )
                                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(intent, null))
                        } else {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TITLE, title)
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "\"$title\" – $author\n(hentet via Shelf-appen)"
                                )
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(intent, null))
                        }
                    }) { Icon(Icons.Default.Share, null) }
                    IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                BookCoverCard(
                    book = buildBookVisual(),
                    onClick = { },
                    onLongClick = { },
                    modifier = Modifier
                        .width(140.dp)
                        .aspectRatio(1f / 1.55f)
                )
                Spacer(Modifier.width(18.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp)
                ) {
                    Text(
                        book?.let { metaCleaned.title } ?: "Laster…",
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        metaCleaned.author,
                        style = ShelfTypography.TitleMedium,
                        color = Color(0xFFD4AF37),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    book?.type?.let { type ->
                        if (type == com.shelf.reader.data.local.entity.BookTypeEntity.AUDIOBOOK ||
                            type == com.shelf.reader.data.local.entity.BookTypeEntity.MIXED
                        ) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Forteller: —",
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    AssistChip(
                        onClick = { },
                        label = { Text(book?.format?.name ?: "—") },
                        leadingIcon = {
                            Icon(
                                if (isAudio) Icons.Default.Audiotrack else Icons.Default.Book,
                                null, Modifier.size(16.dp)
                            )
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    if (pct > 0f) {
                        Text(
                            "${(pct * 100).toInt()}% lest",
                            style = ShelfTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = ShelfColors.ProgressFill
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (book?.type != com.shelf.reader.data.local.entity.BookTypeEntity.AUDIOBOOK) {
                    FilledTonalButton(
                        onClick = {
                            // #region debug-point UI:reader-tap
                            dbgUi(
                                location = "BookDetailsScreen",
                                hypothesisId = "B",
                                msg = "tap-open-reader",
                                data = "bookId=$bookId type=${book?.type} format=${book?.format} fileUri=${book?.fileUri ?: ""} filePath=${book?.filePath ?: ""} persistable=${book?.persistableUriPermission}"
                            )
                            // #endregion
                            onOpenReader(bookId)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pct > 0f) "Fortsett å lese" else "Les nå",
                            style = ShelfTypography.LabelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (isAudio) {
                    FilledTonalButton(
                        onClick = {
                            // #region debug-point UI:player-tap
                            dbgUi(
                                location = "BookDetailsScreen",
                                hypothesisId = "E",
                                msg = "tap-open-player",
                                data = "bookId=$bookId type=${book?.type} format=${book?.format} fileUri=${book?.fileUri ?: ""} filePath=${book?.filePath ?: ""}"
                            )
                            // #endregion
                            onOpenPlayer(bookId)
                        },
                        modifier = if (book?.type == com.shelf.reader.data.local.entity.BookTypeEntity.AUDIOBOOK)
                            Modifier.weight(1f) else Modifier,
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pct > 0f) "Fortsett" else "Hør nå",
                            style = ShelfTypography.LabelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(16.dp)) {
                    val infoRows = mutableListOf<Pair<String, String>>()
                    book?.format?.let { infoRows.add("Format" to it.name) }
                    book?.fileSizeBytes?.takeIf { it > 0 }?.let { infoRows.add("Filstørrelse" to formatBytes(it)) }
                    book?.importSource?.let { infoRows.add("Importkilde" to it.name) }
                    book?.pageCount?.let { infoRows.add("Sider" to "$it sider") }
                    book?.durationMs?.let { infoRows.add("Varighet" to formatDuration(it)) }
                    if (pct > 0f) {
                        book?.durationMs?.let {
                            infoRows.add("Gjenstående" to formatRemaining(it, pct))
                        } ?: book?.pageCount?.let { pc ->
                            val pagesLeft = ((1f - pct) * pc).toInt().coerceAtLeast(0)
                            infoRows.add("Gjenstående" to "$pagesLeft sider")
                        }
                    }
                    infoRows.forEachIndexed { i, (k, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                k,
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(v, style = ShelfTypography.BodyMedium)
                        }
                        if (i < infoRows.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            book?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Om boken", style = ShelfTypography.TitleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            desc,
                            style = ShelfTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (desc.length > 180) {
                            TextButton(
                                onClick = { isDescriptionExpanded = !isDescriptionExpanded },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    if (isDescriptionExpanded) "Vis mindre" else "Les mer",
                                    color = Color(0xFFD4AF37),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ElevatedCard(
                    onClick = { chaptersExpanded = !chaptersExpanded },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Kapitler",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (chapters.isEmpty()) "0" else "${chapters.size}",
                            style = ShelfTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (chaptersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null
                        )
                    }
                    if (chaptersExpanded) {
                        if (chapters.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    "Ingen kapittelinformasjon",
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(chapters.size) { idx ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${idx + 1}.",
                                                style = ShelfTypography.LabelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(32.dp)
                                            )
                                            Text(
                                                chapters[idx],
                                                style = ShelfTypography.BodyMedium,
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (idx < chapters.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ElevatedCard(
                    onClick = { bookmarksExpanded = !bookmarksExpanded },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bookmark, null, tint = Color(0xFFB8860B))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Bokmerker",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${bookmarks.size}",
                            style = ShelfTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (bookmarksExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null
                        )
                    }
                    if (bookmarksExpanded) {
                        if (bookmarks.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    "Ingen bokmerker ennå. Trykk på bokmerke-ikonet i leseren for å lagre en side.",
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(bookmarks.size) { idx ->
                                        val bm = bookmarks[idx]
                                        val pct = bm.positionPercent ?: 0f
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val p = bm.positionPercent ?: 0f
                                                    onOpenBookmark(bookId, p)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${(pct * 100).toInt()}%",
                                                style = ShelfTypography.LabelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.width(52.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    bm.title ?: "Bokmerke ${idx + 1}",
                                                    style = ShelfTypography.BodyMedium,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                bm.snippet?.takeIf { it.isNotBlank() }?.let {
                                                    Text(
                                                        it,
                                                        style = ShelfTypography.BodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            IconButton(onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    runCatching { db.bookmarkDao().deleteById(bm.id) }
                                                    snackbarHostState.showSnackbar("Bokmerke slettet")
                                                }
                                            }) {
                                                Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                            }
                                        }
                                        if (idx < bookmarks.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ElevatedCard(
                    onClick = { highlightsExpanded = !highlightsExpanded },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BorderColor, null, tint = Color(0xFF2D5C3A))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Uthevelser",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${highlights.size}",
                            style = ShelfTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (highlightsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null
                        )
                    }
                    if (highlightsExpanded) {
                        if (highlights.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    "Ingen uthevelser ennå. Marker tekst i leseren for å lage en uthevelse.",
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(highlights.size) { idx ->
                                        val hl = highlights[idx]
                                        val pct = hl.positionPercent ?: 0f
                                        val hlColor = hl.color?.let { Color(it) }
                                            ?: Color(0xFFFFF3B0)
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(12.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(hlColor)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "${(pct * 100).toInt()}%",
                                                    style = ShelfTypography.LabelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.weight(1f))
                                                IconButton(onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        runCatching { db.highlightDao().deleteById(hl.id) }
                                                        snackbarHostState.showSnackbar("Uthevelse slettet")
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                hl.text,
                                                style = ShelfTypography.BodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        hlColor.copy(alpha = 0.18f),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(8.dp)
                                            )
                                            hl.note?.takeIf { it.isNotBlank() }?.let { note ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    note,
                                                    style = ShelfTypography.BodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (idx < highlights.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Handlinger", style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { scope.launch { snackbarHostState.showSnackbar("Legg til hylle") } },
                        label = { Text("Legg til hylle") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { scope.launch { snackbarHostState.showSnackbar("Markert som ferdig") } },
                        label = { Text("Marker som ferdig") },
                        leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { scope.launch { snackbarHostState.showSnackbar("Endre cover") } },
                        label = { Text("Endre cover") },
                        leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { showDeleteDialog = true },
                        label = { Text("Slett bok") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp)) }
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDeleteDialog && book != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        val db = com.shelf.reader.data.local.ShelfDatabase.getInstance(ctx)
                        db.progressDao().deleteByBook(bookId)
                        db.bookDao().delete(book)
                        onDeleted()
                    }
                }) { Text("Slett") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Avbryt") }
            },
            title = { Text("Slett bok") },
            text = {
                Text("Er du sikker på at du vil slette \"${book.title}\"? Lese-fortegnelse vil også bli fjernet.")
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onFtpClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    val pickMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        // #region debug-point UI:file-picker-result
        dbgUi(
            location = "ImportScreen",
            hypothesisId = "A",
            msg = "file-picker-result",
            data = "uriCount=${uris.size} first=${uris.firstOrNull()?.toString() ?: ""}"
        )
        // #endregion
        if (uris.isNotEmpty()) {
            ImportWorker.enqueueUris(
                WorkManager.getInstance(ctx),
                uris.map { it.toString() },
                ImportSourceEntity.FILE_PICKER
            )
            snackbarScope.launch {
                snackbarHostState.showSnackbar("${uris.size} bok(er) sendt til import.")
            }
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { tree: Uri? ->
        // #region debug-point UI:folder-picker-result
        dbgUi(
            location = "ImportScreen",
            hypothesisId = "A",
            msg = "folder-picker-result",
            data = "treeUri=${tree?.toString() ?: ""} persistedBefore=${ctx.contentResolver.persistedUriPermissions.size}"
        )
        // #endregion
        if (tree != null) {
            ctx.contentResolver.takePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ImportWorker.enqueueFolder(WorkManager.getInstance(ctx), tree.toString())
            snackbarScope.launch {
                snackbarHostState.showSnackbar("Importerer innhold fra valgt mappe…")
            }
        }
    }

    fun launchSamples() {
        ImportWorker.enqueueSamples(WorkManager.getInstance(ctx))
        snackbarScope.launch {
            snackbarHostState.showSnackbar("Laster inn prøvebøker i bakgrunnen…")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Importer bøker", style = ShelfTypography.HeadlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportCard(
                title = "Velg filer",
                subtitle = "En eller flere ebøker eller lydbøker fra enheten.",
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                color = Color(0xFF2D5C3A),
                onClick = {
                    // #region debug-point UI:file-picker-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "file-picker-launch",
                        data = "mimeCount=${allImportMimeTypes().size}"
                    )
                    // #endregion
                    pickMultipleLauncher.launch(allImportMimeTypes())
                }
            )
            ImportCard(
                title = "Importer mappe",
                subtitle = "Importer alle bøker i en mappe. Kan overvåkes for nye filer.",
                icon = Icons.Default.Folder,
                color = Color(0xFF8B6F47),
                onClick = {
                    // #region debug-point UI:folder-picker-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "folder-picker-launch",
                        data = "persistedBefore=${ctx.contentResolver.persistedUriPermissions.size}"
                    )
                    // #endregion
                    openFolderLauncher.launch(null)
                }
            )
            ImportCard(
                title = "Last inn prøvebøker",
                subtitle = "Offentlige klassikere som medfølger appen.",
                icon = Icons.Default.AutoAwesome,
                color = Color(0xFF6B2D3A),
                onClick = {
                    // #region debug-point UI:samples-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "sample-import-launch",
                        data = "source=samples"
                    )
                    // #endregion
                    launchSamples()
                }
            )
            ImportCard(
                title = "Fra FTP-server",
                subtitle = "Hent bøker fra NAS, hjemmeserver eller nettlagring.",
                icon = Icons.Default.CloudDownload,
                color = Color(0xFF2D3A5C),
                onClick = {
                    // #region debug-point UI:ftp-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "ftp-import-launch",
                        data = "source=ftp"
                    )
                    // #endregion
                    onFtpClick()
                }
            )

            Spacer(Modifier.height(16.dp))

            ElevatedCard(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Støttede formater",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "EPUB, PDF, MOBI, AZW/AZW3, FB2, CBZ/CBR, TXT, HTML, RTF, DOCX, Markdown, M4B, M4A, MP3, AAC, FLAC, OGG, OPUS, WAV.",
                        style = ShelfTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun allImportMimeTypes(): Array<String> = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "application/x-mobipocket-ebook",
    "application/x-fictionbook+xml",
    "application/vnd.comicbook+zip",
    "application/vnd.comicbook-rar",
    "application/zip",
    "text/plain",
    "text/markdown",
    "text/html",
    "text/rtf",
    "application/rtf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "audio/m4b",
    "audio/x-m4b",
    "audio/mp4",
    "audio/mpeg",
    "audio/x-mp3",
    "audio/aac",
    "audio/x-aac",
    "audio/flac",
    "audio/x-flac",
    "audio/ogg",
    "audio/x-ogg",
    "audio/opus",
    "audio/x-opus",
    "audio/wav",
    "audio/x-wav",
    "application/vnd.amazon.mobi8-ebook",
    "application/vnd.amazon.ebook",
    "*/*"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0.15f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var userNameInput by remember { mutableStateOf("") }
    val pages = listOf("Velkommen", "Navn", "Kom i gang")

    Surface(
        color = Color(0xFF1E130D),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(28.dp)
        ) {
            // Page Progress Indicators
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pages.forEachIndexed { i, _ ->
                    Spacer(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (i <= step) Color(0xFFD4AF37)
                                else Color(0x33FFFFFF)
                            )
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            when (step) {
                0 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Spacer(Modifier.height(20.dp))
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = Color(0x22FFFFFF),
                            modifier = Modifier.size(110.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AutoStories,
                                    null,
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(54.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                        Text(
                            "Velkommen til Shelf",
                            style = ShelfTypography.DisplaySmall,
                            color = Color(0xFFFFF8F2),
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Din personlige, elegante 3D-bokhylle for e-bøker og lydbøker. Alt du eier, på ett sted.",
                            style = ShelfTypography.BodyLarge,
                            color = Color(0xFFC0B2A6),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                1 -> {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Hva heter du?",
                            style = ShelfTypography.HeadlineLarge,
                            color = Color(0xFFFFF8F2),
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Skriv inn navnet ditt slik at vi kan gjøre biblioteket og hilsenen din personlig.",
                            style = ShelfTypography.BodyMedium,
                            color = Color(0xFFC0B2A6)
                        )
                        Spacer(Modifier.height(32.dp))

                        OutlinedTextField(
                            value = userNameInput,
                            onValueChange = { userNameInput = it },
                            label = { Text("Ditt navn", color = Color(0xFFC0B2A6)) },
                            placeholder = { Text("f.eks. Karoline", color = Color(0x66FFF8F2)) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFD4AF37),
                                unfocusedBorderColor = Color(0x44FFFFFF),
                                cursorColor = Color(0xFFD4AF37)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                2 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Spacer(Modifier.height(20.dp))
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(0x22FFFFFF),
                            modifier = Modifier.size(90.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    null,
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            if (userNameInput.isNotBlank()) "Klar, ${userNameInput.trim()}!" else "Biblioteket er klart!",
                            style = ShelfTypography.HeadlineLarge,
                            color = Color(0xFFFFF8F2),
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Du kan nå legge til dine egne e-bøker og lydbøker fra enheten eller koble til FTP-serveren din.",
                            style = ShelfTypography.BodyLarge,
                            color = Color(0xFFC0B2A6),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            // Bottom Navigation Buttons
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE8D7C8)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Tilbake")
                    }
                    Spacer(Modifier.width(16.dp))
                }

                Button(
                    onClick = {
                        if (step < pages.lastIndex) {
                            step++
                        } else {
                            val finalName = userNameInput.trim().ifEmpty { "Karoline" }
                            val prefs = UserPreferencesRepository(ctx)
                            scope.launch {
                                prefs.setUserName(finalName)
                                prefs.setHasSeenOnboarding(true)
                                onDone(finalName)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD4AF37),
                        contentColor = Color(0xFF1F130D)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        if (step < pages.lastIndex) "Neste" else "Åpne biblioteket",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        listOf(ShelfColors.SpineBurgundy, ShelfColors.SpineNavy)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoStories,
                null,
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Shelf", style = ShelfTypography.DisplayMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Din bibliotekhylle — elegant for ebøker, lydbøker og alt imellom.",
            style = ShelfTypography.HeadlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun PermissionStep() {
    Column {
        Text("Gi Shelf tilgang", style = ShelfTypography.HeadlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Vi bruker kun tilgangen til å åpne bokfilene dine og lagre dem sikkert. Alt skjer lokalt på enheten din.",
            style = ShelfTypography.BodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionRow(Icons.Default.FolderOpen, "Velg bibliotekmappe", "Trykk for å velge hvor bøker skal lagres")
            PermissionRow(Icons.Default.PermMedia, "Medie- og filtilgang", "Trykk for å gi tilgang via systemvelger")
        }
    }
}

@Composable
private fun GetStartedStep() {
    Column {
        Text("Kom i gang", style = ShelfTypography.HeadlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Last ned bøker via FTP, importer fra enheten, eller prøv noen klassikere først.",
            style = ShelfTypography.BodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFB8860B))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Prøv prøvebøker", style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Laste inn noen offentlige klassikere umiddelbart.", style = ShelfTypography.BodySmall)
                    }
                }
            }
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, null, tint = Color(0xFF2D3A5C))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Legg til FTP-server", style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Koble til NAS eller hjemmeserver for å synkronisere.", style = ShelfTypography.BodySmall)
                    }
                }
            }
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.UploadFile, null, tint = Color(0xFF2D5C3A))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Importer fra enheten", style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Åpne ebøker eller lydbøker som allerede ligger på telefonen.", style = ShelfTypography.BodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Card(
        onClick = { },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}
