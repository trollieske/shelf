package com.shelf.reader.reader.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
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
    var showChapterMenu by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // ── Immersive Mode ──────────────────────────────────────────────────────
    DisposableEffect(showControls) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (showControls) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    var brightness by rememberSaveable { mutableFloatStateOf(-1f) }
    LaunchedEffect(brightness) { setWindowBrightness(context, brightness) }
    LaunchedEffect(bookId) { vm.load(bookId) }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.error != null -> ErrorView(ui.error!!, onBack)
            ui.chapters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> RealBookSlideReader(
                ui = ui,
                showControls = showControls,
                onToggleControls = { showControls = !showControls; if (!showControls) showChapterMenu = false },
                onPageTurned = { vm.onPageTurned(it) },
                onTotalPages = { vm.onPageCountKnown(it) },
                onNextChapter = { vm.nextChapter() },
                onPreviousChapter = { vm.previousChapter() }
            )
        }

        // ── Unified Bottom Control Deck ──────────────────────────────────────────
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(ui.bookTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Page ${ui.currentPage + 1} of ${ui.totalPages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showChapterMenu = !showChapterMenu }) {
                            Icon(Icons.AutoMirrored.Filled.List, "TOC", tint = if (showChapterMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Slider(value = ui.percent, onValueChange = { vm.seekToPercent(it) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row {
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp - 1) }, modifier = Modifier.size(38.dp)) { Text("A-", fontSize = 11.sp) }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp + 1) }, modifier = Modifier.size(38.dp)) { Text("A+", fontSize = 14.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Sepia", "Dark", "Black").forEach { theme ->
                                val isSelected = ui.readerTheme.equals(theme, ignoreCase = true)
                                Surface(onClick = { vm.setTheme(theme.lowercase()) }, shape = RoundedCornerShape(14.dp), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.height(38.dp)) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                                        Text(theme, style = MaterialTheme.typography.labelMedium, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Side Drawer
        AnimatedVisibility(visible = showControls && showChapterMenu, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)) {
            Surface(modifier = Modifier.fillMaxHeight().padding(vertical = 40.dp, horizontal = 12.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f), shadowElevation = 16.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("Chapters", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black))
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(ui.chapters) { idx, chapter ->
                            Surface(onClick = { vm.setCurrentChapter(idx); showChapterMenu = false }, color = if (idx == ui.currentChapterIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                Text(chapter.title, modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (idx == ui.currentChapterIndex) FontWeight.Bold else FontWeight.Normal))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RealBookSlideReader(
    ui: ReaderBookState,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onPageTurned: (Int) -> Unit,
    onTotalPages: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val swPx = with(density) { config.screenWidthDp.dp.toPx() }.toInt()
    val shPx = with(density) { config.screenHeightDp.dp.toPx() }.toInt()

    val paddingPx = with(density) { 32.dp.toPx() }.toInt()
    val effectiveWidthPx = swPx - (paddingPx * 2)

    val renderer = remember(effectiveWidthPx, shPx) { HtmlPageRenderer(context, effectiveWidthPx, shPx) }
    val cache = remember { PageBitmapCache(maxSize = 8) }
    DisposableEffect(renderer) { onDispose { renderer.release() } }

    val contentHtml = remember(ui.chapters, ui.currentChapterIndex) { if (ui.currentChapterIndex in ui.chapters.indices) ui.chapters[ui.currentChapterIndex].htmlContent else "" }
    val renderGen = remember { mutableIntStateOf(0) }
    
    LaunchedEffect(contentHtml, ui.fontSizeSp, ui.readerTheme) {
        renderGen.intValue++
        cache.clear()
        val count = renderer.prepare(contentHtml, ui.fontSizeSp, readerThemeColors(ui.readerTheme))
        onTotalPages(count)
    }

    if (ui.totalPages > 0) {
        val pagerState = rememberPagerState(initialPage = ui.currentPage.coerceIn(0, ui.totalPages - 1)) { ui.totalPages }
        val scope = rememberCoroutineScope()

        LaunchedEffect(ui.currentPage) { if (pagerState.currentPage != ui.currentPage) pagerState.scrollToPage(ui.currentPage.coerceIn(0, ui.totalPages - 1)) }
        LaunchedEffect(pagerState.currentPage) { onPageTurned(pagerState.currentPage) }

        Box(Modifier.fillMaxSize().background(Color(readerThemeColors(ui.readerTheme).paperColorInt))) {
            HorizontalPager(
                state = pagerState, 
                modifier = Modifier.fillMaxSize(), 
                beyondViewportPageCount = 1,
                pageSpacing = 0.dp
            ) { index ->
                val pGen = renderGen.intValue
                var bitmap by remember(index, pGen) { mutableStateOf<Bitmap?>(cache.getSync(index)) }
                LaunchedEffect(index, pGen) { if (bitmap == null) { val b = renderer.renderPage(index); cache.put(index, b); bitmap = b } }

                Box(Modifier.fillMaxSize().graphicsLayer {
                    val pageOffset = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction)
                    // Layered Slide (Kindle Style)
                    if (pageOffset < 0f) {
                        translationX = 0f
                    } else {
                        translationX = -size.width * pageOffset
                    }
                }) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp)) {
                        bitmap?.let { b ->
                            Canvas(Modifier.fillMaxSize()) {
                                drawIntoCanvas { it.nativeCanvas.drawBitmap(b, null, android.graphics.RectF(0f, 0f, size.width, size.height), null) }
                            }
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    
                    val pageOffset = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction)
                    if (pageOffset < 0f) {
                        Box(Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Black.copy(alpha = 0.15f * pageOffset.absoluteValue), Color.Transparent),
                                    startX = 0f, 
                                    endX = 40.dp.toPx()
                                )
                            )
                        })
                    }
                }
            }

            // ── Single High-Speed Gesture Layer ─────────────────────────────────
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val x = offset.x
                        val w = swPx.toFloat()
                        val edgeZone = w * 0.20f
                        
                        when {
                            x < edgeZone -> {
                                if (pagerState.currentPage > 0) scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                else onPreviousChapter()
                            }
                            x > w - edgeZone -> {
                                if (pagerState.currentPage < ui.totalPages - 1) scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                else onNextChapter()
                            }
                            else -> onToggleControls()
                        }
                    }
                )
            })
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun setWindowBrightness(context: Context, brightness: Float) {
    val lp = (context as? Activity)?.window?.attributes ?: return
    lp.screenBrightness = if (brightness < 0f) -1f else brightness.coerceIn(0.01f, 1.0f)
    (context as? Activity)?.window?.attributes = lp
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp)); Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp)); Button(onClick = onBack) { Text("Go Back") }
    }
}

private fun defaultReaderVmFactory(): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val ctx = this[APPLICATION_KEY] as android.app.Application
        ReaderViewModel(ctx, com.shelf.reader.data.local.ShelfDatabase.getInstance(ctx))
    }
}
