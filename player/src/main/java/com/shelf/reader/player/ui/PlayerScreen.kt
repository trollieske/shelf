package com.shelf.reader.player.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.HandoffPrecisionEntity
import com.shelf.reader.data.repository.HandoffRepository
import com.shelf.reader.data.repository.ResolvedHandoff
import com.shelf.reader.designsystem.theme.ShelfColors
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.player.engine.AudiobookState
import com.shelf.reader.player.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    onBack: () -> Unit,
    onNavigateToEbook: ((targetBookId: Long, positionPercent: Float) -> Unit)? = null,
    onFindEbook: (() -> Unit)? = null,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: PlayerViewModel = viewModel(factory = vmFactory ?: defaultPlayerVmFactory())
) {
    val state by vm.state.collectAsState()
    val serviceBound by vm.serviceBound.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val res = ctx.resources
    val prefs = remember { com.shelf.reader.data.prefs.UserPreferencesRepository(ctx.applicationContext as android.app.Application) }
    val handoffRepo = remember { HandoffRepository(ShelfDatabase.getInstance(ctx.applicationContext as android.app.Application)) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    val playerReady = serviceBound && state.mediaUri != null && state.error == null

    val workAndSibling: Pair<com.shelf.reader.data.local.dao.WorkWithEditions?, Long?> by androidx.compose.runtime.produceState<Pair<com.shelf.reader.data.local.dao.WorkWithEditions?, Long?>>(
        initialValue = null to null,
        key1 = bookId,
        producer = {
            val work = handoffRepo.getWorkForBook(bookId)
            val siblingId = work?.editions?.firstOrNull { e -> e.bookId != bookId }?.bookId
            value = work to siblingId
        }
    )
    val handoffPrecisionName by prefs.handoffPrecision.collectAsStateWithLifecycle(initialValue = HandoffPrecisionEntity.SMART.name)
    val handoffPrecision = remember(handoffPrecisionName) {
        runCatching { HandoffPrecisionEntity.valueOf(handoffPrecisionName) }.getOrElse { HandoffPrecisionEntity.SMART }
    }
    val showToast by prefs.handoffToastEnabled.collectAsStateWithLifecycle(initialValue = true)
    var animatingToggle by remember { mutableStateOf(false) }

    DisposableEffect(bookId) {
        // #region debug-point UI:player-screen-enter
        dbgPlayerUi("E", "player-screen-enter", "bookId=$bookId")
        // #endregion
        onDispose {
            // #region debug-point UI:player-screen-exit
            dbgPlayerUi("E", "player-screen-exit", "bookId=$bookId")
            // #endregion
        }
    }

    LaunchedEffect(bookId) {
        // #region debug-point UI:player-load-request
        dbgPlayerUi("E", "player-load-request", "bookId=$bookId")
        // #endregion
        vm.load(bookId)
    }

    LaunchedEffect(state.error, state.mediaUri, serviceBound) {
        val info = "bookId=$bookId mediaUri=${state.mediaUri ?: ""} serviceBound=$serviceBound error=${state.error ?: ""}"
        if (state.error != null) {
            // #region debug-point UI:player-error
            dbgPlayerUi("E", "player-screen-error", info)
            // #endregion
        } else if (state.mediaUri != null && serviceBound) {
            // #region debug-point UI:player-ready
            dbgPlayerUi("E", "player-screen-ready", info)
            // #endregion
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lydbok", style = ShelfTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    if (animatingToggle) {
                        Spacer(Modifier.width(48.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        val siblingId = workAndSibling.second
                        if (siblingId != null) {
                            AssistChip(
                                onClick = {
                                    if (animatingToggle) return@AssistChip
                                    animatingToggle = true
                                    scope.launch(Dispatchers.Default) {
                                        val totalDur = state.durationMs.coerceAtLeast(0L)
                                        val curDur = state.currentMs.coerceAtLeast(0L)
                                        val frac = if (totalDur > 0L) curDur.toFloat() / totalDur.toFloat() else 0f
                                        val chapTitle = state.chapters.getOrNull(state.currentChapterIndex.coerceAtLeast(0))?.title
                                        val chapterDurations = state.chapters.map { c ->
                                            val start = c.startMs.coerceAtLeast(0L)
                                            val end = (c.endMs ?: (start + 1L)).coerceAtLeast(start)
                                            (end - start).coerceAtLeast(0L)
                                        }
                                        val resolved: ResolvedHandoff = handoffRepo.resolveHandoffPosition(
                                            fromBookId = bookId,
                                            toBookId = siblingId,
                                            ctx = HandoffRepository.HandoffContext(
                                                fromProgressFraction = frac.coerceIn(0f, 1f),
                                                fromChapterIndex = state.currentChapterIndex.takeIf { it >= 0 },
                                                fromChapterLabel = chapTitle,
                                                fromPositionMs = curDur,
                                                fromTotalDurationMs = totalDur,
                                                toAudioChapterTitles = state.chapters.mapNotNull { it.title },
                                                toAudioChapterDurationsMs = chapterDurations,
                                                toAudioTotalDurationMs = totalDur.takeIf { it > 0L },
                                                precision = handoffPrecision
                                            )
                                        )
                                        runCatching {
                                            handoffRepo.recordHandoff(
                                                fromEditionBookId = bookId,
                                                toEditionBookId = siblingId,
                                                precision = handoffPrecision,
                                                fromFraction = frac.coerceIn(0f, 1f),
                                                toFraction = resolved.positionFraction,
                                                fromChapter = chapTitle,
                                                toChapter = resolved.chapterLabel,
                                                method = resolved.mappingMethod,
                                                estimate = resolved.wasEstimate
                                            )
                                        }
                                        withContext(Dispatchers.Main.immediate) {
                                            val estimateLabel = if (resolved.wasEstimate && resolved.chapterLabel != null) {
                                                "Kapittelestimat: ${resolved.chapterLabel}"
                                            } else {
                                                "Fortsetter omtrent samme sted"
                                            }
                                            if (showToast) {
                                                android.widget.Toast.makeText(ctx, estimateLabel, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            runCatching { if (state.isPlaying) vm.playPause() }
                                            onNavigateToEbook?.invoke(siblingId, resolved.positionFraction)
                                            if (onNavigateToEbook == null) onBack()
                                            animatingToggle = false
                                        }
                                    }
                                },
                                modifier = Modifier.padding(end = 4.dp),
                                label = { Text("📖 Les", fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.Default.AutoStories, null, Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                            )
                        }
                    }
                    IconButton(onClick = { showSleep = true }) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = "Søvntimer",
                            tint = if (state.sleepTimerMinutes != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Kapitler")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(260.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = ShelfColors.SpineBurgundy)
            ) {
                Box(Modifier.fillMaxSize()) {
                    val cp = state.coverPath
                    if (cp != null) {
                        val req = remember(cp) {
                            ImageRequest.Builder(ctx)
                                .data(java.io.File(cp))
                                .memoryCacheKey(cp)
                                .diskCacheKey(cp)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = req,
                            contentDescription = state.title.ifBlank { "Bok-cover" },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.Center
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.18f),
                                            Color.Black.copy(alpha = 0.62f)
                                        ),
                                        startY = 0f
                                    )
                                )
                        )
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF2B2446),
                                        ShelfColors.SpineBurgundy,
                                        Color(0xFF3E1A2B)
                                    )
                                )
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.38f)
                                    ),
                                    center = Offset(w * 0.55f, h * 0.42f),
                                    radius = h * 0.75f
                                )
                            )
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                state.title.ifBlank { "Lydbok" },
                                style = ShelfTypography.TitleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                state.author.ifBlank { "" },
                                style = ShelfTypography.BodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (state.isPlaying) WaveformOverlay()
                }
            }

            Spacer(Modifier.height(24.dp))

            if (!playerReady) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (state.error != null) "Lydboken kunne ikke klargjøres" else "Klargjører avspilling…",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.error ?: "Venter på mediekilde…",
                            style = ShelfTypography.BodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            val chapterCount = state.chapters.size
            val currentChapterIdx = state.currentChapterIndex
            val chapterLabel = if (chapterCount > 0) "KAPITTEL ${currentChapterIdx + 1} AV $chapterCount" else "LYDSPILLER"

            val chapterObj = state.chapters.getOrNull(currentChapterIdx)
            val displayTitle = when {
                chapterObj != null && chapterObj.title.isNotBlank() && !chapterObj.title.equals(state.title, ignoreCase = true) -> chapterObj.title
                else -> state.title.ifBlank { "Lydbok" }
            }

            Text(
                text = chapterLabel,
                style = ShelfTypography.LabelMedium.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = displayTitle,
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.author,
                    style = ShelfTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))

            Column(Modifier.fillMaxWidth()) {
                val currentSec = state.currentMs / 1_000L
                val durSec = state.durationMs / 1_000L
                val pctF = if (state.durationMs > 0) state.currentMs.toFloat() / state.durationMs.toFloat() else 0f
                var slider by remember(state.currentMs, state.durationMs) { mutableFloatStateOf(pctF) }

                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = {
                        val ms = (slider * state.durationMs.coerceAtLeast(1L)).toLong()
                        scope.launch { vm.seekTo(ms) }
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDuration(currentSec),
                        style = ShelfTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "- ${formatDuration(max(0L, durSec - currentSec))}",
                        style = ShelfTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { vm.skipBack(10_000L) } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Replay10, contentDescription = "10s tilbake", Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = { scope.launch { vm.prevChapter() } }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Forrige kapittel", Modifier.size(34.dp))
                }
                FilledTonalIconButton(
                    onClick = { scope.launch { vm.playPause() } },
                    enabled = playerReady,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Spill",
                        Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { scope.launch { vm.nextChapter() } }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Neste kapittel", Modifier.size(34.dp))
                }
                IconButton(onClick = { scope.launch { vm.skipForward(30_000L) } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forward30, contentDescription = "30s frem", Modifier.size(28.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AssistChip(
                    onClick = {
                        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        val i = speeds.indexOf(state.playbackSpeed)
                        val next = if (i < 0) 1f else speeds[(i + 1) % speeds.size]
                        scope.launch { vm.setSpeed(next) }
                    },
                    leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(18.dp)) },
                    label = { Text("${String.format("%.2f", state.playbackSpeed)}×") }
                )
                AssistChip(
                    onClick = { showSleep = true },
                    leadingIcon = { Icon(Icons.Default.Bedtime, null, Modifier.size(18.dp)) },
                    label = {
                        val s = state.sleepTimerMinutes
                        Text(if (s == null) "Søvntimer: Av" else "Søvntimer: ${s}m")
                    }
                )
                AssistChip(
                    onClick = { showChapters = true },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp)) },
                    label = { Text("Kapitler (${state.chapters.size})") }
                )
                AssistChip(
                    onClick = { },
                    leadingIcon = { Icon(Icons.Default.DirectionsCar, null, Modifier.size(18.dp)) },
                    label = { Text("Bilmodus") }
                )
            }
        }
    }

    if (showChapters) {
        val chs = state.chapters
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("Kapitler", style = ShelfTypography.TitleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (chs.isEmpty()) {
                    Text("Ingen kapitler tilgjengelig ennå.",
                        style = ShelfTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val scroll = rememberScrollState()
                    Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chs.forEachIndexed { i, ch ->
                            val selected = i == state.currentChapterIndex
                            ElevatedCard(
                                onClick = { scope.launch { vm.seekTo(ch.startMs) }; showChapters = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${i + 1}",
                                        style = ShelfTypography.LabelLarge,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(40.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ch.title.ifBlank { "Kapittel ${i + 1}" },
                                            maxLines = 1,
                                            style = ShelfTypography.BodyMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Text(
                                            formatDuration(ch.startMs / 1000L),
                                            style = ShelfTypography.LabelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showSleep) {
        SleepTimerSheet(
            current = state.sleepTimerMinutes,
            remainingMs = state.sleepTimerRemainingMs,
            onDismiss = { showSleep = false },
            onPick = { mins -> scope.launch { vm.setSleepTimer(mins) }; showSleep = false }
        )
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    current: Int?,
    remainingMs: Long,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit
) {
    var customText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Søvntimer", style = ShelfTypography.TitleLarge, fontWeight = FontWeight.Bold)

            if (remainingMs > 0L) {
                val sec = remainingMs / 1000L
                val m = sec / 60L
                val s = sec % 60L
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "⏱️ Aktiv nedtelling: ${m} min ${s} sek",
                            style = ShelfTypography.BodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        TextButton(onClick = { onPick(null) }) {
                            Text("Slå av", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Custom Time Input Field
            Text("Egentilpasset tid (minutter)", style = ShelfTypography.LabelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() }.take(3) },
                    placeholder = { Text("Eks. 25") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val mins = customText.toIntOrNull()
                        if (mins != null && mins > 0) {
                            onPick(mins)
                        }
                    },
                    enabled = customText.toIntOrNull()?.let { it > 0 } == true
                ) {
                    Text("Sett timer")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Hurtigvalg", style = ShelfTypography.LabelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val options = listOf(null, 5, 10, 15, 30, 45, 60, 90)
            options.forEach { mins ->
                val label = if (mins == null) "Slå av søvntimer" else "$mins minutter"
                val selected = current == mins
                OutlinedButton(
                    onClick = { onPick(mins) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text(label, style = ShelfTypography.BodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun dbgPlayerUi(hypothesisId: String, msg: String, data: String) {
    Thread {
        try {
            val safeMsg = msg.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val safeData = data.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val body = """{"sessionId":"ebook-audio-crash","runId":"pre-fix","hypothesisId":"$hypothesisId","location":"PlayerScreen","msg":"[DEBUG] $safeMsg","data":{"info":"$safeData"},"ts":${System.currentTimeMillis()}}"""
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

@Composable
private fun WaveformOverlay() {
    val inf = rememberInfiniteTransition(label = "wave")
    val t by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "wt"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val baseY = h * 0.78f
        val bars = 48
        val barW = w / bars
        for (i in 0 until bars) {
            val phase = (i / bars.toFloat()) * 6.28f
            val barH = (sin(phase + t * 6.28f) * 0.5f + 0.55f) * (h * 0.18f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.45f),
                topLeft = Offset(i * barW + barW * 0.22f, baseY - barH),
                size = androidx.compose.ui.geometry.Size(barW * 0.56f, barH + 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f)
            )
        }
    }
}

@Composable
fun defaultPlayerVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            val db = ShelfDatabase.getInstance(app)
            PlayerViewModel(
                application = app,
                db = db,
                dispatchers = DefaultDispatcherProvider
            )
        }
    }
}
