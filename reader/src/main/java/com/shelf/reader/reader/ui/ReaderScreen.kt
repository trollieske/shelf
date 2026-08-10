package com.shelf.reader.reader.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.reader.engine.HtmlPageRenderer
import com.shelf.reader.reader.engine.PageBitmapCache
import com.shelf.reader.reader.engine.ReaderBookState
import com.shelf.reader.reader.pageturn.*
import com.shelf.reader.reader.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    initialPositionPercent: Float? = null,
    onNavigateToOther: ((targetBookId: Long, positionMs: Long?, positionPercent: Float) -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: ReaderViewModel = viewModel(factory = vmFactory ?: defaultReaderVmFactory()),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var showControls by rememberSaveable { mutableStateOf(false) }
    var showQuickMenu by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // Screen brightness state (-1f = system default BRIGHTNESS_OVERRIDE_NONE, 0.01f to 1.0f for manual)
    var brightness by rememberSaveable { mutableFloatStateOf(-1f) }

    // Apply brightness to Window
    LaunchedEffect(Unit) {
        GpuDeviceProfile.logGpuDiagnostics()
    }

    LaunchedEffect(brightness) {
        setWindowBrightness(context, brightness)
    }

    DisposableEffect(Unit) {
        onDispose {
            setWindowBrightness(context, -1f)
        }
    }

    LaunchedEffect(bookId) { vm.load(bookId) }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.error != null -> ErrorView(ui.error!!, onBack)
            ui.chapters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> PageCurlReader(
                ui               = ui,
                onToggleControls  = {
                    showControls = !showControls
                    if (!showControls) showQuickMenu = false
                },
                onPageTurned     = { page -> vm.onPageTurned(page) },
                onTotalPages     = { count -> vm.onPageCountKnown(count) },
                onNextChapter    = { vm.nextChapter() },
                onPreviousChapter = { vm.previousChapter() },
            )
        }

        // ── Top Glass Navigation Bar ───────────────────────────────────────────────
        AnimatedVisibility(
            visible  = showControls,
            enter    = slideInVertically { -it } + fadeIn(),
            exit     = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape    = RoundedCornerShape(20.dp),
                color    = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border   = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            text     = ui.bookTitle.ifEmpty { "Leser" },
                            style    = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ui.author.isNotEmpty()) {
                            Text(
                                text     = ui.author,
                                style    = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { showQuickMenu = !showQuickMenu }) {
                        Icon(
                            imageVector = Icons.Default.TextFormat,
                            contentDescription = "Tekst og Lys",
                            tint = if (showQuickMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // ── Smooth In-Reader Quick Control Deck (Lys & Tekst Meny) ─────────────────
        AnimatedVisibility(
            visible  = showControls && showQuickMenu,
            enter    = slideInVertically { it / 2 } + fadeIn(),
            exit     = slideOutVertically { it / 2 } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(28.dp),
                color    = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 16.dp,
                border   = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Lesemodus & Stil",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        IconButton(onClick = { showQuickMenu = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Lukk", modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 1. Skjermlysstyrke (Brightness) Slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        val sliderVal = if (brightness < 0f) 0.8f else brightness
                        Slider(
                            value         = sliderVal,
                            onValueChange = { brightness = it },
                            valueRange    = 0.1f..1.0f,
                            modifier      = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (brightness < 0f) "Auto" else "${(brightness * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 2. Tekststørrelse Quick Stepper
                    Text("Tekststørrelse", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalIconButton(
                            onClick = { vm.setFontSize(ui.fontSizeSp - 1) },
                            enabled = ui.fontSizeSp > 12,
                        ) {
                            Text("A-", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Slider(
                            value         = ui.fontSizeSp.toFloat(),
                            onValueChange = { vm.setFontSize(it.toInt()) },
                            valueRange    = 12f..32f,
                            steps         = 20,
                            modifier      = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )

                        FilledTonalIconButton(
                            onClick = { vm.setFontSize(ui.fontSizeSp + 1) },
                            enabled = ui.fontSizeSp < 32,
                        ) {
                            Text("A+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }

                        Text(
                            "${ui.fontSizeSp} pt",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.width(44.dp).padding(start = 6.dp),
                            textAlign = TextAlign.End,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // 3. Tema Velger Pills (Sepia, Hvit, Mørk, OLED Svart)
                    Text("Bakgrunn & Papir", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val themes = listOf(
                            "Sepia" to "Sepia",
                            "White" to "Hvit",
                            "Dark"  to "Mørk",
                            "Black" to "Svart",
                        )
                        themes.forEach { (key, label) ->
                            val isSelected = ui.readerTheme.equals(key, ignoreCase = true)
                            val colors     = readerThemeColors(key)
                            val bgCol      = Color(colors.paperColorInt)
                            val txtCol     = Color(android.graphics.Color.parseColor(colors.textColor))

                            Surface(
                                onClick = { vm.setTheme(key.lowercase()) },
                                shape   = RoundedCornerShape(14.dp),
                                color   = bgCol,
                                border  = if (isSelected)
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text     = label,
                                        color    = txtCol,
                                        style    = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom Floating Reading Bar (Page Slider + Quick Font Stepper) ─────────
        AnimatedVisibility(
            visible  = showControls,
            enter    = slideInVertically { it } + fadeIn(),
            exit     = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape    = RoundedCornerShape(24.dp),
                color    = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border   = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    // Slider
                    Slider(
                        value         = ui.percent,
                        onValueChange = { vm.seekToPercent(it) },
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text  = "Kapittel ${ui.currentChapterIndex + 1} av ${ui.chapters.size.coerceAtLeast(1)} (Side ${ui.currentPage + 1}/${ui.totalPages.coerceAtLeast(1)})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Instant A- button on bottom bar
                            IconButton(
                                onClick = { vm.setFontSize(ui.fontSizeSp - 1) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text("A-", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            // Instant A+ button on bottom bar
                            IconButton(
                                onClick = { vm.setFontSize(ui.fontSizeSp + 1) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text("A+", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            // Quick deck button
                            IconButton(
                                onClick = { showQuickMenu = !showQuickMenu },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Meny", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun setWindowBrightness(context: Context, brightness: Float) {
    val activity = context as? Activity ?: return
    val lp = activity.window.attributes
    lp.screenBrightness = if (brightness < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    else brightness.coerceIn(0.05f, 1.0f)
    activity.window.attributes = lp
}

// ────────────────────────────────────────────────────────────────────────────
// PageTurn Canvas Engine Composable
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageCurlReader(
    ui: ReaderBookState,
    onToggleControls: () -> Unit,
    onPageTurned: (Int) -> Unit,
    onTotalPages: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config  = LocalConfiguration.current

    val screenWidthPx  = with(density) { config.screenWidthDp.dp.toPx() }.toInt()
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }.toInt()

    val renderer = remember(screenWidthPx, screenHeightPx) {
        HtmlPageRenderer(context, screenWidthPx, screenHeightPx)
    }
    val cache = remember { PageBitmapCache() }

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    val contentHtml = remember(ui.chapters, ui.currentChapterIndex) {
        ui.chapters.getOrNull(ui.currentChapterIndex)?.htmlContent ?: ""
    }

    var currentPage by remember(ui.currentPage, ui.currentChapterIndex) { mutableIntStateOf(ui.currentPage) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var turnDirection by remember { mutableStateOf(TurnDirection.FORWARD) }
    var isTurning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var isAnimRunning by remember { mutableStateOf(false) }

    // Updated states for pointer input without re-subscribing pointerInput
    val currentTotalPages  by rememberUpdatedState(ui.totalPages)
    val currentChapterIdx  by rememberUpdatedState(ui.currentChapterIndex)
    val totalChaptersCount by rememberUpdatedState(ui.chapters.size)
    val animRunning        by rememberUpdatedState(isAnimRunning)
    val activeCurrentPage  by rememberUpdatedState(currentPage)

    // Bitmap state
    // displayedBitmap = what is visibly shown RIGHT NOW. Never goes null after first load.
    // This is the critical fix: we only swap displayedBitmap to the new page once it's ready.
    var displayedBitmap  by remember { mutableStateOf<Bitmap?>(null) }
    // nextBitmap = rendered page N+1 (for forward curl underlay)
    var nextBitmap       by remember { mutableStateOf<Bitmap?>(null) }
    // prevBitmap = rendered page N-1 (for backward curl underlay, also curl leaf)
    var prevBitmap       by remember { mutableStateOf<Bitmap?>(null) }
    var backsideBmp      by remember { mutableStateOf<Bitmap?>(null) }
    var prevBacksideBmp  by remember { mutableStateOf<Bitmap?>(null) }

    // During a forward page turn we need:
    //   curlLeafBmp  = the page being peeled away  = displayedBitmap (frozen at turn start)
    //   underPageBmp = the page revealed underneath = nextBitmap
    // We snapshot these at the START of a turn so they don't change mid-animation.
    var frozenCurlLeaf   by remember { mutableStateOf<Bitmap?>(null) }
    var frozenUnderPage  by remember { mutableStateOf<Bitmap?>(null) }
    var frozenBackside   by remember { mutableStateOf<Bitmap?>(null) }

    // Re-prepare layout when font size, theme or chapter content changes
    LaunchedEffect(contentHtml, ui.fontSizeSp, ui.readerTheme) {
        cache.clear()
        displayedBitmap = null
        val count = renderer.prepare(contentHtml, ui.fontSizeSp, readerThemeColors(ui.readerTheme))
        onTotalPages(count)
        // Preserve the user's RELATIVE reading position (percent through the chapter)
        // instead of clamping the old page number. Same behavior as iBooks:
        // 40/100 (40%) with new pagination 200 pages → jump to page 80 (still 40%), not page 40.
        val pct = ui.percent.coerceIn(0f, 1f)
        val targetFromPct = if (count > 0) (pct * count.toFloat()).toInt()
            .coerceIn(0, (count - 1).coerceAtLeast(0))
        else 0
        // Prefer the pending reposition percent (always accurate) when available,
        // otherwise fall back to the state percent which may be stale.
        val pendingPct = ui.pendingRepositionPct
        val target = if (pendingPct != null && count > 0) {
            (pendingPct * count.toFloat()).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
        } else {
            targetFromPct
        }
        currentPage = target
    }

    LaunchedEffect(currentPage) {
        onPageTurned(currentPage)
    }

    // Main bitmap fetch: render current, next and previous pages
    LaunchedEffect(currentPage, ui.totalPages, ui.fontSizeSp, ui.readerTheme, ui.currentChapterIndex) {
        if (ui.totalPages <= 0) return@LaunchedEffect

        val paperColor = readerThemeColors(ui.readerTheme).paperColorInt
        val maxIdx = (ui.totalPages - 1).coerceAtLeast(0)
        val safeCurrent = currentPage.coerceIn(0, maxIdx)

        // 1. Render current page and update display immediately
        val cur = cache.get(safeCurrent) ?: run {
            val rendered = renderer.renderPage(safeCurrent)
            cache.put(safeCurrent, rendered)
            rendered
        }
        // Only update displayedBitmap once we actually have a real bitmap
        displayedBitmap = cur

        // 2. Render next page (forward turn underlay)
        val nextIdx = safeCurrent + 1
        if (nextIdx <= maxIdx) {
            val nxt = cache.get(nextIdx) ?: run {
                val rendered = renderer.renderPage(nextIdx)
                cache.put(nextIdx, rendered)
                rendered
            }
            nextBitmap = nxt
            backsideBmp = renderer.createBacksideBitmap(cur, paperColor)
        } else {
            nextBitmap = null
            backsideBmp = null
        }

        // 3. Render previous page (backward turn underlay + curl leaf)
        val prevIdx = safeCurrent - 1
        if (prevIdx >= 0) {
            val prv = cache.get(prevIdx) ?: run {
                val rendered = renderer.renderPage(prevIdx)
                cache.put(prevIdx, rendered)
                rendered
            }
            prevBitmap = prv
            prevBacksideBmp = renderer.createBacksideBitmap(prv, paperColor)
        } else {
            prevBitmap = null
            prevBacksideBmp = null
        }

        // 4. Pre-render N+2 in background for the next forward turn
        val nextNextIdx = safeCurrent + 2
        if (nextNextIdx <= maxIdx && cache.getSync(nextNextIdx) == null) {
            val rendered = renderer.renderPage(nextNextIdx)
            cache.put(nextNextIdx, rendered)
        }
    }

    val themeColors = readerThemeColors(ui.readerTheme)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(themeColors.paperColorInt))
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (animRunning) return@awaitEachGesture

                    val startX = down.position.x
                    val startY = down.position.y
                    val w = size.width.toFloat()

                    var isDragging = false
                    var currentX = startX
                    val dir = if (startX < w * 0.50f) TurnDirection.BACKWARD else TurnDirection.FORWARD

                    val pageCount  = currentTotalPages
                    val chapIdx    = currentChapterIdx
                    val totalChaps = totalChaptersCount
                    val maxPage    = (pageCount - 1).coerceAtLeast(0)

                    val canTurnWithinChapter = if (dir == TurnDirection.FORWARD) activeCurrentPage < maxPage else activeCurrentPage > 0
                    val canTurnChapter = if (dir == TurnDirection.FORWARD) chapIdx < totalChaps - 1 else chapIdx > 0

                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                        if (change.pressed) {
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY
                            val dist = kotlin.math.hypot(dx, dy)

                            val startInCenterZone = startX in (w * 0.35f)..(w * 0.65f)
                            val minDragDist = if (startInCenterZone) 56f else 12f

                            if (!isDragging && dist > minDragDist) {
                                if (!canTurnWithinChapter && !canTurnChapter) {
                                    break
                                }
                                isDragging = true
                                isTurning = true
                                turnDirection = dir
                                // Snapshot bitmaps at the START of the drag so they
                                // can't become null or change mid-animation
                                val paperCol = themeColors.paperColorInt
                                val curBmp = displayedBitmap ?: cache.getSync(activeCurrentPage.coerceIn(0, maxPage))
                                if (curBmp == null) {
                                    isTurning = false
                                    break
                                }
                                if (dir == TurnDirection.FORWARD) {
                                    frozenCurlLeaf  = curBmp
                                    val nextIdx = (activeCurrentPage + 1).coerceAtMost(maxPage)
                                    frozenUnderPage = nextBitmap ?: cache.getSync(nextIdx)
                                    frozenBackside  = backsideBmp ?: renderer.createBacksideBitmap(curBmp, paperCol)
                                } else {
                                    frozenCurlLeaf  = curBmp
                                    val prevIdx = (activeCurrentPage - 1).coerceAtLeast(0)
                                    frozenUnderPage = prevBitmap ?: cache.getSync(prevIdx)
                                    frozenBackside  = prevBacksideBmp ?: renderer.createBacksideBitmap(curBmp, paperCol)
                                }
                            }

                            if (isDragging) {
                                change.consume()
                                currentX = change.position.x
                                val frac = if (dir == TurnDirection.FORWARD) {
                                    ((startX - currentX) / (w * 0.45f)).coerceIn(0f, 1f)
                                } else {
                                    ((currentX - startX) / (w * 0.45f)).coerceIn(0f, 1f)
                                }
                                dragFraction = frac
                            }
                        } else {
                            if (isDragging) {
                                change.consume()
                            }
                            break
                        }
                    }

                    if (isDragging) {
                        val finalFrac = dragFraction
                        val paperColor = themeColors.paperColorInt
                        scope.launch {
                            isAnimRunning = true
                            if (finalFrac > 0.18f) {
                                animateCurlFraction(
                                    onUpdate = { dragFraction = it },
                                    start    = finalFrac,
                                    end      = 1f,
                                )
                                if (dir == TurnDirection.FORWARD) {
                                    if (activeCurrentPage < maxPage) {
                                        val nextIdx = activeCurrentPage + 1
                                        val destBmp = frozenUnderPage ?: cache.getSync(nextIdx) ?: renderer.renderPage(nextIdx)
                                        displayedBitmap = destBmp
                                        currentPage = nextIdx
                                    } else if (chapIdx < totalChaps - 1) {
                                        onNextChapter()
                                    }
                                } else {
                                    if (activeCurrentPage > 0) {
                                        val prevIdx = activeCurrentPage - 1
                                        val destBmp = frozenUnderPage ?: cache.getSync(prevIdx) ?: renderer.renderPage(prevIdx)
                                        displayedBitmap = destBmp
                                        currentPage = prevIdx
                                    } else if (chapIdx > 0) {
                                        onPreviousChapter()
                                    }
                                }
                            } else {
                                animateCurlFraction(
                                    onUpdate = { dragFraction = it },
                                    start    = finalFrac,
                                    end      = 0f,
                                )
                            }
                            isTurning = false
                            dragFraction = 0f
                            isAnimRunning = false
                            frozenCurlLeaf = null
                            frozenUnderPage = null
                            frozenBackside = null
                        }
                    } else {
                        // Handle TAP gesture
                        val tapX = startX
                        val paperColor = themeColors.paperColorInt
                        when {
                            tapX < w * 0.35f -> {
                                if (activeCurrentPage > 0) {
                                    scope.launch {
                                        isAnimRunning = true
                                        turnDirection = TurnDirection.BACKWARD
                                        val targetPage = activeCurrentPage - 1
                                        val leaf = displayedBitmap ?: cache.getSync(activeCurrentPage) ?: renderer.renderPage(activeCurrentPage)
                                        val under = prevBitmap ?: cache.getSync(targetPage) ?: renderer.renderPage(targetPage)
                                        frozenCurlLeaf  = leaf
                                        frozenUnderPage = under
                                        frozenBackside  = renderer.createBacksideBitmap(leaf, paperColor)
                                        isTurning = true
                                        animateCurlFraction(onUpdate = { dragFraction = it }, start = 0f, end = 1f)
                                        displayedBitmap = under
                                        currentPage = targetPage
                                        isTurning = false
                                        dragFraction = 0f
                                        isAnimRunning = false
                                        frozenCurlLeaf = null
                                        frozenUnderPage = null
                                        frozenBackside = null
                                    }
                                } else if (chapIdx > 0) {
                                    onPreviousChapter()
                                }
                            }
                            tapX > w * 0.65f -> {
                                val maxPage2 = (currentTotalPages - 1).coerceAtLeast(0)
                                if (activeCurrentPage < maxPage2) {
                                    scope.launch {
                                        isAnimRunning = true
                                        turnDirection = TurnDirection.FORWARD
                                        val targetPage = activeCurrentPage + 1
                                        val leaf = displayedBitmap ?: cache.getSync(activeCurrentPage) ?: renderer.renderPage(activeCurrentPage)
                                        val under = nextBitmap ?: cache.getSync(targetPage) ?: renderer.renderPage(targetPage)
                                        frozenCurlLeaf  = leaf
                                        frozenUnderPage = under
                                        frozenBackside  = renderer.createBacksideBitmap(leaf, paperColor)
                                        isTurning = true
                                        animateCurlFraction(onUpdate = { dragFraction = it }, start = 0f, end = 1f)
                                        displayedBitmap = under
                                        currentPage = targetPage
                                        isTurning = false
                                        dragFraction = 0f
                                        isAnimRunning = false
                                        frozenCurlLeaf = null
                                        frozenUnderPage = null
                                        frozenBackside = null
                                    }
                                } else if (chapIdx < totalChaps - 1) {
                                    onNextChapter()
                                }
                            }
                            else -> onToggleControls()
                        }
                    }
                }
            }
    ) {
        // Use frozen bitmaps during animation, displayedBitmap when idle
        val curlLeafBmp = if (isTurning) frozenCurlLeaf ?: displayedBitmap else displayedBitmap
        val underPageBmp = if (isTurning) frozenUnderPage else null
        val backBmp = if (isTurning) frozenBackside else null

        Canvas(Modifier.fillMaxSize()) {
            if (isTurning && curlLeafBmp != null && dragFraction >= 0.001f) {
                drawPageCurlEffect(
                    currentBitmap  = curlLeafBmp,
                    nextBitmap     = underPageBmp,
                    backsideBitmap = backBmp,
                    foldFraction   = dragFraction.coerceIn(0f, 1f),
                    direction      = turnDirection,
                    doublePage     = false,
                    paperColorInt  = themeColors.paperColorInt,
                )
            } else {
                val cur = displayedBitmap
                if (cur != null) {
                    drawIntoCanvas { composeCanvas ->
                        val src = android.graphics.Rect(0, 0, cur.width, cur.height)
                        val dst = android.graphics.RectF(0f, 0f, size.width, size.height)
                        composeCanvas.nativeCanvas.drawBitmap(cur, src, dst, null)
                    }
                } else {
                    drawRect(Color(themeColors.paperColorInt))
                }
            }
        }

        if (displayedBitmap == null && ui.chapters.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private suspend fun animateCurlFraction(
    onUpdate: (Float) -> Unit,
    start: Float,
    end: Float,
) {
    val anim = androidx.compose.animation.core.Animatable(start)
    val spec = tween<Float>(
        durationMillis = 380,
        easing         = FastOutSlowInEasing,
    )
    anim.animateTo(end, spec) {
        onUpdate(value)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Error View & VM Factory
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Gå tilbake") }
    }
}

private fun defaultReaderVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val ctx = this[APPLICATION_KEY] as android.app.Application
            val db  = com.shelf.reader.data.local.ShelfDatabase.getInstance(ctx)
            ReaderViewModel(ctx, db)
        }
    }
