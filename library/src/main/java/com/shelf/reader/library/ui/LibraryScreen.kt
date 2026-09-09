package com.shelf.reader.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.designsystem.components.BookCoverCard
import com.shelf.reader.designsystem.components.BookVisual
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.library.gamification.ui.ReadingRhythmViewModel
import com.shelf.reader.library.gamification.ui.SaluteEffectOverlay
import com.shelf.reader.library.gamification.ui.SaluteTier
import com.shelf.reader.library.gamification.ui.play
import com.shelf.reader.library.gamification.ui.rememberSaluteEffectState
import com.shelf.reader.library.viewmodel.LibraryMode
import com.shelf.reader.library.viewmodel.LibraryViewModel
import com.shelf.reader.library.viewmodel.ResumeItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Omarchy-inspirert bibliotek-krom: Tokyo Night-base, én aksent, ingen hevede kort.
// Delte tokens ligger i designsystem (OmarchyColors) slik at Innstillinger/Kilder matcher.
private val LibBg = com.shelf.reader.designsystem.theme.OmarchyColors.Bg
private val LibHeaderBg = com.shelf.reader.designsystem.theme.OmarchyColors.HeaderBg
private val LibHairline = com.shelf.reader.designsystem.theme.OmarchyColors.Hairline
private val LibAccent = com.shelf.reader.designsystem.theme.OmarchyColors.Accent
private val LibDim = com.shelf.reader.designsystem.theme.OmarchyColors.Dim
private val LibFg = com.shelf.reader.designsystem.theme.OmarchyColors.Fg
private val LibFgBright = com.shelf.reader.designsystem.theme.OmarchyColors.FgBright
private val LibPanel = com.shelf.reader.designsystem.theme.OmarchyColors.Panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    mode: LibraryMode,
    onBookClick: (Long) -> Unit,
    onBookLongClick: (Long) -> Unit,
    onImportClick: () -> Unit,
    onFtpClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onNavVisibilityChange: (Boolean) -> Unit = {},
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: LibraryViewModel = viewModel(factory = vmFactory ?: defaultLibraryVmFactory()),
    rhythmVmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    rhythmVm: ReadingRhythmViewModel = viewModel(factory = rhythmVmFactory ?: defaultRhythmVmFactory())
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { vm.setMode(mode) }
    var search by rememberSaveable { mutableStateOf("") }
    var showCreateShelf by rememberSaveable { mutableStateOf(false) }
    var createShelfName by rememberSaveable { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(search) { vm.setQuery(search) }

    val saluteState = rememberSaluteEffectState()
    var activeSaluteTier by remember { mutableStateOf(SaluteTier.GOLD) }
    var hasAutoTriggeredDebug by rememberSaveable { mutableStateOf(false) }

    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefs = remember(appCtx) {
        com.shelf.reader.data.prefs.UserPreferencesRepository(appCtx)
    }
    val celebrationsEnabled by prefs.rhythmCelebrationsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val debugAutoTriggerEnabled by prefs.rhythmDebugAutoTriggerOnLogin.collectAsStateWithLifecycle(initialValue = true)

    LaunchedEffect(rhythmVm, celebrationsEnabled, saluteState) {
        rhythmVm.tierEvents.collect { tier ->
            if (celebrationsEnabled) {
                activeSaluteTier = tier
                val duration = if (tier == SaluteTier.GOLD) 5200 else 4500
                saluteState.play(tier, duration)
            }
        }
    }

    val ctxPackage = androidx.compose.ui.platform.LocalContext.current
    val isDebuggable = remember(ctxPackage) {
        (ctxPackage.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    LaunchedEffect(isDebuggable, debugAutoTriggerEnabled, hasAutoTriggeredDebug, saluteState) {
        if (isDebuggable && debugAutoTriggerEnabled && !hasAutoTriggeredDebug) {
            hasAutoTriggeredDebug = true
            delay(900)
            activeSaluteTier = SaluteTier.GOLD
            saluteState.play(SaluteTier.GOLD, 6000)
        }
    }

    // Rulle-retning fra rutenettet: ned = skjul nav, opp = vis nav.
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, onNavVisibilityChange) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                when {
                    index > lastIndex || (index == lastIndex && offset > lastOffset) ->
                        onNavVisibilityChange(false)
                    index < lastIndex || (index == lastIndex && offset < lastOffset) ->
                        onNavVisibilityChange(true)
                }
                lastIndex = index
                lastOffset = offset
            }
    }
    val booksToDisplay = remember(ui.flatGridBooks) { ui.flatGridBooks.distinctBy { it.id } }
    LaunchedEffect(booksToDisplay.isEmpty(), search, mode) {
        if (booksToDisplay.isEmpty() || search.isNotBlank()) onNavVisibilityChange(true)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
            containerColor = LibBg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (search.isBlank()) {
                    ExtendedFloatingActionButton(
                        onClick = { createShelfName = ""; showCreateShelf = true },
                        icon = { Icon(Icons.Default.Folder, null, tint = LibAccent) },
                        text = { Text("Ny hylle", color = LibFg, fontWeight = FontWeight.Medium) },
                        containerColor = LibPanel,
                        contentColor = LibFg,
                        shape = RoundedCornerShape(4.dp),
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                    )
                }
            }
        ) { innerPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LibBg)
                    .padding(innerPadding)
            ) {
                // Flat toppband: tittel + minimalt ikoner. Ingen heving, bare hårlinje.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(LibHeaderBg)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    var showSearchField by rememberSaveable { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            when (mode) {
                                LibraryMode.Books -> "Bøker"
                                LibraryMode.Audio -> "Lydbøker"
                            },
                            style = ShelfTypography.TitleLarge,
                            color = LibFgBright,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showSearchField = !showSearchField }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Search, contentDescription = "Søk", tint = if (search.isNotEmpty() || showSearchField) LibAccent else LibDim, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onFtpClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.CloudSync, contentDescription = "FTP & Synk", tint = LibDim, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onImportClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Importer", tint = LibFgBright, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Settings, contentDescription = "Innstillinger", tint = LibDim, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Søk: skjult ikon som ekspanderer. Skarpe hjørner, ingen chip-rad.
                    AnimatedVisibility(visible = showSearchField || search.isNotEmpty()) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = LibPanel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = LibAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (search.isEmpty()) {
                                            Text("Søk i biblioteket...", color = LibDim, fontSize = 12.sp, maxLines = 1)
                                        }
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = search,
                                            onValueChange = { search = it },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = LibFgBright)
                                        )
                                    }
                                    if (search.isNotEmpty()) {
                                        IconButton(onClick = { search = "" }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Tøm", tint = LibDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LibHairline)
                )

                // Fortsett-linje: waybar/tmux-stil. Monospace, lavkontrast, full bredde, ikke kort.
                val resume = when (mode) {
                    LibraryMode.Books -> ui.resumeEbook
                    LibraryMode.Audio -> ui.resumeAudio
                }
                resume?.let { item ->
                    ResumeStrip(item = item, onClick = { onBookClick(item.bookId) })
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (ui.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LibDim)
                        }
                    } else if (booksToDisplay.isEmpty()) {
                        CleanEmptyState(
                            onImportClick = onImportClick,
                            onFtpClick = onFtpClick
                        )
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 115.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(booksToDisplay, key = { it.id }) { b ->
                                BookCoverCard(
                                    book = b.copy(leanDegrees = 0f),
                                    onClick = { onBookClick(b.id) },
                                    onLongClick = { onBookLongClick(b.id) },
                                    showFormatBadge = false,
                                    showInlineProgress = false,
                                    underCoverContent = {
                                        if (b.progress > 0f) {
                                            ThinProgressBar(progress = b.progress)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Dialog: Create New Shelf
                if (showCreateShelf) {
                    AlertDialog(
                        onDismissRequest = { showCreateShelf = false },
                        title = { Text("Ny hylle") },
                        text = {
                            OutlinedTextField(
                                value = createShelfName,
                                onValueChange = { createShelfName = it },
                                label = { Text("Navn på samling") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val name = createShelfName.trim()
                                    if (name.isNotBlank()) {
                                        vm.createShelf(name)
                                        scope.launch { snackbarHostState.showSnackbar("Hylle opprettet: $name") }
                                        showCreateShelf = false
                                    }
                                }
                            ) { Text("Opprett") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCreateShelf = false }) { Text("Avbryt") }
                        }
                    )
                }
            }
        }
        SaluteEffectOverlay(
            state = saluteState,
            tier = activeSaluteTier,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Fortsett-linje: liten monospace, lav kontrast, full bredde. 2px visuell vekt maks. */
@Composable
private fun ResumeStrip(item: ResumeItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▸",
            color = LibAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = "${item.title} · ${item.detail}",
            style = ShelfTypography.LabelSmall.copy(fontFamily = FontFamily.Monospace),
            color = LibDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 2px fremdriftslinje under omslaget — aksent på LibHairline-spor. Ingen etiketter. */
@Composable
private fun ThinProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .height(2.dp)
            .background(LibHairline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0.01f, 1f))
                .background(LibAccent)
        )
    }
}

@Composable
private fun CleanEmptyState(
    onImportClick: () -> Unit,
    onFtpClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = LibPanel,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = LibDim,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Biblioteket er tomt",
                style = ShelfTypography.TitleLarge,
                color = LibFgBright,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Legg til e-bøker og lydbøker ved å importere filer fra enheten eller synkronisere fra FTP/Seedbox.",
                style = ShelfTypography.BodyMedium,
                color = LibDim,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onFtpClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LibPanel,
                    contentColor = LibFg
                ),
                shape = RoundedCornerShape(4.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Synkroniser fra FTP / Seedbox", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LibFg),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Importer fra enhet")
            }
        }
    }
}

private fun defaultLibraryVmFactory(): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = (this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
        val db = com.shelf.reader.data.local.ShelfDatabase.getInstance(app)
        val prefs = com.shelf.reader.data.prefs.UserPreferencesRepository(app)
        LibraryViewModel(app, prefs)
    }
}

private fun defaultRhythmVmFactory(): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = (this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
        val provider = app.applicationContext as com.shelf.reader.core.di.AppDependenciesProvider
        ReadingRhythmViewModel(
            rhythmDao = com.shelf.reader.data.local.ShelfDatabase.getInstance(app).readingRhythmDao(),
            engine = provider.readingTracker,
            preferences = com.shelf.reader.data.prefs.UserPreferencesRepository(app)
        )
    }
}

object SampleBooks {
    val books: List<BookVisual> = emptyList()
}
