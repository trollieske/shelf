package com.shelf.reader.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.core.domain.model.LibraryViewType
import com.shelf.reader.data.local.entity.BookEntity
import com.shelf.reader.designsystem.components.*
import com.shelf.reader.designsystem.theme.ShelfColors
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.library.viewmodel.LibraryFilter
import com.shelf.reader.library.viewmodel.LibrarySort
import com.shelf.reader.library.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onBookLongClick: (Long) -> Unit,
    onImportClick: () -> Unit,
    onFtpClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: LibraryViewModel = viewModel(factory = vmFactory ?: defaultLibraryVmFactory())
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var bookActionTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSortFilterSheet by rememberSaveable { mutableStateOf(false) }

    var showCreateShelf by rememberSaveable { mutableStateOf(false) }
    var createShelfName by rememberSaveable { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(search) { vm.setQuery(search) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (search.isBlank()) {
                ExtendedFloatingActionButton(
                    onClick = { createShelfName = ""; showCreateShelf = true },
                    icon = { Icon(Icons.Default.Folder, null) },
                    text = { Text("Ny hylle", fontWeight = FontWeight.SemiBold) },
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color(0xFF0F172A)
                )
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1120))
                .padding(innerPadding)
        ) {
            // Ultra-Streamlined Minimal Header Block
            Surface(
                color = Color(0xFF162032),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    var showSearchField by rememberSaveable { mutableStateOf(false) }

                    // Row 1: Header Title & Minimal Action Icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Bibliotek",
                            style = ShelfTypography.TitleLarge,
                            color = Color(0xFFF8FAFC),
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showSearchField = !showSearchField }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Search, contentDescription = "Søk", tint = if (search.isNotEmpty() || showSearchField) Color(0xFFF59E0B) else Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = {
                                val nextView = when (ui.viewType) {
                                    LibraryViewType.SHELF -> LibraryViewType.GRID
                                    LibraryViewType.GRID -> LibraryViewType.LIST
                                    LibraryViewType.LIST -> LibraryViewType.SHELF
                                }
                                vm.setViewType(nextView)
                            }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    when (ui.viewType) {
                                        LibraryViewType.SHELF -> Icons.Default.ViewAgenda
                                        LibraryViewType.GRID -> Icons.Default.GridView
                                        LibraryViewType.LIST -> Icons.Default.FormatListBulleted
                                    },
                                    contentDescription = "Visning",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { showSortFilterSheet = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Tune, contentDescription = "Sorter & Filter", tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onFtpClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.CloudSync, contentDescription = "FTP & Synk", tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onImportClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Importer", tint = Color(0xFFF8FAFC), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Settings, contentDescription = "Innstillinger", tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Collapsible Search Bar
                    AnimatedVisibility(visible = showSearchField || search.isNotEmpty()) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0x22FFFFFF),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (search.isEmpty()) {
                                            Text("Søk i biblioteket...", color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1)
                                        }
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = search,
                                            onValueChange = { search = it },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White)
                                        )
                                    }
                                    if (search.isNotEmpty()) {
                                        IconButton(onClick = { search = "" }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Tøm", tint = Color(0xFF94A3B8))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Row 2: Subtle Filter Capsules
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterCapsule(label = "Alt (${ui.totalBookCount})", isSelected = ui.filter == LibraryFilter.ALL, onClick = { vm.setFilter(LibraryFilter.ALL) })
                        FilterCapsule(label = "Pågående (${ui.inProgressCount})", isSelected = ui.filter == LibraryFilter.IN_PROGRESS, onClick = { vm.setFilter(LibraryFilter.IN_PROGRESS) })
                        FilterCapsule(label = "Ebøker (${ui.ebookCount})", isSelected = ui.filter == LibraryFilter.EBOOKS, onClick = { vm.setFilter(LibraryFilter.EBOOKS) })
                        FilterCapsule(label = "Lydbøker (${ui.audiobookCount})", isSelected = ui.filter == LibraryFilter.AUDIOBOOKS, onClick = { vm.setFilter(LibraryFilter.AUDIOBOOKS) })
                        FilterCapsule(label = "Favoritter", isSelected = ui.filter == LibraryFilter.FAVORITES, onClick = { vm.setFilter(LibraryFilter.FAVORITES) })
                        FilterCapsule(label = "Fullførte (${ui.finishedCount})", isSelected = ui.filter == LibraryFilter.FINISHED, onClick = { vm.setFilter(LibraryFilter.FINISHED) })
                    }
                }
            }

            val booksToDisplay = remember(ui.flatGridBooks) { ui.flatGridBooks.distinctBy { it.id } }
            val activeAudio by com.shelf.reader.data.repository.ActivePlaybackState.state.collectAsStateWithLifecycle()

            // In-Progress Books Carousel (multiple books support)
            val inProgressBooks = remember(booksToDisplay, activeAudio) {
                booksToDisplay.filter { b ->
                    (activeAudio != null && activeAudio!!.bookId == b.id) || (b.progress > 0f && b.progress < 0.99f)
                }.sortedByDescending { b -> if (activeAudio?.bookId == b.id) 1f else b.progress }
            }

            if (inProgressBooks.isNotEmpty() && search.isBlank()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "PÅGÅENDE BØKER & LYDBØKER (${inProgressBooks.size})",
                        style = ShelfTypography.LabelSmall,
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        inProgressBooks.forEach { b ->
                            val prog = activeAudio?.takeIf { it.bookId == b.id }?.progressPercent ?: b.progress
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF162032),
                                tonalElevation = 4.dp,
                                modifier = Modifier
                                    .width(180.dp)
                                    .clickable { onBookClick(b.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF1E293B),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                if (b.format.isAudio) Icons.Default.Headphones else Icons.Default.MenuBook,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(b.title, style = ShelfTypography.LabelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${(prog * 100).toInt()}% • ${if (b.format.isAudio) "Lyd" else "Ebok"}", style = ShelfTypography.LabelSmall, color = Color(0xFF94A3B8))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (ui.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFD4AF37))
                    }
                } else if (booksToDisplay.isEmpty()) {
                    CleanEmptyState(
                        onImportClick = onImportClick,
                        onFtpClick = onFtpClick
                    )
                } else {
                    when (ui.viewType) {
                        LibraryViewType.SHELF -> {
                            val shelfItems = remember(booksToDisplay) {
                                booksToDisplay.map { b ->
                                    ShelfBookItem(
                                        id = b.id,
                                        title = b.title,
                                        author = b.author,
                                        coverPath = b.coverImagePath,
                                        progressPercent = b.progress,
                                        isCompleted = b.progress >= 0.99f,
                                        isAudiobook = b.format.isAudio,
                                        formatBadge = if (b.format.isAudio) "LYDBOK" else b.format.badge,
                                        cloudSyncStatus = if (b.isDownloaded) "local" else "cloud"
                                    )
                                }
                            }
                            RealisticBookshelfView(
                                books = shelfItems,
                                onBookClick = { onBookClick(it) },
                                onBookLongClick = { id -> bookActionTarget = id },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        LibraryViewType.GRID -> {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 115.dp),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(booksToDisplay, key = { it.id }) { b ->
                                    BookCoverCard(
                                        book = b,
                                        onClick = { onBookClick(b.id) },
                                        onLongClick = { bookActionTarget = b.id }
                                    )
                                }
                            }
                        }
                        LibraryViewType.LIST -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(booksToDisplay, key = { it.id }) { b ->
                                    CleanListBookRow(
                                        book = b,
                                        onClick = { onBookClick(b.id) },
                                        onLongClick = { bookActionTarget = b.id }
                                    )
                                }
                            }
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

            // Sheet: Book Action Options
            bookActionTarget?.let { bookId ->
                val selectedBook = ui.flatListBooks.firstOrNull { it.id == bookId }
                selectedBook?.let { b ->
                    ModalBottomSheet(onDismissRequest = { bookActionTarget = null }) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Text(b.title, style = ShelfTypography.TitleMedium, fontWeight = FontWeight.Bold)
                            Text(b.author, style = ShelfTypography.BodyMedium, color = Color.Gray)
                            Spacer(Modifier.height(16.dp))

                            ListItem(
                                headlineContent = { Text(if (b.dateFinished != null) "Merk som ulest" else "Merk som ferdig") },
                                leadingContent = { Icon(Icons.Default.Check, null) },
                                modifier = Modifier.clickable {
                                    vm.markFinished(b.id)
                                    bookActionTarget = null
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Slett fra bibliotek") },
                                leadingContent = { Icon(Icons.Default.Delete, null, tint = Color(0xFFE53935)) },
                                modifier = Modifier.clickable {
                                    vm.delete(b.id)
                                    bookActionTarget = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterCapsule(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color(0xFFF59E0B) else Color(0x22FFFFFF),
        contentColor = if (isSelected) Color(0xFF0F172A) else Color(0xFFCBD5E1)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ViewTypeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) Color(0xFFF59E0B) else Color.Transparent,
        contentColor = if (isSelected) Color(0xFF0F172A) else Color(0xFF94A3B8)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun CleanListBookRow(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverCard(
                book = book,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = Modifier
                    .width(44.dp)
                    .height(64.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = ShelfTypography.TitleSmall,
                    color = Color(0xFFF7F2EC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    book.author,
                    style = ShelfTypography.BodySmall,
                    color = Color(0xFFC0B2A6),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.progress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(3.dp),
                        color = Color(0xFFD4AF37),
                        trackColor = Color(0x33FFFFFF)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0x33D4AF37)
            ) {
                Text(
                    text = if (book.format.isAudio) "🎧 LYDBOK" else book.format.badge,
                    fontSize = 10.sp,
                    color = Color(0xFFD4AF37),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
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
                shape = CircleShape,
                color = Color(0x22D4AF37),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = Color(0xFFD4AF37),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Biblioteket er tomt",
                style = ShelfTypography.TitleLarge,
                color = Color(0xFFF7F2EC),
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Legg til e-bøker og lydbøker ved å importere filer fra enheten eller synkronisere fra FTP/Seedbox.",
                style = ShelfTypography.BodyMedium,
                color = Color(0xFFC0B2A6),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onFtpClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD4AF37),
                    contentColor = Color(0xFF1E130D)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Synkroniser fra FTP / Seedbox", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(12.dp),
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

object SampleBooks {
    val books: List<com.shelf.reader.designsystem.components.BookVisual> = emptyList()
}
