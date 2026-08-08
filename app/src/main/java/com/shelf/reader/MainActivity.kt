package com.shelf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.shelf.reader.designsystem.theme.ShelfColors
import com.shelf.reader.app.ShelfDestinations
import com.shelf.reader.core.domain.model.DarkModePref
import com.shelf.reader.core.net.CalibreContentServerClient
import com.shelf.reader.core.net.DiscoveredSourceCandidate
import com.shelf.reader.core.net.LanSourceDiscovery
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.designsystem.theme.ShelfTheme
import com.shelf.reader.library.ui.LibraryScreen
import com.shelf.reader.library.ui.SampleBooks
import com.shelf.reader.reader.ui.ReaderScreen
import com.shelf.reader.player.ui.PlayerScreen
import com.shelf.reader.ftp.ui.FtpScreen
import com.shelf.reader.smb.ui.SmbScreen
import com.shelf.reader.webdav.ui.WebdavScreen
import com.shelf.reader.torrent.ui.TorrentScreen
import com.shelf.reader.app.BookDetailsScreen
import com.shelf.reader.app.ImportScreen
import com.shelf.reader.app.OnboardingScreen
import com.shelf.reader.app.ui.SettingsScreen
import com.shelf.reader.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var prefs: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = UserPreferencesRepository(this)
        setContent {
            val darkMode by prefs.darkMode.collectAsStateWithLifecycle(initialValue = DarkModePref.FOLLOW_SYSTEM)
            val dynamicColors by prefs.dynamicColors.collectAsStateWithLifecycle(initialValue = false)
            val trueBlack by prefs.trueBlack.collectAsStateWithLifecycle(initialValue = false)
            val useDarkTheme = when (darkMode) {
                DarkModePref.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                DarkModePref.LIGHT -> false
                DarkModePref.DARK -> true
                DarkModePref.TRUE_BLACK -> true
            }
            ShelfTheme(
                darkTheme = useDarkTheme,
                dynamicColor = dynamicColors,
                trueBlack = trueBlack
            ) {
                val targetRoute = intent?.getStringExtra("target_route")
                ShelfRoot(prefs = prefs, initialRoute = targetRoute)
            }
        }
    }
}

private sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    object Library : BottomNavItem(ShelfDestinations.Library.route, "Bibliotek", Icons.Default.AutoStories)
    object Sources : BottomNavItem(ShelfDestinations.Sources.route, "Kilder", Icons.Filled.Storage)
    object Settings : BottomNavItem(ShelfDestinations.Settings.route, "Innstillinger", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfRoot(prefs: UserPreferencesRepository, initialRoute: String? = null) {
    val navController = rememberNavController()
    val hasSeenOnboardingState by prefs.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = null)

    if (hasSeenOnboardingState == null) {
        Surface(color = androidx.compose.ui.graphics.Color(0xFF1E130D), modifier = Modifier.fillMaxSize()) {}
        return
    }

    val defaultStart = if (hasSeenOnboardingState == true) ShelfDestinations.Library.route else ShelfDestinations.Onboarding.route
    val startDest = initialRoute ?: defaultStart
    val items = listOf(BottomNavItem.Library, BottomNavItem.Sources, BottomNavItem.Settings)
    val showBottomRoutes = setOf(
        ShelfDestinations.Library.route,
        ShelfDestinations.Sources.route,
        ShelfDestinations.Settings.route
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val activeAudio by com.shelf.reader.data.repository.ActivePlaybackState.state.collectAsStateWithLifecycle()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val isPlayerScreen = currentDestination?.route?.startsWith("player") == true

            Column {
                if (activeAudio != null && !isPlayerScreen) {
                    val active = activeAudio!!
                    Surface(
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        color = Color(0xFF162032),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(ShelfDestinations.Player.routeFor(active.bookId))
                            }
                    ) {
                        Column {
                            LinearProgressIndicator(
                                progress = { active.progressPercent },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = Color(0xFFF59E0B),
                                trackColor = Color(0x33FFFFFF)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Headphones, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        active.title.ifBlank { "Lydbok" },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subLabel = if (active.sleepTimerRemainingMs > 0L) {
                                        val m = (active.sleepTimerRemainingMs / 60_000L).toInt().coerceAtLeast(1)
                                        "${active.author} • ⏱️ ${m}m igjen"
                                    } else {
                                        active.author.ifBlank { "Spiller av" }
                                    }
                                    Text(
                                        subLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = {
                                    com.shelf.reader.data.repository.ActivePlaybackState.clear()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Lukk", tint = Color(0xFF94A3B8))
                                }
                            }
                        }
                    }
                }

                if (currentDestination?.route in showBottomRoutes) {
                    NavigationBar(
                        tonalElevation = 4.dp,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        items.forEach { item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDest,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(ShelfDestinations.Library.route) {
                LibraryScreen(
                    onBookClick = { id -> navController.navigate(ShelfDestinations.BookDetails.routeFor(id)) },
                    onBookLongClick = {},
                    onImportClick = { navController.navigate(ShelfDestinations.Import.route) },
                    onFtpClick = { navController.navigate(ShelfDestinations.Sources.route) },
                    onSettingsClick = { navController.navigate(ShelfDestinations.Settings.route) }
                )
            }
            composable(ShelfDestinations.Sources.route) {
                SourcesOverviewScreen(
                    onBack = { navController.popBackStack() },
                    onFtpClick = { navController.navigate(ShelfDestinations.Ftp.route) },
                    onSmbClick = { navController.navigate(ShelfDestinations.Smb.route) },
                    onWebdavClick = { navController.navigate(ShelfDestinations.Webdav.route) },
                    onTorrentClick = { navController.navigate(ShelfDestinations.Torrent.route) },
                    onImportClick = { navController.navigate(ShelfDestinations.Import.route) },
                    onImportProgressClick = { navController.navigate(ShelfDestinations.ImportProgress.route) }
                )
            }
            composable(ShelfDestinations.Ftp.route) {
                FtpScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(ShelfDestinations.Smb.route) {
                SmbScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(ShelfDestinations.Webdav.route) {
                WebdavScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(ShelfDestinations.Torrent.route) {
                TorrentScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(ShelfDestinations.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = ShelfDestinations.Player.route,
                arguments = listOf(androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val bookId = backStack.arguments?.getLong("bookId") ?: 0L
                PlayerScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = ShelfDestinations.Reader.route,
                arguments = listOf(
                    androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType },
                    androidx.navigation.navArgument("positionPercent") {
                        type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null
                    }
                )
            ) { backStack ->
                val bookId = backStack.arguments?.getLong("bookId") ?: 0L
                val positionPercentStr = backStack.arguments?.getString("positionPercent")
                val initialPosition = positionPercentStr?.toFloatOrNull()?.coerceIn(0f, 1f)
                ReaderScreen(
                    bookId = bookId,
                    initialPositionPercent = initialPosition,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = ShelfDestinations.BookDetails.route,
                arguments = listOf(androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val bookId = backStack.arguments?.getLong("bookId") ?: 0L
                BookDetailsScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { id -> navController.navigate(ShelfDestinations.Reader.routeFor(id)) },
                    onOpenPlayer = { id -> navController.navigate(ShelfDestinations.Player.routeFor(id)) },
                    onOpenBookmark = { bId, posPct ->
                        navController.navigate(ShelfDestinations.Reader.routeFor(bId, posPct))
                    },
                    onDeleted = { navController.popBackStack() }
                )
            }
            composable(ShelfDestinations.Import.route) {
                ImportScreen(onBack = { navController.popBackStack() })
            }
            composable(ShelfDestinations.ImportProgress.route) {
                ImportProgressScreen(onBack = { navController.popBackStack() })
            }
            composable(ShelfDestinations.Onboarding.route) {
                OnboardingScreen(onDone = { name ->
                    navController.navigate(ShelfDestinations.Library.route) {
                        popUpTo(0) { inclusive = true }
                    }
                })
            }
            composable(
                route = ShelfDestinations.FtpServer.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                FtpScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(
                route = ShelfDestinations.SmbServer.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                SmbScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(
                route = ShelfDestinations.WebdavServer.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                WebdavScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesOverviewScreen(
    onBack: () -> Unit,
    onFtpClick: () -> Unit,
    onSmbClick: () -> Unit,
    onWebdavClick: () -> Unit,
    onTorrentClick: () -> Unit,
    onImportClick: () -> Unit,
    onImportProgressClick: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kilder & synkronisering", style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {}
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Koble biblioteket ditt til",
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Importer bøker fra eksterne kilder. FTP, SMB og WebDAV støtter automatisk synk.",
                style = ShelfTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            val cardModifier = Modifier.fillMaxWidth()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "FTP / SFTP",
                    subtitle = "Vanlig filoverføring",
                    icon = Icons.Default.CloudSync,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onFtpClick
                )
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "SMB",
                    subtitle = "Windows / NAS",
                    icon = Icons.Default.Dns,
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = onSmbClick
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "WebDAV",
                    subtitle = "Nextcloud / Owncloud",
                    icon = Icons.Default.Cloud,
                    tint = MaterialTheme.colorScheme.secondary,
                    onClick = onWebdavClick
                )
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "Torrent",
                    subtitle = "Peer-to-peer",
                    icon = Icons.Default.SwapHoriz,
                    tint = androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                    onClick = onTorrentClick
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "Calibre",
                    subtitle = "Innholdstjener / OPDS",
                    icon = Icons.Default.LocalLibrary,
                    tint = androidx.compose.ui.graphics.Color(0xFFF97316),
                    onClick = {
                        android.widget.Toast.makeText(ctx, "Skann nettverket ditt eller legg inn Calibre-URL manuelt nedenfor", android.widget.Toast.LENGTH_LONG).show()
                    }
                )
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "OPDS-katalog",
                    subtitle = "Standard bokkataloger",
                    icon = Icons.Default.MenuBook,
                    tint = androidx.compose.ui.graphics.Color(0xFF10B981),
                    onClick = {
                        android.widget.Toast.makeText(ctx, "Velg en kjent katalog nedenfor, eller lim inn din egen OPDS-URL", android.widget.Toast.LENGTH_LONG).show()
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            LanDiscoverySection()

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "Verktøy",
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "Importer",
                    subtitle = "Fra filer / mapper",
                    icon = Icons.Default.FileUpload,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onImportClick
                )
                SourceCard(
                    modifier = Modifier.weight(1f),
                    title = "Nedlastinger",
                    subtitle = "Status og feil",
                    icon = Icons.Default.DownloadDone,
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = onImportProgressClick
                )
            }

            Spacer(Modifier.height(20.dp))
            WellKnownCatalogsSection()

            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Tips", fontWeight = FontWeight.SemiBold, style = ShelfTypography.BodyLarge)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Alle nedlastede bøker lastes automatisk inn i biblioteket ditt hvis tillegget er støttet (EPUB, PDF, MP3, M4B, etc.)",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(title, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = ShelfTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LanDiscoverySection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("Trykk for å finne FTP / SMB / Calibre / WebDAV-tjenere på ditt hjemmenettverk") }
    val discovered = remember { mutableStateListOf<DiscoveredSourceCandidate>() }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Oppdag kilder på nettverket ditt",
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    progressText,
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            AssistChip(
                onClick = {
                    if (scanning) return@AssistChip
                    discovered.clear()
                    scanning = true
                    progressText = "Skanner undernettet… dette tar 10–40 sekunder."
                    scope.launch(Dispatchers.IO) {
                        val discovery = LanSourceDiscovery(ctx)
                        val seen = HashSet<String>()
                        try {
                            discovery.runScan().collect { cand ->
                                val key = cand.host + ":" + cand.port
                                if (seen.add(key)) {
                                    withContext(Dispatchers.Main.immediate) {
                                        discovered.add(cand)
                                        progressText = "Fant ${discovered.size} mulige kilde(r)… fortsetter scanning."
                                    }
                                }
                            }
                        } catch (_: Throwable) { } finally {
                            withContext(Dispatchers.Main.immediate) {
                                scanning = false
                                progressText = if (discovered.isEmpty()) {
                                    "Ingen kilder funnet. Sjekk at du er på samme WiFi som serverne dine, eller legg dem inn manuelt via kortene over."
                                } else {
                                    "Skanning ferdig. ${discovered.size} mulige kilde(r) funnet – klikk for å åpne/legge til."
                                }
                            }
                        }
                    }
                },
                enabled = !scanning,
                label = { Text(if (scanning) "Skanner…" else "Skann nå") },
                leadingIcon = {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.WifiTethering, null)
                    }
                }
            )
        }

        if (discovered.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    discovered.forEachIndexed { idx, cand ->
                        key(cand.host + cand.port + idx) {
                            SimpleListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        val label = cand.label + " på " + cand.host + ":" + cand.port
                                        android.widget.Toast.makeText(ctx, "$label – forhåndsvisning: ${cand.url}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                headline = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(cand.label, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        AssistChip(
                                            onClick = {},
                                            label = { Text("${cand.confidencePct}% sannsynlig") },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                            )
                                        )
                                    }
                                },
                                supporting = {
                                    Text(cand.url, style = ShelfTypography.BodySmall)
                                },
                                leadingIcon = {
                                    val tint = when (cand.type) {
                                        com.shelf.reader.core.net.DiscoveredSourceType.FTP -> MaterialTheme.colorScheme.primary
                                        com.shelf.reader.core.net.DiscoveredSourceType.SMB -> MaterialTheme.colorScheme.tertiary
                                        com.shelf.reader.core.net.DiscoveredSourceType.WEBDAV -> MaterialTheme.colorScheme.secondary
                                        com.shelf.reader.core.net.DiscoveredSourceType.CALIBRE -> androidx.compose.ui.graphics.Color(0xFFF97316)
                                        com.shelf.reader.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Icon(
                                        when (cand.type) {
                                            com.shelf.reader.core.net.DiscoveredSourceType.FTP -> Icons.Default.CloudSync
                                            com.shelf.reader.core.net.DiscoveredSourceType.SMB -> Icons.Default.Dns
                                            com.shelf.reader.core.net.DiscoveredSourceType.WEBDAV -> Icons.Default.Cloud
                                            com.shelf.reader.core.net.DiscoveredSourceType.CALIBRE -> Icons.Default.LocalLibrary
                                            com.shelf.reader.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> Icons.Default.Public
                                        },
                                        null,
                                        tint = tint
                                    )
                                },
                                trailingIcon = {
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class CatalogSuggestion(
    val title: String,
    val url: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: androidx.compose.ui.graphics.Color
)

@Composable
private fun WellKnownCatalogsSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val catalogs = remember {
        listOf(
            CatalogSuggestion("Standard Ebooks", "https://standardebooks.org/opds", "Høykvalitet, fritt og offentlig", Icons.Default.AutoStories, androidx.compose.ui.graphics.Color(0xFF0EA5E9)),
            CatalogSuggestion("Feedbooks", "https://www.feedbooks.com/catalog.atom", "Ny og klassisk litteratur", Icons.Default.MenuBook, androidx.compose.ui.graphics.Color(0xFFEF4444)),
            CatalogSuggestion("Project Gutenberg", "https://www.gutenberg.org/ebooks/opds", "Offentlig eiendom, 70 000+ bøker", Icons.Default.LibraryBooks, androidx.compose.ui.graphics.Color(0xFF8B5CF6)),
            CatalogSuggestion("LibriVox (lydbøker)", "https://librivox.org/api/feed/audiobooks/?format=opds", "Fritt lydbøker, frivillige", Icons.Default.Audiotrack, androidx.compose.ui.graphics.Color(0xFF10B981))
        )
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Kjente OPDS-kataloger",
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Klikk for å bla i offentlige og fritt tilgjengelige kataloger – importer direkte til biblioteket ditt.",
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            catalogs.forEach { c ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        android.widget.Toast.makeText(
                            ctx,
                            "OPDS: ${c.title} – ${c.url}\n(Funksjonalitet for blaing i katalog implementeres når kjernefunksjonene bekreftet fungerer)",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = c.tint.copy(alpha = 0.12f)
                        ) {
                            Icon(
                                c.icon,
                                null,
                                Modifier.padding(8.dp).size(28.dp),
                                tint = c.tint
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                c.subtitle,
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                c.url,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleListItem(
    modifier: Modifier = Modifier,
    headline: @Composable () -> Unit,
    supporting: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            headline()
            supporting?.let {
                Spacer(Modifier.height(2.dp))
                it()
            }
        }
        trailingIcon?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportProgressScreen(onBack: () -> Unit) {
    val navContext = androidx.compose.ui.platform.LocalContext.current
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nedlastinger & import", style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Tilbake")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            val db = com.shelf.reader.data.local.ShelfDatabase.getInstance(navContext)
            val downloadTasks by db.downloadTaskDao().observeAll()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val syncHistory by db.syncHistoryDao().observeAll()
                .collectAsStateWithLifecycle(initialValue = emptyList())

            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("Nedlastinger") })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Synk-historikk") })
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTabIndex) {
                0 -> {
                    if (downloadTasks.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.DownloadDone,
                                    null,
                                    Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Ingen nedlastinger ennå",
                                    style = ShelfTypography.TitleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(downloadTasks) { task ->
                                val statusColor = when (task.status) {
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.RUNNING -> MaterialTheme.colorScheme.primary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED -> MaterialTheme.colorScheme.secondary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.FAILED -> MaterialTheme.colorScheme.error
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                val sourceLabel = if (task.serverId != null) {
                                    when {
                                        task.remotePath.contains("smb", true) || task.serverId != null && task.localPath?.contains("smb") == true -> "Synk"
                                        else -> "Synk"
                                    }
                                } else "Import"
                                Card {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(task.remoteName, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(sourceLabel) },
                                                colors = AssistChipDefaults.assistChipColors()
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(task.status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                                colors = AssistChipDefaults.assistChipColors(labelColor = statusColor)
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        if (task.sizeBytes > 0) {
                                    val pct = task.downloadedBytes.toFloat() / task.sizeBytes
                                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(5.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${(pct * 100).toInt()}% · ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.sizeBytes)}",
                                        style = ShelfTypography.BodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (task.status == com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED) {
                                            Text(
                                                formatBytes(task.downloadedBytes),
                                                style = ShelfTypography.BodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (task.errorMessage != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "Feil: ${task.errorMessage}",
                                                style = ShelfTypography.BodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (syncHistory.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.History,
                                    null,
                                    Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Ingen synk-historikk",
                                    style = ShelfTypography.TitleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Når auto-synk kjører vises resultatet her.",
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(syncHistory) { h ->
                                val statusColor = when (h.status) {
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.RUNNING -> MaterialTheme.colorScheme.primary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED -> MaterialTheme.colorScheme.secondary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.FAILED -> MaterialTheme.colorScheme.error
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                val dateText = java.text.SimpleDateFormat("dd. MMM yyyy HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(h.startedAt))
                                val durationMs = (h.completedAt ?: System.currentTimeMillis()) - h.startedAt
                                val durationSec = (durationMs / 1000).toInt()
                                val durText = if (h.completedAt != null) {
                                    if (durationSec < 60) "${durationSec}s" else "${durationSec / 60}m ${durationSec % 60}s"
                                } else "—"
                                Card {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "Server ${h.serverId}",
                                                    style = ShelfTypography.BodyLarge,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    "$dateText · Varighet: $durText",
                                                    style = ShelfTypography.BodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(h.status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                                colors = AssistChipDefaults.assistChipColors(labelColor = statusColor)
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            SyncStatChip("Funnet", "${h.filesFound}")
                                            SyncStatChip("Nye", "${h.filesNew}")
                                            SyncStatChip("Lastet ned", "${h.filesDownloaded}", MaterialTheme.colorScheme.primary)
                                            SyncStatChip("Feilet", "${h.filesFailed}", MaterialTheme.colorScheme.error)
                                        }
                                        if (h.errorMessage != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "Feil: ${h.errorMessage}",
                                                style = ShelfTypography.BodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatChip(label: String, value: String, color: androidx.compose.ui.graphics.Color = LocalContentColor.current) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = ShelfTypography.TitleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = ShelfTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

object SampleData {
    val demoBooks = SampleBooks.books
}
