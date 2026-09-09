package com.shelf.reader.reader.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import eu.wewox.pagecurl.page.PageCurlTurnDirection
import eu.wewox.pagecurl.page.rememberPageCurlState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material3.*
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.reader.engine.HtmlPageRenderer
import com.shelf.reader.reader.engine.PageBitmapCache
import com.shelf.reader.reader.engine.ReaderBookState
import com.shelf.reader.reader.pageturn.*
import com.shelf.reader.reader.viewmodel.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
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
    var showContentsSheet by rememberSaveable { mutableStateOf(false) }
    var showThemesSheet by rememberSaveable { mutableStateOf(false) }
    var showBookmarksSheet by rememberSaveable { mutableStateOf(false) }
    var showInteractiveHighlightView by rememberSaveable { mutableStateOf(false) }
    var showSearchDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var orientationLocked by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val tracker = remember {
        (context.applicationContext as com.shelf.reader.core.di.AppDependenciesProvider).readingTracker
    }

    LaunchedEffect(bookId) {
        tracker.startSession(bookId.toString(), com.shelf.reader.core.gamification.model.SessionSource.READER)
    }

    DisposableEffect(bookId) {
        onDispose {
            tracker.endSession()
        }
    }

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
    val scope = rememberCoroutineScope()
    LaunchedEffect(brightness) { setWindowBrightness(context, brightness) }
    LaunchedEffect(bookId) { vm.load(bookId) }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.error != null -> ErrorView(ui.error!!, onBack)
            ui.chapters.isEmpty() && ui.bookTitle.isNotBlank() -> ErrorView(
                message = "Fant ingen lesbare kapitler i boken.\n\n" +
                    "Filen kan være tom, skadet, ha en DRM-beskyttelse, eller ha et støttet format som ikke kunne tolkes korrekt. Prøv å importere boken på nytt, eller konvertere til en ren DRM-fri EPUB først.",
                onBack = onBack,
            )
            ui.chapters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val themeColors = readerThemeColors(ui.readerTheme)
                val bgC = Color(themeColors.paperColorInt)

                // ── SITE WRAPPER BAKGRUNN (PageCurl bak ALLE menyer, INGEN SQUISH!) ──
                Box(Modifier.fillMaxSize().background(bgC)) {
                    RealBookSlideReader(
                        ui = ui,
                        showControls = showControls,
                        onToggleControls = { showControls = !showControls; if (!showControls) showContentsSheet = false; tracker.onUserInteraction() },
                        onPageTurned = { vm.onPageTurned(it); tracker.onUserInteraction() },
                        onTotalPages = { vm.onPageCountKnown(it) },
                        onJumpToChapterPage = { ch, page, pct ->
                            vm.jumpToChapterPage(ch, page, pct)
                            tracker.onUserInteraction()
                        },
                        onHighlight = { hl -> vm.saveHighlight(hl.text, hl.colorInt, hl.pageIndex, hl.startPageOffset, hl.endPageOffset) }
                    )
                }

                // ═══ OVERLAY MENY TOPP (hele veien opp, SOLID STRIP, ingen over leseflaten midt på) ═══
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(tween(120)) + slideInVertically { -it / 3 },
                    exit = fadeOut(tween(90)) + slideOutVertically { -it / 2 }
                ) {
                    Surface(
                        tonalElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake", modifier = Modifier.size(26.dp))
                            }
                            Text(
                                ui.bookTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                            )
                            IconButton(onClick = { showSearchDialog = true }) {
                                Icon(Icons.Default.Search, "Søk", modifier = Modifier.size(24.dp))
                            }
                            IconButton(onClick = {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    val pct = ((ui.percent.coerceIn(0f, 1f)) * 100).toInt()
                                    val chapTitle = ui.chapters.getOrNull(ui.currentChapterIndex)?.title?.takeIf { it.isNotBlank() } ?: "Kapittel ${ui.currentChapterIndex + 1}"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Jeg lser nå \"${ui.bookTitle}\" — $chapTitle (side ${ui.currentPage + 1} av ${ui.totalPages.coerceAtLeast(1)}, $pct%)\n#ShelfApp"
                                    )
                                    putExtra(android.content.Intent.EXTRA_TITLE, ui.bookTitle)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Del lesefremgang"))
                            }) {
                                Icon(Icons.Outlined.Share, "Del", modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }

                // ═══ OVERLAY MENY BUNN (hele veien ned, SOLID STRIP, ingen over leseflaten midt på) ═══
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(tween(120)) + slideInVertically { it / 3 },
                    exit = fadeOut(tween(90)) + slideOutVertically { it / 2 }
                ) {
                    Surface(
                        tonalElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        val pagesLeftInChapter = (ui.totalPages - ui.currentPage - 1).coerceAtLeast(0)
                        val pctStr = "${((ui.percent.coerceIn(0f, 1f)) * 100).toInt()}%"
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(top = 6.dp, bottom = 8.dp)
                        ) {
                            Text(
                                "$pagesLeftInChapter pages left · $pctStr · ${ui.currentPage + 1} of ${ui.totalPages.coerceAtLeast(1)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NavigationBarItem(
                                    selected = false,
                                    onClick = { showContentsSheet = true },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(26.dp)) },
                                    label = { Text("Contents", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                                )
                                NavigationBarItem(
                                    selected = false,
                                    onClick = { showThemesSheet = true },
                                    icon = {
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            Text("A", fontSize = 20.sp, fontWeight = FontWeight.Black)
                                            Text("A", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 1.dp))
                                        }
                                    },
                                    label = { Text("Themes", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                                )
                                NavigationBarItem(
                                    selected = false,
                                    onClick = {
                                        val page = ui.currentPage.coerceAtLeast(0)
                                        val pct = if (ui.totalPages > 1) page.toFloat() / (ui.totalPages - 1) else 0f
                                        vm.saveBookmark(pct, page)
                                    },
                                    icon = { Icon(Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(26.dp)) },
                                    label = { Text("Bookmark", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                                )
                                NavigationBarItem(
                                    selected = false,
                                    onClick = { showInteractiveHighlightView = true },
                                    icon = { Icon(Icons.Outlined.BorderColor, null, modifier = Modifier.size(26.dp)) },
                                    label = { Text("Mark Text", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                                )
                                NavigationBarItem(
                                    selected = orientationLocked,
                                    onClick = {
                                        orientationLocked = !orientationLocked
                                        val activity = context as? Activity
                                        if (activity != null) {
                                            activity.requestedOrientation = if (orientationLocked) {
                                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                            } else {
                                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                            }
                                        }
                                        android.widget.Toast.makeText(
                                            context,
                                            if (orientationLocked) "Skjerm låst i nåværende rotasjon" else "Skjerm låst opp",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    icon = {
                                        Icon(
                                            if (orientationLocked) Icons.Default.Lock else Icons.Outlined.Lock,
                                            null,
                                            modifier = Modifier.size(26.dp),
                                            tint = if (orientationLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    label = { Text(if (orientationLocked) "Låst" else "Lock", fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ═══ INTERAKTIV WEBVIEW FOR TEKSTMARKERING (Marker Tekst-knapp) ═══
        AnimatedVisibility(
            visible = showInteractiveHighlightView,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(100))
        ) {
            val themeColors = readerThemeColors(ui.readerTheme)
            val currentHtml = if (ui.currentChapterIndex in ui.chapters.indices) ui.chapters[ui.currentChapterIndex].htmlContent else ""
            val fontSize = ui.fontSizeSp
            val lang = "en"
            Column(Modifier.fillMaxSize().background(Color(themeColors.paperColorInt))) {
                // Top bar for markeringsmodus
                Surface(tonalElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Marker tekst · ${ui.chapters.getOrNull(ui.currentChapterIndex)?.title ?: ""}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)
                        )
                        FilledTonalButton(
                            onClick = { showInteractiveHighlightView = false },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Text("Ferdig", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Box(Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = false
                                    cacheMode = WebSettings.LOAD_NO_CACHE
                                    allowFileAccess = false
                                    textZoom = 100
                                    useWideViewPort = false
                                    loadWithOverviewMode = false
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                    setSupportZoom(false)
                                }
                                // Sikre at WebView ikke hopper rundt ved scroll:
                                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                                // Hindre at valgte tekster kopieres av Android-menyen (vår meny ligger ALLTID UNDER)
                                isLongClickable = true
                                isHapticFeedbackEnabled = false

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val scrollToPage = ui.currentPage.coerceAtLeast(0)
                                        // Vent 350 ms for at ALT skal være malt, kolonner bredder kjent, CSS ferdig
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            view?.evaluateJavascript(
                                                "(function(){try{ " +
                                                    "var pw = (document.documentElement.clientWidth || window.innerWidth || 360); " +
                                                    "var targetX = ($scrollToPage) * pw; " +
                                                    "window.scrollTo(targetX, 0);" +
                                                    " }catch(e){ console.error(e); }})();",
                                                null
                                            )
                                        }, 350)
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
                                val cssPageWidth = (ctx.resources.displayMetrics.widthPixels / density).toInt()
                                val cssQuoteBorder = 3f / density
                                val html = buildHighlightableHtml(currentHtml, fontSize, themeColors, lang, cssPageWidth, cssQuoteBorder)
                                addJavascriptInterface(
                                    object : Any() {
                                        @android.webkit.JavascriptInterface
                                        fun onHighlightCreated(text: String, colorInt: Int, pageIndex: Int, startOff: Double, endOff: Double) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                vm.saveHighlight(
                                                    text = text,
                                                    colorInt = colorInt,
                                                    pageIndex = pageIndex,
                                                    startOffset = startOff.toFloat(),
                                                    endOffset = endOff.toFloat()
                                                )
                                            }
                                        }
                                    },
                                    "AndroidPageReady"
                                )
                                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                        Text(
                            "Marker teksten med fingeren over → velg farge i menyen som dukker opp. Merkede setninger lagres i boken din.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // ── Themes & Settings Dialog ──────────────────────────────────────────
        if (showThemesSheet) {
            AlertDialog(
                onDismissRequest = { showThemesSheet = false },
                title = {
                    Text("Themes & Settings", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Font Size", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp - 1) }, modifier = Modifier.size(42.dp)) { Text("A-", fontSize = 11.sp) }
                            Text("${ui.fontSizeSp} sp", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp + 1) }, modifier = Modifier.size(42.dp)) { Text("A+", fontSize = 14.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Light", "Sepia", "Dark", "Black").forEach { theme ->
                                val isSelected = ui.readerTheme.equals(theme, ignoreCase = true)
                                Surface(
                                    onClick = { vm.setTheme(theme.lowercase()) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.height(40.dp).weight(1f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            theme,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemesSheet = false }) { Text("Close", fontWeight = FontWeight.Bold) }
                }
            )
        }

        // ── Bottom Sheet (Contents / Chapters) ──────────────────────────────────────
        if (showContentsSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showContentsSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = 6.dp,
                dragHandle = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(36.dp, 4.dp)) {}
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ui.bookTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { scope.launch { sheetState.hide(); showContentsSheet = false } }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            ) {
                LazyColumn(
                    Modifier.fillMaxWidth().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    itemsIndexed(ui.chapters) { idx, chapter ->
                        val selected = idx == ui.currentChapterIndex
                        Surface(
                            onClick = {
                                vm.setCurrentChapter(idx)
                                scope.launch { sheetState.hide(); showContentsSheet = false }
                            },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${idx + 1}.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSearchDialog) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                "Søk etter \"$searchQuery\" i nåværende kapittel – bytt til Marker tekst for interaktivt søk.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        showSearchDialog = false
                    }) { Text("Søk") }
                },
                dismissButton = {
                    TextButton(onClick = { showSearchDialog = false }) { Text("Avbryt") }
                },
                title = { Text("Søk i kapittel") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Ord eller setning") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Tips: trykk på Mark-tekst nederst i menyen for å bla i teksten og søke direkte i HTML-visningen med finne-i-side.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            if (activity != null && orientationLocked) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
private fun RealBookSlideReader(
    ui: ReaderBookState,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onPageTurned: (Int) -> Unit,
    onTotalPages: (Int) -> Unit,
    onJumpToChapterPage: (Int, Int, Float) -> Unit,
    onHighlight: (com.shelf.reader.reader.engine.HighlightData) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val swPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
    val shPx = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()

    val effectiveWidthPx = swPx - with(density) { 32.dp.toPx() }.toInt() * 2

    // ─────────────────────────────────────────────────────────────────────────
    // INDEKSMODELL (norm):
    //   curlCount == P   (P = reelle sider i DETTE kapittelet, indeks 0 = side 1)
    //   Ekstraside (indeks P) finnes KUN når brukeren står på siste side OG neste
    //   kapittels side 1 allerede ligger i cachen. Bakover-kant: speilvendt skift
    //   (+1) KUN når brukeren står på side 1 OG forrige kapittels siste side er
    //   cachet. Ingen fantomsider, ingen tom indeks 0.
    // ─────────────────────────────────────────────────────────────────────────
    val sizeKey = "$effectiveWidthPx-$shPx"
    val fontKey = "${ui.fontSizeSp}-${ui.readerTheme}"

    // Én WebView-renderer per kapittel (hovedkapittel + naboer til kant-forhåndstegning).
    val renderers = remember(effectiveWidthPx, shPx) {
        java.util.concurrent.ConcurrentHashMap<Int, HtmlPageRenderer>()
    }
    fun rendererFor(chapter: Int): HtmlPageRenderer =
        renderers.getOrPut(chapter) { HtmlPageRenderer(context, effectiveWidthPx, shPx, onHighlight) }
    DisposableEffect(effectiveWidthPx, shPx) {
        onDispose {
            renderers.values.forEach { it.release() }
            renderers.clear()
        }
    }

    // Kapittelnøklet bitmap-cache ("c<kap>p<side>"). Tømmes KUN ved font/tema/størrelse —
    // ALDRI ved kapittelbytte (kant-bitmaps og neste kapittels side 1 skal overleve).
    val cache = remember(effectiveWidthPx, shPx) { PageBitmapCache(maxSize = 14) }

    // Kapittel → kjent sidetall. observable slik at count følger med under kapittelbytte.
    val prepared = remember(effectiveWidthPx, shPx) { mutableStateMapOf<Int, Int>() }
    val lastKnownPages = remember(effectiveWidthPx, shPx) { mutableIntStateOf(1) }

    val chapters = ui.chapters
    val chapterCount = chapters.size
    val chapIdx = ui.currentChapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
    val hasPrev = chapIdx > 0
    val hasNext = chapIdx < chapterCount - 1

    // Sidetall for dette kapittelet: kjent fra klargjøring (også nabokapitler),
    // ellers VM-verdien, ellers sist kjente. Gir korrekt count umiddelbart etter hopp.
    val chapterPages = prepared[chapIdx]
        ?: ui.totalPages.takeIf { it > 0 }
        ?: lastKnownPages.intValue
    if (ui.totalPages > 0) lastKnownPages.intValue = ui.totalPages

    fun cacheKey(ch: Int, page: Int) = "c${ch}p${page}"

    // Bumpes når en kant-bitmap legges i cachen — gjør kant-klarhet observerbar.
    val cacheGeneration = remember { mutableIntStateOf(0) }
    fun nextEdgeReady(): Boolean = cacheGeneration.value >= 0 &&
        cache.getSync(cacheKey(chapIdx + 1, 0)) != null
    fun prevEdgeReady(): Boolean {
        cacheGeneration.value
        val pc = prepared[chapIdx - 1]
        return pc != null && pc > 0 && cache.getSync(cacheKey(chapIdx - 1, pc - 1)) != null
    }

    val curlState = rememberPageCurlState(initialCurrent = 0)
    val scope = rememberCoroutineScope()
    val updatedUi by rememberUpdatedState(ui)
    val updatedJump by rememberUpdatedState(onJumpToChapterPage)
    val updatedToggleControls by rememberUpdatedState(onToggleControls)

    // Bakover-skift: +1-forskyvning KUN mens vi står på reell side 1 med forrige kant klar.
    var leadingExtra by remember { mutableStateOf(false) }
    // Fremover-ekstraside: +1 på slutten KUN mens vi står på siste side med neste kant klar.
    var trailingExtra by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    LaunchedEffect(chapIdx) {
        committing = false
        leadingExtra = false
        trailingExtra = false
    }

    val curlCount = chapterPages + (if (trailingExtra) 1 else 0) + (if (leadingExtra) 1 else 0)

    fun realPageOf(curlIdx: Int): Int = curlIdx - if (leadingExtra) 1 else 0

    /** Avvis tomme/gjennomsiktige bitmapmer (renderer-timeout gir ARGB-0). */
    fun isUsableBitmap(b: Bitmap?): Boolean {
        if (b == null || b.isRecycled || b.width < 2 || b.height < 2) return false
        val w = b.width; val h = b.height
        val samples = listOf(intArrayOf(1, 1), intArrayOf(w / 2, h / 2), intArrayOf(w - 2, h - 2), intArrayOf(w / 2, 1))
        var first = 0
        var transparent = 0
        samples.forEachIndexed { i, pt ->
            val c = runCatching { b.getPixel(pt[0], pt[1]) }.getOrElse { return false }
            if (i == 0) first = c else if (c != first) return true
            if ((c ushr 24) == 0) transparent++
        }
        return transparent != samples.size
    }

    // Klargjør et kapittel i sin egen renderer (idempotent, observert via prepared).
    suspend fun prepareChapter(ch: Int): Int {
        prepared[ch]?.let { return it }
        if (ch < 0 || ch >= chapterCount) return 0
        val r = rendererFor(ch)
        val count = runCatching {
            r.prepare(chapters[ch].htmlContent, updatedUi.fontSizeSp, readerThemeColors(updatedUi.readerTheme))
        }.getOrDefault(0)
        if (count > 0) prepared[ch] = count
        return count
    }

    /** Forhåndstegn kant-siden til naborapittelet. Avviser tomme bitmapmer. */
    suspend fun prefetchNeighbor(chapter: Int, page: Int?) {
        val count = prepareChapter(chapter)
        if (count <= 0) return
        val p = (page ?: count - 1).coerceIn(0, count - 1)
        val key = cacheKey(chapter, p)
        if (cache.getSync(key) != null) return
        val bmp = runCatching { rendererFor(chapter).renderPage(p) }.getOrNull()
        if (isUsableBitmap(bmp)) cache.put(key, bmp!!)
    }

    // Klargjør nåværende kapittel. NB: ingen cache.clear() her — den hører hjemme
    // hos font/tema/størrelse ALENE (kapittelbytte skal aldri tømme cachen).
    LaunchedEffect(chapIdx, fontKey, sizeKey) {
        if (chapterCount == 0) return@LaunchedEffect
        val count = prepareChapter(chapIdx)
        if (count > 0 && chapIdx == updatedUi.currentChapterIndex) onTotalPages(count)
    }

    // Full re-render KUN ved font/tema/størrelse — aldri ved kapittelbytte.
    LaunchedEffect(fontKey, sizeKey) {
        cache.clear()
        prepared.clear()
        leadingExtra = false
        trailingExtra = false
    }

    // Siste viste reelle side — males UNDER PageCurl slik at bakgrunnen aldri er svart.
    val lastGood = remember(effectiveWidthPx, shPx) { mutableStateOf<Bitmap?>(null) }

    val themeColors = readerThemeColors(ui.readerTheme)
    val paperColor = Color(themeColors.paperColorInt)

    /** Hvem eier denne curl-indeksen? (kapittel, side) eller null. */
    fun pageOwner(pageIdx: Int): Pair<Int, Int>? {
        if (leadingExtra && pageIdx == 0) {
            val pc = prepared[chapIdx - 1] ?: return null
            return if (pc > 0) Pair(chapIdx - 1, pc - 1) else null
        }
        if (trailingExtra && pageIdx == chapterPages) return Pair(chapIdx + 1, 0)
        return if (pageIdx in 0 until chapterPages) Pair(chapIdx, pageIdx) else null
    }

    @Composable
    fun CurlPageContent(pageIdx: Int) {
        val target = pageOwner(pageIdx)
        val key = target?.let { cacheKey(it.first, it.second) } ?: ""
        var bitmap by remember(key, fontKey, sizeKey) {
            mutableStateOf<Bitmap?>(if (key.isEmpty()) null else cache.getSync(key))
        }
        LaunchedEffect(key, fontKey, sizeKey) {
            if (bitmap == null && target != null) {
                val bmp = runCatching { rendererFor(target.first).renderPage(target.second) }.getOrNull()
                if (isUsableBitmap(bmp)) {
                    cache.put(key, bmp!!)
                    bitmap = bmp
                }
            }
            if (target != null && target.first == chapIdx) {
                cache.getSync(key)?.let { lastGood.value = it }
            }
        }
        // Kant-side uten ferdig bitmap: hold sist viste reelle side (aldri svart/tom).
        val shown = bitmap
            ?: if (target == null || target.first != chapIdx) lastGood.value else null
        Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp)) {
            val b = shown
            if (b != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas {
                        val native = it.nativeCanvas
                        if (!b.isRecycled) {
                            native.drawBitmap(b, null, android.graphics.RectF(0f, 0f, size.width, size.height), null)
                        }
                    }
                }
            } else if (target != null && target.first == chapIdx) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    // ── ÉN hovedløkke: siderapportering, prefetch og kant-commits ────────────
    LaunchedEffect(chapIdx, chapterPages, hasNext, hasPrev) {
        var lastReported = -1
        snapshotFlow { Triple(curlState.current, cacheGeneration.value, leadingExtra) }.collect { (cur, _, shift) ->
            val real = cur - if (shift) 1 else 0
            val onForwardSentinel = trailingExtra && cur == chapterPages
            val onBackwardSentinel = leadingExtra && cur == 0

            when {
                !onForwardSentinel && !onBackwardSentinel && real in 0 until chapterPages -> {
                    if (real != lastReported) {
                        lastReported = real
                        onPageTurned(real)
                    }
                    // Prefetch nabo-kanten når vi er innen 1–2 reelle sider fra kanten.
                    if (hasNext && chapterPages - real <= 2) {
                        scope.launch { runCatching { prefetchNeighbor(chapIdx + 1, 0) } }
                    }
                    if (hasPrev && real <= 1) {
                        scope.launch { runCatching { prefetchNeighbor(chapIdx - 1, null) } }
                    }
                }
                onForwardSentinel && !committing -> {
                    if (nextEdgeReady()) {
                        committing = true
                        updatedJump(chapIdx + 1, 0, 0f)
                    } else {
                        // Bitmap forsvant (LRU-eviction): bli på reell siste side.
                        curlState.snapTo((chapterPages - 1).coerceAtLeast(0))
                    }
                }
                onBackwardSentinel && !committing -> {
                    if (prevEdgeReady()) {
                        val pc = prepared[chapIdx - 1] ?: 0
                        committing = true
                        updatedJump(chapIdx - 1, (pc - 1).coerceAtLeast(0), 1f)
                    } else {
                        curlState.snapTo(0)
                    }
                }
            }
        }
    }

    // Parkér på reell side 1 med forrige kant klar → skift på (indeks 0 = forrige kant).
    // Forlat side 1 → skift av (indeksen går tilbake til reell sidetelling, samme piksler).
    LaunchedEffect(hasPrev, chapIdx) {
        snapshotFlow { Pair(curlState.current, cacheGeneration.value) }.collect { (cur, _) ->
            if (!hasPrev || !prevEdgeReady()) {
                if (leadingExtra && cur == 1) {
                    leadingExtra = false
                    curlState.snapTo(0)
                }
            } else when {
                !leadingExtra && cur == 0 -> {
                    leadingExtra = true
                    curlState.snapTo(1)
                }
                leadingExtra && cur >= 2 -> {
                    leadingExtra = false
                    curlState.snapTo(cur - 1)
                }
            }
        }
    }

    // Fremover-ekstraside: på plass bare når vi står på siste reelle side og neste kant er klar.
    LaunchedEffect(chapterPages) {
        snapshotFlow { Pair(curlState.current, cacheGeneration.value) }.collect { (cur, _) ->
            val want = hasNext && nextEdgeReady() && cur >= chapterPages - 1 && cur <= chapterPages
            if (trailingExtra != want) trailingExtra = want
        }
    }

    // ── Posisjonssynk: VM-side → curl-indeks (ingen leadingOffset-kamp) ──────
    LaunchedEffect(ui.currentPage, curlCount, leadingExtra) {
        val target = ui.currentPage.coerceAtLeast(0) + if (leadingExtra) 1 else 0
        if (curlCount > 0 && curlState.current != target) {
            curlState.snapTo(target.coerceIn(0, curlCount - 1))
        }
    }

    val infinitePulse: InfiniteTransition = rememberInfiniteTransition(label = "edge-pulse")
    val pulseAlpha by infinitePulse.animateFloat(
        initialValue = 0.12f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse-alpha"
    )

    val pageCurlConfig = rememberPageCurlConfig(
        backPageColor = paperColor,
        backPageContentAlpha = 0.08f,
        shadowColor = Color.Black,
        shadowAlpha = 0.25f,
        shadowRadius = 15.dp,
        tapForwardEnabled = true,
        tapBackwardEnabled = true,
        tapCustomEnabled = true,
        // Kun midt-tapp = kontroller. Kant-tapp er normal krøll inn i/ut av kant-siden.
        onCustomTap = customTapHandler@{ size, offset ->
            val width = size.width.toFloat().coerceAtLeast(1f)
            val xFrac = offset.x.toFloat().coerceIn(0f, width) / width
            if (xFrac > 0.28f && xFrac < 0.72f) {
                updatedToggleControls()
                return@customTapHandler true
            }
            false
        }
    )

    val realNow = curlState.current - if (leadingExtra) 1 else 0

    Box(Modifier.fillMaxSize().background(paperColor)) {
        // lastGood under PageCurl: bakgrunnen er ALLITID papir med innhold — aldri svart.
        lastGood.value?.let { b ->
            Canvas(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp)) {
                drawIntoCanvas {
                    val native = it.nativeCanvas
                    if (!b.isRecycled) {
                        native.drawBitmap(b, null, android.graphics.RectF(0f, 0f, size.width, size.height), null)
                    }
                }
            }
        }

        if (curlCount > 0) {
            PageCurl(
                count = curlCount,
                state = curlState,
                config = pageCurlConfig,
                interactionsEnabled = true,
                // Kamigura-forken: vis den ANKOMMENDE siden på krøllklaffen.
                backContent = { cur, forward ->
                    CurlPageContent(if (forward) cur + 1 else cur - 1)
                },
                modifier = Modifier.fillMaxSize()
            ) { pageIdx ->
                CurlPageContent(pageIdx)
            }

            // ── Kapittelkant indikator (puls ved kanten, 65dp) ─
            if (realNow == 0 && hasPrev) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Black.copy(alpha = pulseAlpha), Color.Transparent),
                                    startX = 0f,
                                    endX = with(density) { 65.dp.toPx() }
                                )
                            )
                        }
                )
            }
            if (realNow == chapterPages - 1 && hasNext) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = pulseAlpha)),
                                    startX = size.width - with(density) { 65.dp.toPx() },
                                    endX = size.width
                                )
                            )
                        }
                )
            }

            if (showControls) {
                val metrics by rendererFor(chapIdx).lastMetrics.collectAsStateWithLifecycle()
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
                    color = Color.Black.copy(0.7f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Rendering: $metrics",
                        color = Color.White,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
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

private fun buildHighlightableHtml(
    content: String,
    fontSizeSp: Int,
    theme: com.shelf.reader.reader.pageturn.ReaderThemeColors,
    lang: String,
    cssPageWidth: Int,
    cssQuoteBorder: Float,
): String {
    val colorsJson = arrayOf(
        "\"#FFDD55\":0xFFFFFF7F",
        "\"#FF9AA2\":0xFFFF9AA2",
        "\"#B5DEFF\":0xFFB5DEFF",
        "\"#C7CEEA\":0xFFC7CEEA",
        "\"#A0E7E5\":0xFFA0E7E5",
        "\"#B4F8C8\":0xFFB4F8C8",
    ).joinToString(",")
    val scriptJs = """
        (function() {
            var colors = [
                { hex: '#FFDD55', android: -65793 },
                { hex: '#FF9AA2', android: -41962 },
                { hex: '#B5DEFF', android: -4857089 },
                { hex: '#C7CEEA', android: -3682582 },
                { hex: '#A0E7E5', android: -6230043 },
                { hex: '#B4F8C8', android: -4917304 }
            ];
            var ui = document.createElement('div');
            ui.style.cssText = 'position:fixed;z-index:9999;display:none;padding:6px 10px;background:rgba(30,30,32,0.98);border-radius:12px;box-shadow:0 4px 18px rgba(0,0,0,0.4);';
            ui.className = '__hl_float';
            colors.forEach(function(c){
                var s=document.createElement('span');
                s.setAttribute('data-c', c.android);
                s.setAttribute('data-hex', c.hex);
                s.style.cssText='display:inline-block;width:26px;height:26px;border-radius:50%;margin:0 4px;cursor:pointer;border:2px solid rgba(255,255,255,0.75);background:'+c.hex;
                s.addEventListener('click', function(ev){
                    ev.preventDefault(); ev.stopPropagation();
                    var sel = window.getSelection();
                    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { ui.style.display = 'none'; return; }
                    var text = sel.toString();
                    if (!text || text.trim().length === 0) { ui.style.display = 'none'; return; }
                    var hex = this.getAttribute('data-hex') || '#FFDD55';
                    var cInt = parseInt(this.getAttribute('data-c'), 10);
                    try {
                        var range = sel.getRangeAt(0);
                        // ─── WRAP TEKSTEN I <span> MED BAKGRUNNSFARGE SÅ DEN BLIR SYNLIG! ───
                        var span = document.createElement('span');
                        span.style.backgroundColor = hex;
                        span.style.padding = '0 2px';
                        span.style.borderRadius = '3px';
                        try {
                            range.surroundContents(span);
                        } catch(e) {
                            // Fallback hvis range går over elementer: ekstraher innhold og pakk inn
                            try {
                                var content = range.extractContents();
                                span.appendChild(content);
                                range.insertNode(span);
                            } catch(e2) {}
                        }
                    } catch(e) { console.error(e); }
                    var pageWidth = (document.documentElement.clientWidth || window.innerWidth || 360);
                    var rect = (function(){ try{ var t = document.createElement('span'); t.style.position='relative'; t.style.left='0'; t.style.visibility='hidden'; return (window.getSelection && window.getSelection().rangeCount) ? window.getSelection().getRangeAt(0).getBoundingClientRect() : {left:0,right:pageWidth}; }catch(e){ return {left:0,right:pageWidth}; } })();
                    var page = Math.max(0, Math.floor(rect.left / pageWidth));
                    var relLeft = rect.left - page * pageWidth;
                    var relRight = rect.right - page * pageWidth;
                    var startFrac = Math.max(0, Math.min(1, relLeft / pageWidth));
                    var endFrac = Math.max(0, Math.min(1, relRight / pageWidth));
                    try { AndroidPageReady.onHighlightCreated(text, cInt, page, Math.min(startFrac, endFrac), Math.max(startFrac, endFrac)); } catch(e){ console.error(e); }
                    sel.removeAllRanges();
                    ui.style.display = 'none';
                });
                ui.appendChild(s);
            });
            document.body.appendChild(ui);
            function hideIfOutside(e){ if (ui.style.display === 'none') return; var r = ui.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) ui.style.display = 'none'; }
            document.addEventListener('selectionchange', function(){
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0 || sel.isCollapsed || sel.toString().trim().length === 0) { ui.style.display = 'none'; return; }
                var rect = sel.getRangeAt(0).getBoundingClientRect();
                ui.style.display = 'block';
                // VI VISER ALLTID MENYEN NEDENFOR TEKSTEN! Da unngår vi OnePlus sin oppover-plasserte Copy-meny!
                var top = rect.bottom + 20;
                var viewportH = window.innerHeight || document.documentElement.clientHeight || 800;
                if (top + 60 > viewportH) top = rect.top - 70;
                if (top < 6) top = rect.bottom + 20;
                var left = rect.left + rect.width/2 - ui.offsetWidth/2;
                if (left < 6) left = 6;
                var maxL = (window.innerWidth || 360) - ui.offsetWidth - 6;
                if (left > maxL) left = maxL;
                ui.style.top = top + 'px';
                ui.style.left = left + 'px';
            });
            document.addEventListener('mousedown', hideIfOutside);
            document.addEventListener('touchstart', hideIfOutside, {passive:true});
        })();
    """.trimIndent()
    return """
    <!DOCTYPE html>
    <html lang="${lang.ifBlank { "en" }}">
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
    <style>
      *, *::before, *::after { box-sizing: border-box; }
      html, body { 
        margin: 0; padding: 0; height: 100%; width: 100%; 
        background: ${theme.bodyBg}; color: ${theme.textColor};
        -webkit-text-size-adjust: none;
      }
      body { 
        font-family: "Crimson Pro", "EB Garamond", "Palatino", "Georgia", serif; 
        font-size: ${fontSizeSp}px; 
        line-height: 1.7; 
        text-rendering: optimizeLegibility;
        -webkit-font-smoothing: antialiased;
        -webkit-tap-highlight-color: transparent;
        overflow-x: auto;
        overflow-y: hidden;
      }
      #content-wrapper {
        display: block; 
        min-height: 100vh;
        min-width: 100vw;
        width: max-content;
        margin: 0;
        padding: 48px 64px 64px 64px;
        column-width: calc(100vw - 128px); 
        column-gap: 96px; 
        column-fill: auto;
        word-wrap: break-word; 
        overflow-wrap: break-word; 
        hyphens: auto; 
        -webkit-hyphens: auto; 
        text-align: justify;
        orphans: 1;
        widows: 1;
      }
      h1, h2, h3 { color: ${theme.headingColor}; text-align: center !important; margin: 1.2em 0 0.6em !important; font-weight: 700 !important; line-height: 1.3; }
      h1 { font-size: 1.5em !important; }
      h2 { font-size: 1.3em !important; }
      h3 { font-size: 1.15em !important; }
      p { margin: 0 0 0.7em !important; text-align: justify !important; text-indent: 1.6em !important; line-height: 1.7 !important; }
      img, svg { max-width: 100% !important; height: auto !important; display: block !important; margin: 0.8em auto !important; }
      blockquote { border-left: ${cssQuoteBorder}px solid ${theme.headingColor}; padding-left: 1.2em; margin: 1.5em 0; font-style: italic; opacity: 0.92; }
      ::selection { background: rgba(255, 205, 90, 0.55); }
    </style>
    </head>
    <body><div id="content-wrapper">$content</div>
    <script>$scriptJs</script>
    </body>
    </html>
    """.trimIndent()
}

