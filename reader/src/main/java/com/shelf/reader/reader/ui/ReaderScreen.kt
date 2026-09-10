package com.shelf.reader.reader.ui

import android.app.Activity
import android.util.Log
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
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
import com.shelf.reader.reader.engine.RenderCoordinator
import com.shelf.reader.reader.engine.PageBitmapCache
import com.shelf.reader.reader.engine.PageFlowState
import com.shelf.reader.reader.engine.PageWindow
import com.shelf.reader.reader.engine.ReaderBookState
import com.shelf.reader.reader.engine.ReaderPageRef
import com.shelf.reader.reader.pageturn.*
import com.shelf.reader.reader.viewmodel.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

// Fokusert diagnostikk for kant-krøllen (slås AV i endelig kode).
private const val CURL_DIAG = false
private fun curlDiag(msg: String) { if (CURL_DIAG) Log.d("CurlDiag", msg) }

// Diagnostikk for blink-jakt (slås AV i release): logger kun hendelser som kan
// påvirke synlig innhold — prepare-fullføring, cache-skriving, vindusendring,
// indeks-remapping og grense-commit. Aldri rått bokinnhold.
private const val READER_DIAG = false // gated: kun diagnostikkbygg
private fun readerDiag(msg: String) { if (READER_DIAG) Log.d("ReaderDiag", msg) }

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

    // Navigasjons-epoke: økes KUN ved eksterne navigasjonshendelser (TOC-valg,
    // gjenoppretting etter klargjøring, font/tema-endring). Vanlige sidevendinger
    // endrer den aldri — CurlState forblir autoritativ under lesing.
    var navEpoch by remember { mutableStateOf(0) }

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
                        navEpoch = navEpoch,
                        showControls = showControls,
                        onToggleControls = { showControls = !showControls; if (!showControls) showContentsSheet = false; tracker.onUserInteraction() },
                        onPageTurned = { vm.onPageTurned(it); tracker.onUserInteraction() },
                        onTotalPages = { vm.onPageCountKnown(it); navEpoch++ },
                        onJumpToChapterPage = { ch, page, pct -> vm.jumpToChapterPage(ch, page, pct) },
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
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp - 1); navEpoch++ }, modifier = Modifier.size(42.dp)) { Text("A-", fontSize = 11.sp) }
                            Text("${ui.fontSizeSp} sp", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp + 1); navEpoch++ }, modifier = Modifier.size(42.dp)) { Text("A+", fontSize = 14.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Light", "Sepia", "Dark", "Black").forEach { theme ->
                                val isSelected = ui.readerTheme.equals(theme, ignoreCase = true)
                                Surface(
                                    onClick = { vm.setTheme(theme.lowercase()); navEpoch++ },
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
                    val tocItems = ui.chapters.withIndex().filter { it.value.inToc }
                    itemsIndexed(tocItems) { _, tocEntry ->
                        val idx = tocEntry.index
                        val chapter = tocEntry.value
                        val selected = idx == ui.currentChapterIndex
                        Surface(
                            onClick = {
                                vm.setCurrentChapter(idx)
                                navEpoch++
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

/** Avvis gjenvunnet, feil størrelse, gjennomsiktig eller ellers ugyldig bitmap. */
private fun isUsableRenderBitmap(b: Bitmap): Boolean {
    if (b.isRecycled || b.width < 2 || b.height < 2) return false
    val w = b.width
    val h = b.height
    val samples = listOf(
        intArrayOf(1, 1), intArrayOf(w / 2, h / 2), intArrayOf(w - 2, h - 2), intArrayOf(w / 2, 1)
    )
    var first = 0
    var transparent = 0
    samples.forEachIndexed { i, pt ->
        val c = runCatching { b.getPixel(pt[0], pt[1]) }.getOrElse { return false }
        if (i == 0) first = c else if (c != first) return true
        if ((c ushr 24) == 0) transparent++
    }
    return transparent != samples.size
}

/**
 * Felles Paint-flagg for ALLE drawBitmap-kall av rendererte leser-sider:
 * bilinær filtering ved skalering, dithering og antialiasing — aldri null-Paint.
 * Konstant (ikke instans) slik at flagg-settet er JVM-enhetstestbart.
 */
internal const val PAGE_BITMAP_PAINT_FLAGS: Int =
    android.graphics.Paint.ANTI_ALIAS_FLAG or
    android.graphics.Paint.FILTER_BITMAP_FLAG or
    android.graphics.Paint.DITHER_FLAG

@OptIn(ExperimentalPageCurlApi::class)
@Composable
private fun RealBookSlideReader(
    ui: ReaderBookState,
    navEpoch: Int,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onPageTurned: (Int) -> Unit,
    onTotalPages: (Int) -> Unit,
    onJumpToChapterPage: (sectionIndex: Int, localPageIndex: Int, chapterPct: Float) -> Unit,
    onHighlight: (com.shelf.reader.reader.engine.HighlightData) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val swPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
    val shPx = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()

    // Sideinnholdsboksen (identisk med paddingen i CurlPageContent/lastGood):
    // rendererens bitmapdimensjoner MÅ være nøyaktig disse — aldri en blanding
    // av fullskjerm- og padde-mål (som gav vertikal skvis + skaleringsartefakter).
    val pageHPad = 32.dp
    val pageVPad = 48.dp
    val effectiveWidthPx = swPx - with(density) { (pageHPad * 2).toPx() }.toInt()
    val contentHeightPx = shPx - with(density) { (pageVPad * 2).toPx() }.toInt()
    val sizeKey = "$effectiveWidthPx-$contentHeightPx"
    val fontKey = "${ui.fontSizeSp}-${ui.readerTheme}"
    val configKey = "$fontKey-$sizeKey"
    fun renderKey(ch: Int, page: Int): String = "$ch|$configKey|p$page"

    val chapters = ui.chapters
    val chapterCount = chapters.size
    val chapIdx = ui.currentChapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
    val hasNextSect = chapIdx < chapterCount - 1
    val hasPrevSect = chapIdx > 0

    // Én WebView-renderer per kapittel — eierskap håndheves av RenderCoordinator.
    val renderers = remember(effectiveWidthPx, contentHeightPx) {
        java.util.concurrent.ConcurrentHashMap<Int, HtmlPageRenderer>()
    }
    fun rendererFor(ch: Int): HtmlPageRenderer =
        renderers.getOrPut(ch) { HtmlPageRenderer(context, effectiveWidthPx, contentHeightPx, onHighlight) }
    DisposableEffect(effectiveWidthPx, contentHeightPx) {
        onDispose {
            renderers.values.forEach { it.release() }
            renderers.clear()
        }
    }

    // ── Bitmap-cache + klargjøringskart. Tømmes kun ved font/tema/størrelse —
    // ALDRI ved seksjonsbytte (kant-bitmapmer fra nabo-seksjoner skal overleve). ──
    val cache = remember(effectiveWidthPx, contentHeightPx, fontKey) { PageBitmapCache(maxSize = 14) }
    val lastKnownPages = remember(effectiveWidthPx, contentHeightPx) { mutableIntStateOf(1) }
    val prepared = remember(effectiveWidthPx, contentHeightPx, fontKey) { mutableStateMapOf<Int, Int>() }

    // Gjør cache-ankomster observerbare i komposisjonen: økes KUN av
    // koordinator-callbacken etter en vellykket cache-skriving.
    val cacheGeneration = remember(effectiveWidthPx, contentHeightPx, fontKey) { mutableIntStateOf(0) }

    // ── Render-koordinator: enkelt-eierskap, dedup på nøkkel, spesulativ lav prio ──
    val coordinatorScope = remember(effectiveWidthPx, contentHeightPx, fontKey) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
    DisposableEffect(effectiveWidthPx, contentHeightPx, fontKey) {
        onDispose { coordinatorScope.cancel() }
    }
    // Koordinatoren er ENESTE forfatter i cachen: callbacken validerer bitmapmen
    // (isUsable), skriver under den kanoniske nøkkelen (pageKey === renderKey) og
    // øker cacheGeneration — aldri spredte cache.put/generasjon-skrifter i UI-koden.
    val coordinator = remember(effectiveWidthPx, contentHeightPx, fontKey) {
        RenderCoordinator<Bitmap>(
            scope = coordinatorScope,
            isUsable = ::isUsableRenderBitmap,
            onRendered = { _, key, bmp ->
                cache.put(key, bmp)
                cacheGeneration.intValue++
                readerDiag("CACHE-PUT key='$key' gen=$cacheGeneration bmp=${bmp.width}x${bmp.height}")
            },
        )
    }

    // Sidetall for denne seksjonen: kjent fra klargjøring vinner (aldri forrige
    // seksjons stale totalPages), ellers VM, ellers sist kjente.
    if (ui.totalPages > 0) lastKnownPages.intValue = ui.totalPages
    val chapterPages = prepared[chapIdx]
        ?: ui.totalPages.takeIf { it > 0 }
        ?: lastKnownPages.intValue

    // ── PageWindow: ÉN eier av mappingen curl-indeks ↔ ReaderPageRef ──
    // Kantsider (forrige seksjons SISTE side / neste seksjons side 0) finnes i
    // vinduet FØRST når bitmapmen allerede ligger i cachen under den kanoniske
    // renderKey — aldri som fantom. Ingen "prepared ?: 1"-fallback: er forrige
    // seksjon ikke klargjort, er prevFinalLocal -1 og prevSectionReady dermed false.
    // Lesing av cacheGeneration her gjør cache-ankomster reaktive i komposisjonen.
    val cacheGenObserved = cacheGeneration.intValue
    val prevFinalLocal = if (hasPrevSect) (prepared[chapIdx - 1] ?: 0) - 1 else -1
    val prevSectionReady = hasPrevSect && prevFinalLocal >= 0 &&
        cache.getSync(renderKey(chapIdx - 1, prevFinalLocal)) != null
    val nextSectionReady = hasNextSect && prepared.containsKey(chapIdx + 1) &&
        cache.getSync(renderKey(chapIdx + 1, 0)) != null
    val prevReadyRef = if (prevSectionReady) ReaderPageRef(chapIdx - 1, prevFinalLocal) else null
    val nextReadyRef = if (nextSectionReady) ReaderPageRef(chapIdx + 1, 0) else null

    val sectionPageCount = chapterPages.coerceAtLeast(1)
    // Startvindu: kun reelle sider i seksjonen — null fantom-sider. Vinduet
    // PERSISTERER med vilje over seksjonsbyttet (ingen chapIdx-nøkkel): det gamle
    // vinduet løser landingssiden korrekt via sin kant-referanse i commit-rammen,
    // og remap-effekten skriver ny curl-indeks + nytt vindu i SAMME effekt-fase.
    // PageCurl ser aldri (nytt count, stale indeks) — som tidligere ga én frames
    // feil side/papir ("blink") ved hvert grensekryss.
    var window by remember { mutableStateOf(PageFlowState.initial(chapIdx, sectionPageCount)) }

    // Overgangstilstand: ventende commit-mål (seksjon + lokal side). Må ligge FØR
    // vindus-effektene (de leger den for å la remap-effekten eie seksjonsskiftet).
    var committing by remember { mutableStateOf(false) }
    var pendingNavSection by remember { mutableStateOf<Int?>(null) }
    var pendingNavTarget by remember { mutableStateOf<Int?>(null) }

    val curlState = rememberPageCurlState(initialCurrent = 0)
    val scope = rememberCoroutineScope()
    val updatedUi by rememberUpdatedState(ui)
    val updatedToggleControls by rememberUpdatedState(onToggleControls)
    val updatedJump by rememberUpdatedState(onJumpToChapterPage)
    val updatedNextReady by rememberUpdatedState(nextReadyRef)
    val updatedPrevReady by rememberUpdatedState(prevReadyRef)

    // Bygg vinduet på nytt ved sidetall-korrigering (prepare fullført) eller
    // seksjonsskifte uten ventende commit-remap. Snap skjer FØR vindusskrivingen
    // (samme effekt-fase, ingen blandet komposisjon).
    LaunchedEffect(chapIdx, sectionPageCount, pendingNavTarget) {
        if (pendingNavTarget != null) return@LaunchedEffect // remap-effekten eier overgangen
        if (window.sectionIndex == chapIdx && window.sectionPageCount == sectionPageCount) {
            return@LaunchedEffect
        }
        snapshotFlow { curlState.progress }.first { it == 0f } // aldri snap midt i gest
        val local = updatedUi.currentPage.coerceIn(0, sectionPageCount - 1)
        val fresh = PageFlowState.initial(chapIdx, sectionPageCount)
        val idx = PageFlowState.curlIndexOfLocal(fresh, local)
        if (curlState.current != idx) curlState.snapTo(idx)
        window = fresh
        readerDiag("WINDOW-RESET ch=$chapIdx count=$sectionPageCount snapTo=$idx")
    }

    /** Kjerne uten lås — kalleren (koordinatoren) eier eier-låsen. */
    suspend fun prepareChapterUnlocked(ch: Int): Int {
        prepared[ch]?.let { return it }
        if (ch < 0 || ch >= chapterCount) return 0
        val count = runCatching {
            rendererFor(ch).prepare(chapters[ch].htmlContent, updatedUi.fontSizeSp, readerThemeColors(updatedUi.readerTheme))
        }.getOrDefault(0)
        if (count > 0) prepared[ch] = count
        return count
    }

    /** Klargjør kapittel i sin renderer — gjennom koordinatorens eier-lås. */
    suspend fun prepareChapter(ch: Int): Int = coordinator.withOwner(ch) { prepareChapterUnlocked(ch) }

    /**
     * Forhåndstegn side [page] i nabo-seksjonen [chapter]: prepare via eier-låsen,
     * deretter render GJENNOM koordinatoren (renderCurrent) — aldri direkte
     * rendererFor(...).renderPage(...). Koordinator-callbacken skriver cachen og
     * øker cacheGeneration når bitmapmen er valgt og lagret.
     */
    suspend fun prefetchNeighbor(chapter: Int, page: Int) {
        val count = prepareChapter(chapter)
        if (count <= 0 || chapter < 0 || chapter >= chapterCount) return
        val target = if (page < 0) count - 1 else page.coerceIn(0, count - 1)
        val key = renderKey(chapter, target)
        if (cache.getSync(key) != null) return
        coordinator.renderCurrent(
            ownerKey = chapter,
            pageKey = key,
            page = target,
        ) { p -> rendererFor(chapter).renderPage(p, key) }
        readerDiag("PREFETCH-DONE ch=$chapter page=$target")
    }

    // Klargjør nåværende seksjon (font/tema/størrelse re-klargjør) og
    // forhåndstegn nabo-seksjonenes kant-sider (grense-krøll uten tom innhold).
    LaunchedEffect(chapIdx, fontKey, sizeKey) {
        if (chapterCount == 0) return@LaunchedEffect
        coordinator.cancelSpeculative()
        val count = prepareChapter(chapIdx)
        readerDiag("PREPARE-DONE ch=$chapIdx pages=$count")
        if (count > 0 && chapIdx == updatedUi.currentChapterIndex) onTotalPages(count)
        if (hasPrevSect) scope.launch { runCatching { prefetchNeighbor(chapIdx - 1, -1) } }
        if (hasNextSect) scope.launch { runCatching { prefetchNeighbor(chapIdx + 1, 0) } }
    }

    // Full re-render kun ved font/tema/størrelse.
    LaunchedEffect(fontKey, sizeKey) {
        cache.clear()
        prepared.clear()
    }

    val lastGood = remember(effectiveWidthPx, contentHeightPx) { mutableStateOf<Bitmap?>(null) }
    // ÉN gjenbrukbar Paint for ALLE drawBitmap-kall av rendererte leser-sider:
    // filtering + dithering + antialiasing (aldri null-Paint), opprettes ikke på nytt per ramme.
    val pageBitmapPaint = remember(effectiveWidthPx, contentHeightPx, fontKey) {
        android.graphics.Paint(PAGE_BITMAP_PAINT_FLAGS)
    }
    val themeColors = readerThemeColors(ui.readerTheme)
    val paperColor = Color(themeColors.paperColorInt)

    // ── Sidevending, vindusstabilisering og ÉN overgangshandler ─────────────
    // ÉN eier (denne collectoren) håndterer:
    //   1. vindusstabilisering — kant-sider legges til/fjernes KUN når PageCurl er
    //      i ro (progress == 0f: ingen gest, ingen animasjon) og bitmapmen er cachet,
    //   2. landing på en kant-ReaderPageRef (kun etter fullført krøll),
    //   3. nøyaktig ÉN jumpToChapterPage-commit per seksjonskryss.
    // Reset committing først når den nye seksjonens tilstand er observert (ny chapIdx).
    LaunchedEffect(chapIdx) { committing = false }
    LaunchedEffect(chapIdx, sectionPageCount) {
        var lastReported = -1
        var armed = false
        // Observerer krøllposisjon, krøll-ro OG ready-referansene (rememberUpdated-
        // State): når en kant-bitmap ankommer mens brukeren står på en grense i ro,
        // utvides vinduet umiddelbart — samme eier, ingen separate collectore.
        snapshotFlow {
            Triple(curlState.current, curlState.progress, updatedNextReady to updatedPrevReady)
        }.collect { (cur, prog, ready) ->
            if (updatedUi.currentChapterIndex != chapIdx) return@collect // stale instans
            if (pendingNavTarget != null) return@collect // remap i gang — ignorer alt annet
            val ref = PageFlowState.resolve(window, cur) ?: return@collect // fantom/utenfor
            val crossing = ref.sectionIndex != chapIdx
            when {
                crossing && !committing -> {
                    // Fullført krøll landet på en kant-side: commit nøyaktig én gang.
                    // Vinduet bygges på nytt rundt den allerede synlige pikslen (samme
                    // renderKey → samme bitmap-instans), og curl-indeksen remappes
                    // til mål-lokal-siden KUN når PageCurl er i ro.
                    committing = true
                    val pct = if (ref.sectionIndex > chapIdx) 0f else 1f
                    pendingNavSection = ref.sectionIndex
                    pendingNavTarget = ref.localPageIndex
                    readerDiag("COMMIT ${chapIdx} -> ${ref.sectionIndex}:${ref.localPageIndex} pct=$pct")
                    updatedJump(ref.sectionIndex, ref.localPageIndex, pct)
                }
                !crossing && !armed -> {
                    if (ref.localPageIndex == updatedUi.currentPage && ref.localPageIndex < sectionPageCount) {
                        armed = true
                        lastReported = ref.localPageIndex
                    }
                }
                !crossing && ref.localPageIndex != lastReported -> {
                    lastReported = ref.localPageIndex
                    onPageTurned(ref.localPageIndex)
                }
            }
            // Stabiliser vinduet kun i ro: samme ReaderPageRef forblir synlig —
            // indeksremapping skjer via snapTo når kant-sider legges til/fjernes.
            if (!crossing && !committing && prog == 0f) {
                val stabilized = PageFlowState.stabilize(window, cur, ready.first, ready.second)
                if (stabilized != window) {
                    readerDiag(
                        "STABILIZE cur=$cur ${window.count}->${stabilized.count} " +
                        "leading=${stabilized.leading != null} trailing=${stabilized.trailing != null}"
                    )
                    window = stabilized
                    val sameRefIdx = stabilized.indexOfRef(ref)
                    if (sameRefIdx != null && sameRefIdx != cur) {
                        readerDiag("STABILIZE-REMAP cur=$cur -> $sameRefIdx")
                        curlState.snapTo(sameRefIdx)
                    }
                }
            }
        }
    }

    // Konsum av overgangsmål: remapp curl-indeksen til mål-lokal-siden og bygg
    // vinduet på nytt i SAMME effekt-fase (begge skrivene før neste komposisjon —
    // ingen blandet (nytt count, gammel indeks)-ramme = ingen blink). Kjøres kun
    // når PageCurl er i ro og den nye seksjonens tilstand er observert.
    LaunchedEffect(pendingNavTarget, chapIdx, sectionPageCount) {
        val t = pendingNavTarget ?: return@LaunchedEffect
        val targetSection = pendingNavSection ?: return@LaunchedEffect
        if (updatedUi.currentChapterIndex != targetSection) return@LaunchedEffect // vent på ny seksjon
        snapshotFlow { curlState.progress }.first { it == 0f } // aldri snap midt i gest/animasjon
        val fresh = PageFlowState.initial(targetSection, sectionPageCount)
        val idx = t.coerceIn(0, sectionPageCount - 1)
        if (curlState.current != idx) {
            readerDiag("REMAP-SNAP ${curlState.current} -> $idx (fresh window count=${fresh.count})")
            curlState.snapTo(idx)
        }
        window = fresh
        pendingNavSection = null
        pendingNavTarget = null
    }

    // ── Eksplisitt navigasjon (TOC, gjenoppretting, font-endring) ──
    // navEpoch økes KUN av eksterne navigasjonshendelser; vanlige sidevendinger
    // endrer den aldri, så CurlState forblir autoritativ under lesing.
    var lastNavHandled by remember { mutableIntStateOf(-1) }
    LaunchedEffect(navEpoch, ui.currentPage, window.count) {
        if (navEpoch == lastNavHandled) return@LaunchedEffect
        val page = ui.currentPage.coerceAtLeast(0).coerceIn(0, (window.count - 1).coerceAtLeast(0))
        val target = PageFlowState.curlIndexOfLocal(window, page)
        if (window.count > 0 && curlState.current != target) {
            readerDiag("NAV-SNAP page=$page target=$target (window=${window.count})")
            curlState.snapTo(target)
        }
        lastNavHandled = navEpoch
    }

    // ── Maks ÉN spesulativ prefetch: neste side, bare når koordinatoren er ledig ──
    LaunchedEffect(chapIdx, ui.currentPage, chapterPages, fontKey, sizeKey) {
        coordinator.cancelSpeculative()
        if (chapterPages <= 0 || !coordinator.isIdle()) return@LaunchedEffect
        val next = ui.currentPage.coerceAtLeast(0) + 1
        if (next < chapterPages) {
            coordinator.requestSpeculative(
                ownerKey = chapIdx,
                pageKey = renderKey(chapIdx, next),
                page = next,
            ) { p -> rendererFor(chapIdx).renderPage(p, renderKey(chapIdx, next)) }
        }
    }

    val pageCurlConfig = rememberPageCurlConfig(
        backPageColor = paperColor,
        backPageContentAlpha = 0.08f,
        shadowColor = Color.Black,
        shadowAlpha = 0.25f,
        shadowRadius = 15.dp,
        tapForwardEnabled = true,
        tapBackwardEnabled = true,
        tapCustomEnabled = true,
        // Kun midt-tapp = kontroller. Kant-tapp er normal krøll.
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

    @Composable
    fun CurlPageContent(pageIdx: Int) {
        val ref = PageFlowState.resolve(window, pageIdx)
        val renderK = ref?.let { renderKey(it.sectionIndex, it.localPageIndex) } ?: ""
        var bitmap by remember(renderK, fontKey, sizeKey) {
            mutableStateOf<Bitmap?>(if (renderK.isEmpty()) null else cache.getSync(renderK))
        }
        LaunchedEffect(renderK, fontKey, sizeKey) {
            if (bitmap == null && ref != null) {
                val bmp = coordinator.renderCurrent(
                    ownerKey = ref.sectionIndex,
                    pageKey = renderK,
                    page = ref.localPageIndex,
                ) { p -> rendererFor(ref.sectionIndex).renderPage(p, renderK) }
                if (bmp != null) bitmap = bmp
            }
            if (ref != null && ref.sectionIndex == chapIdx) {
                cache.getSync(renderK)?.let { lastGood.value = it }
            }
        }
        // Synlig side uten ferdig bitmap → spinner. Krøllklaff/annet uten bitmap → papir.
        val isVisiblePage = pageIdx == curlState.current
        Box(Modifier.fillMaxSize().padding(horizontal = pageHPad, vertical = pageVPad)) {
            val b = bitmap
            if (b != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas {
                        val native = it.nativeCanvas
                        if (!b.isRecycled) {
                            native.drawBitmap(
                                b, null,
                                // Heltalls-destinasjon, nøyaktig målt innholdsdimsjon (aldri
                                // blanding av fullskjerm- og renderer-mål), felles filtrert Paint.
                                android.graphics.RectF(0f, 0f, size.width.roundToInt().toFloat(), size.height.roundToInt().toFloat()),
                                pageBitmapPaint
                            )
                        }
                    }
                }
            } else if (isVisiblePage && ref != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(paperColor)) {
        // lastGood under PageCurl: papir + sist viste side, aldri svart.
        lastGood.value?.let { b ->
            Canvas(Modifier.fillMaxSize().padding(horizontal = pageHPad, vertical = pageVPad)) {
                drawIntoCanvas {
                    val native = it.nativeCanvas
                    if (!b.isRecycled) {
                        native.drawBitmap(
                            b, null,
                            android.graphics.RectF(0f, 0f, size.width.roundToInt().toFloat(), size.height.roundToInt().toFloat()),
                            pageBitmapPaint
                        )
                    }
                }
            }
        }

        if (window.count > 0) {
            PageCurl(
                count = window.count,
                state = curlState,
                config = pageCurlConfig,
                interactionsEnabled = true,
                // Kamigura-forken: den ankommande siden på krøllklaffen.
                backContent = { cur, forward ->
                    CurlPageContent(if (forward) cur + 1 else cur - 1)
                },
                modifier = Modifier.fillMaxSize()
            ) { pageIdx ->
                CurlPageContent(pageIdx)
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

