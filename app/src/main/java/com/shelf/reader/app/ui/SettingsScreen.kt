package com.shelf.reader.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.core.domain.model.DarkModePref
import com.shelf.reader.core.domain.model.LibraryViewType
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.designsystem.theme.ShelfColors
import com.shelf.reader.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

data class SettingsUiState(
    val libraryViewType: LibraryViewType = LibraryViewType.SHELF,
    val darkMode: DarkModePref = DarkModePref.FOLLOW_SYSTEM,
    val dynamicColors: Boolean = false,
    val trueBlack: Boolean = false,
    val readerFontSizeSp: Int = 16,
    val readerTheme: String = "light",
    val audioSpeed: Float = 1.0f,
    val audioSkipBackSec: Int = 10,
    val audioSkipFwdSec: Int = 30,
    val audioFadeOut: Boolean = true,
    val autoPlayNext: Boolean = false,
    val watchLibraryFolder: Boolean = false,
    val ftpSyncEnabled: Boolean = false,
    val ftpWifiOnly: Boolean = true,
    val ftpChargingOnly: Boolean = false,
    val ftpIntervalMinutes: Int = 360,
    val smbSyncEnabled: Boolean = false,
    val smbWifiOnly: Boolean = true,
    val smbChargingOnly: Boolean = false,
    val smbIntervalMinutes: Int = 360,
    val webdavSyncEnabled: Boolean = false,
    val webdavWifiOnly: Boolean = true,
    val webdavChargingOnly: Boolean = false,
    val webdavIntervalMinutes: Int = 360,
    val torrentBackgroundEnabled: Boolean = false,
    val torrentWifiOnly: Boolean = true,
    val torrentChargingOnly: Boolean = false,
    val torrentMinBattery: Int = 20,
    val libraryFormatFilterEnabled: Boolean = true,
    val libraryTabCountsEnabled: Boolean = true,
    val onlineCoverLookup: Boolean = false,
    val handoffPrecision: String = com.shelf.reader.data.local.entity.HandoffPrecisionEntity.SMART.name,
    val handoffToastEnabled: Boolean = true,
    val seenOnboarding: Boolean = false
)

class SettingsViewModel(
    app: Application,
    val prefs: UserPreferencesRepository = UserPreferencesRepository(app),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(app) {

    val state: StateFlow<SettingsUiState> = combine(
        prefs.libraryViewType,
        prefs.darkMode,
        prefs.dynamicColors,
        prefs.trueBlack,
        prefs.readerFontSizeSp,
        prefs.readerTheme,
        prefs.audioSpeed,
        prefs.audioSkipBackSec,
        prefs.audioSkipFwdSec,
        prefs.autoSleepFadeOut,
        prefs.autoPlayNextInSeries,
        prefs.watchLibraryFolder,
        prefs.ftpSyncEnabled,
        prefs.ftpWifiOnly,
        prefs.ftpChargingOnly,
        prefs.ftpIntervalMinutes,
        prefs.smbSyncEnabled,
        prefs.smbWifiOnly,
        prefs.smbChargingOnly,
        prefs.smbIntervalMinutes,
        prefs.webdavSyncEnabled,
        prefs.webdavWifiOnly,
        prefs.webdavChargingOnly,
        prefs.webdavIntervalMinutes,
        prefs.torrentBackgroundEnabled,
        prefs.torrentWifiOnly,
        prefs.torrentChargingOnly,
        prefs.torrentMinBatteryPct,
        prefs.libraryFormatFilterEnabled,
        prefs.libraryTabCountsEnabled,
        prefs.onlineCoverLookup,
        prefs.handoffPrecision,
        prefs.handoffToastEnabled
    ) { a ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            libraryViewType = a[0] as LibraryViewType,
            darkMode = a[1] as DarkModePref,
            dynamicColors = a[2] as Boolean,
            trueBlack = a[3] as Boolean,
            readerFontSizeSp = a[4] as Int,
            readerTheme = a[5] as String,
            audioSpeed = a[6] as Float,
            audioSkipBackSec = a[7] as Int,
            audioSkipFwdSec = a[8] as Int,
            audioFadeOut = a[9] as Boolean,
            autoPlayNext = a[10] as Boolean,
            watchLibraryFolder = a[11] as Boolean,
            ftpSyncEnabled = a[12] as Boolean,
            ftpWifiOnly = a[13] as Boolean,
            ftpChargingOnly = a[14] as Boolean,
            ftpIntervalMinutes = a[15] as Int,
            smbSyncEnabled = a[16] as Boolean,
            smbWifiOnly = a[17] as Boolean,
            smbChargingOnly = a[18] as Boolean,
            smbIntervalMinutes = a[19] as Int,
            webdavSyncEnabled = a[20] as Boolean,
            webdavWifiOnly = a[21] as Boolean,
            webdavChargingOnly = a[22] as Boolean,
            webdavIntervalMinutes = a[23] as Int,
            torrentBackgroundEnabled = a[24] as Boolean,
            torrentWifiOnly = a[25] as Boolean,
            torrentChargingOnly = a[26] as Boolean,
            torrentMinBattery = a[27] as Int,
            libraryFormatFilterEnabled = a[28] as Boolean,
            libraryTabCountsEnabled = a[29] as Boolean,
            onlineCoverLookup = a[30] as Boolean,
            handoffPrecision = a[31] as String,
            handoffToastEnabled = a[32] as Boolean,
            seenOnboarding = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setLibraryViewType(v: LibraryViewType) = viewModelScope.launch(dispatchers.io) {
        prefs.setLibraryViewType(v)
    }

    fun setDarkMode(d: DarkModePref) = viewModelScope.launch(dispatchers.io) {
        prefs.setDarkMode(d)
    }

    fun setDynamicColors(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setDynamicColors(b)
    }

    fun setTrueBlack(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setAmoledBlack(b)
    }

    fun setFontSize(sp: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setReaderFontSizeSp(sp)
    }

    fun setReaderTheme(name: String) = viewModelScope.launch(dispatchers.io) {
        prefs.setReaderTheme(name)
    }

    fun setAudioSpeed(f: Float) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioSpeed(f)
    }

    fun setSkipBack(s: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioSkipBack(s)
    }

    fun setSkipFwd(s: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioSkipFwd(s)
    }

    fun setAudioFadeOut(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioFadeOut(b)
    }

    fun setAutoPlayNext(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setAutoPlayNext(b)
    }

    fun setWatchFolder(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWatchLibraryFolder(b)
    }

    fun setFtpSyncEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpSyncEnabled(b)
    }

    fun setFtpWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpWifiOnly(b)
    }

    fun setFtpChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpChargingOnly(b)
    }

    fun setFtpIntervalMinutes(m: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpIntervalMinutes(m)
    }

    fun setSmbSyncEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbSyncEnabled(b)
    }

    fun setSmbWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbWifiOnly(b)
    }

    fun setSmbChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbChargingOnly(b)
    }

    fun setSmbIntervalMinutes(m: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbIntervalMinutes(m)
    }

    fun setWebdavSyncEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavSyncEnabled(b)
    }

    fun setWebdavWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavWifiOnly(b)
    }

    fun setWebdavChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavChargingOnly(b)
    }

    fun setWebdavIntervalMinutes(m: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavIntervalMinutes(m)
    }

    fun setTorrentBackgroundEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentBackgroundEnabled(b)
    }

    fun setTorrentWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentWifiOnly(b)
    }

    fun setTorrentChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentChargingOnly(b)
    }

    fun setTorrentMinBattery(pct: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentMinBatteryPct(pct)
    }

    fun setLibraryFormatFilter(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setLibraryFormatFilter(b)
    }

    fun setLibraryTabCounts(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setLibraryTabCounts(b)
    }

    fun setOnlineCover(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setOnlineCoverLookup(b)
    }

    fun setHandoffPrecision(name: String) = viewModelScope.launch(dispatchers.io) {
        prefs.setHandoffPrecision(name)
    }

    fun setHandoffToastEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setHandoffToast(b)
    }

    fun clearCache() = viewModelScope.launch(dispatchers.io) {
        val app = getApplication<Application>()
        val cacheDir = app.cacheDir
        runCatching {
            cacheDir.resolve("image_cache").deleteRecursively()
        }
        runCatching {
            cacheDir.listFiles { f ->
                f.name.startsWith("pdf_") ||
                    f.name.startsWith("cbz_") ||
                    f.name.startsWith("tmp_") ||
                    f.name.startsWith("epub_")
            }?.forEach { it.deleteRecursively() }
        }
    }

    fun exportDb(ctx: Context): String? {
        return try {
            val dbFile = ctx.getDatabasePath("shelf.db")
            val outDir = ctx.getExternalFilesDir(null) ?: return null
            val outFile = outDir.resolve("shelf_backup.db")
            FileInputStream(dbFile).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun importDb(ctx: Context, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val dbFile = ctx.getDatabasePath("shelf.db")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

@Composable
private fun defaultSettingsVmFactory(): ViewModelProvider.Factory {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    return viewModelFactory {
        initializer {
            SettingsViewModel(app)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSourcesClick: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = defaultSettingsVmFactory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val importDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        vm.importDb(ctx, uri)
        if (uri != null) {
            scope.launch { snackbarHostState.showSnackbar("Databasen er importert") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Innstillinger",
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            Spacer(Modifier.height(4.dp))
            Card(
                onClick = onSourcesClick,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Kilder & synkronisering",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "FTP, SMB, WebDAV, Torrent, Calibre, OPDS",
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }

            SettingsSection("Utsende") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Text(
                        "Bibliotekvisning",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LibraryViewType.values().forEach { vt ->
                            val isSel = vt == state.libraryViewType
                            FilterChip(
                                selected = isSel,
                                onClick = { vm.setLibraryViewType(vt) },
                                label = {
                                    Text(
                                        when (vt) {
                                            LibraryViewType.SHELF -> "Hylle"
                                            LibraryViewType.GRID -> "Rutenett"
                                            LibraryViewType.LIST -> "Liste"
                                        },
                                        style = ShelfTypography.LabelMedium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        when (vt) {
                                            LibraryViewType.SHELF -> Icons.Default.ViewAgenda
                                            LibraryViewType.GRID -> Icons.Default.GridView
                                            LibraryViewType.LIST -> Icons.AutoMirrored.Filled.ViewList
                                        },
                                        null,
                                        Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Mørk modus",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            DarkModePref.FOLLOW_SYSTEM to "Følg system",
                            DarkModePref.LIGHT to "Av",
                            DarkModePref.DARK to "På"
                        ).forEach { (pref, label) ->
                            val isSel = pref == state.darkMode ||
                                (pref == DarkModePref.DARK && state.darkMode == DarkModePref.TRUE_BLACK)
                            FilterChip(
                                selected = isSel,
                                onClick = { vm.setDarkMode(pref) },
                                label = {
                                    Text(label, style = ShelfTypography.LabelMedium)
                                },
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .weight(1f)
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Svart (AMOLED)",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Ekte svart bakgrunn for AMOLED-skjermer",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.trueBlack,
                            onCheckedChange = { vm.setTrueBlack(it) }
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Dynamiske Material You-farger",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Fargepalett fra bakgrunnsbilde",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.dynamicColors,
                            onCheckedChange = { vm.setDynamicColors(it) }
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Formatfilter i biblioteket",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Vis knapper for EPUB, PDF, lydbøker osv. over listen",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.libraryFormatFilterEnabled,
                            onCheckedChange = { vm.setLibraryFormatFilter(it) }
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Antall bøker i faner",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Vis (N) ved siden av Alle, Ebøker og Lydbøker",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.libraryTabCountsEnabled,
                            onCheckedChange = { vm.setLibraryTabCounts(it) }
                        )
                    }
                }
            }

            SettingsSection("Leser") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Skriftstørrelse",
                            style = ShelfTypography.BodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    "${state.readerFontSizeSp} sp",
                                    style = ShelfTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                    Slider(
                        value = state.readerFontSizeSp.toFloat(),
                        onValueChange = { vm.setFontSize(it.toInt()) },
                        valueRange = 10f..32f,
                        steps = 22
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        "Lesertema",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { vm.setReaderTheme("light") },
                            label = { Text("Lys") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.LightMode,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.readerTheme == "light")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                        AssistChip(
                            onClick = { vm.setReaderTheme("sepia") },
                            label = { Text("Seppia") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.WbSunny,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.readerTheme == "sepia")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                        AssistChip(
                            onClick = { vm.setReaderTheme("dark") },
                            label = { Text("Mørk") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DarkMode,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.readerTheme == "dark")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }

            SettingsSection("Lydbok avspilling") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Text(
                        "Avspillingshastighet",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            val isSel = state.audioSpeed == speed
                            AssistChip(
                                onClick = { vm.setAudioSpeed(speed) },
                                label = {
                                    Text(
                                        "%.2f×".format(speed).replace(",00", "").replace(",0", ""),
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (isSel)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Tilbake-spole",
                            style = ShelfTypography.BodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    "${state.audioSkipBackSec} s",
                                    style = ShelfTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                    Slider(
                        value = state.audioSkipBackSec.toFloat(),
                        onValueChange = { vm.setSkipBack(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 54
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Frem-spole",
                            style = ShelfTypography.BodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    "${state.audioSkipFwdSec} s",
                                    style = ShelfTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                    Slider(
                        value = state.audioSkipFwdSec.toFloat(),
                        onValueChange = { vm.setSkipFwd(it.toInt()) },
                        valueRange = 10f..120f,
                        steps = 109
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Ton ut ved automatisk søvn",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Reduserer volumet gradvis før pause",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.audioFadeOut,
                            onCheckedChange = { vm.setAudioFadeOut(it) }
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Spill neste bok i serie automatisk",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Når siste kapittel er ferdig",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.autoPlayNext,
                            onCheckedChange = { vm.setAutoPlayNext(it) }
                        )
                    }
                }
            }

            SettingsSection("Lagring & synkronisering") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                val path = vm.exportDb(ctx)
                                scope.launch {
                                    if (path != null) {
                                        snackbarHostState.showSnackbar("Eksportert til: $path")
                                    } else {
                                        snackbarHostState.showSnackbar("Eksport feilet")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Eksporter database")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                importDbLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Importer")
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Overvåk bibliotekmappe for nye filer",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Legger bøker til automatisk",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.watchLibraryFolder,
                            onCheckedChange = { vm.setWatchFolder(it) }
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        "Synkronisering",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Velg hvilke kilder som skal synkroniseres automatisk. Alle synkjobber bruker WorkManager for bakgrunnskjøring som er optimalisert for Android.",
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SyncSourceRow(
                        label = "FTP / SFTP",
                        subtitle = "Vanlig filoverføring",
                        icon = Icons.Default.CloudSync,
                        enabled = state.ftpSyncEnabled,
                        onToggleEnabled = {
                            vm.setFtpSyncEnabled(it)
                            runCatching { com.shelf.reader.ftp.worker.FtpSyncWorker.schedule(ctx) }
                        },
                        intervalMinutes = state.ftpIntervalMinutes,
                        onIntervalChange = {
                            vm.setFtpIntervalMinutes(it)
                            runCatching { com.shelf.reader.ftp.worker.FtpSyncWorker.schedule(ctx) }
                        },
                        wifiOnly = state.ftpWifiOnly,
                        onWifiOnlyChange = {
                            vm.setFtpWifiOnly(it)
                            runCatching { com.shelf.reader.ftp.worker.FtpSyncWorker.schedule(ctx) }
                        },
                        chargingOnly = state.ftpChargingOnly,
                        onChargingOnlyChange = { vm.setFtpChargingOnly(it) }
                    )

                    SyncSourceRow(
                        label = "SMB",
                        subtitle = "Windows / NAS / Samba",
                        icon = Icons.Default.Dns,
                        enabled = state.smbSyncEnabled,
                        onToggleEnabled = {
                            vm.setSmbSyncEnabled(it)
                            runCatching { com.shelf.reader.smb.worker.SmbSyncWorker.schedule(ctx) }
                        },
                        intervalMinutes = state.smbIntervalMinutes,
                        onIntervalChange = {
                            vm.setSmbIntervalMinutes(it)
                            runCatching { com.shelf.reader.smb.worker.SmbSyncWorker.schedule(ctx) }
                        },
                        wifiOnly = state.smbWifiOnly,
                        onWifiOnlyChange = {
                            vm.setSmbWifiOnly(it)
                            runCatching { com.shelf.reader.smb.worker.SmbSyncWorker.schedule(ctx) }
                        },
                        chargingOnly = state.smbChargingOnly,
                        onChargingOnlyChange = { vm.setSmbChargingOnly(it) }
                    )

                    SyncSourceRow(
                        label = "WebDAV",
                        subtitle = "Nextcloud / Owncloud",
                        icon = Icons.Default.Cloud,
                        enabled = state.webdavSyncEnabled,
                        onToggleEnabled = {
                            vm.setWebdavSyncEnabled(it)
                            runCatching { com.shelf.reader.webdav.worker.WebdavSyncWorker.schedule(ctx) }
                        },
                        intervalMinutes = state.webdavIntervalMinutes,
                        onIntervalChange = {
                            vm.setWebdavIntervalMinutes(it)
                            runCatching { com.shelf.reader.webdav.worker.WebdavSyncWorker.schedule(ctx) }
                        },
                        wifiOnly = state.webdavWifiOnly,
                        onWifiOnlyChange = {
                            vm.setWebdavWifiOnly(it)
                            runCatching { com.shelf.reader.webdav.worker.WebdavSyncWorker.schedule(ctx) }
                        },
                        chargingOnly = state.webdavChargingOnly,
                        onChargingOnlyChange = { vm.setWebdavChargingOnly(it) }
                    )

                    // Torrent background row
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.SwapHoriz, null,
                                    tint = androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Torrent (bakgrunn)",
                                        style = ShelfTypography.BodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Fortsett nedlasting når appen er lukket",
                                        style = ShelfTypography.BodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = state.torrentBackgroundEnabled,
                                    onCheckedChange = { vm.setTorrentBackgroundEnabled(it) }
                                )
                            }
                            if (state.torrentBackgroundEnabled) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Kun på Wi-Fi", style = ShelfTypography.BodyMedium, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = state.torrentWifiOnly,
                                        onCheckedChange = { vm.setTorrentWifiOnly(it) }
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Kun under lading", style = ShelfTypography.BodyMedium, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = state.torrentChargingOnly,
                                        onCheckedChange = { vm.setTorrentChargingOnly(it) }
                                    )
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Minste batteri",
                                        style = ShelfTypography.BodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${state.torrentMinBattery}%",
                                        style = ShelfTypography.LabelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Slider(
                                    value = state.torrentMinBattery.toFloat(),
                                    onValueChange = { vm.setTorrentMinBattery(it.toInt()) },
                                    valueRange = 0f..100f,
                                    steps = 19,
                                    colors = SliderDefaults.colors(
                                        thumbColor = androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                                        activeTrackColor = androidx.compose.ui.graphics.Color(0xFF8B5CF6).copy(alpha = 0.6f)
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Online cover-oppslag",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Søk etter omslag på internett",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.onlineCoverLookup,
                            onCheckedChange = { vm.setOnlineCover(it) }
                        )
                    }
                }
            }

            SettingsSection("Immersion Handoff (ebok ↔ lydbok)") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    var scanRunning by rememberSaveable { mutableStateOf(false) }
                    var scanCurrent by rememberSaveable { mutableIntStateOf(0) }
                    var scanTotal by rememberSaveable { mutableIntStateOf(0) }
                    var scanLinksCreated by rememberSaveable { mutableIntStateOf(0) }

                    Text(
                        "Presisjon",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val precisionOptions = listOf(
                        com.shelf.reader.data.local.entity.HandoffPrecisionEntity.CHAPTER_ONLY.name to "Kun kapittel",
                        com.shelf.reader.data.local.entity.HandoffPrecisionEntity.SMART.name to "Smart (standard)",
                        com.shelf.reader.data.local.entity.HandoffPrecisionEntity.PERCENT_ONLY.name to "Kun prosent"
                    )
                    var precisionExpanded by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = precisionExpanded,
                        onExpandedChange = { precisionExpanded = it }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            value = precisionOptions.firstOrNull { it.first == state.handoffPrecision }?.second
                                ?: precisionOptions[1].second,
                            onValueChange = { },
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = precisionExpanded)
                            },
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = precisionExpanded,
                            onDismissRequest = { precisionExpanded = false }
                        ) {
                            precisionOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        vm.setHandoffPrecision(key)
                                        precisionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Velg hvor nøyaktig overgang mellom lydbok og ebok skal være. Standard: Smart.",
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Vis melding ved bytte",
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                "Fortsettelsesposisjon (kapittel eller prosent) vises som en kort melding.",
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.handoffToastEnabled,
                            onCheckedChange = { vm.setHandoffToastEnabled(it) }
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        "Koble utgaver i biblioteket",
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Skanner alle ebøker og lydbøker for å koble sammen utgaven av samme verk. Ser på tittel, forfatter, serie og ISBN.",
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scanRunning) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val pct = if (scanTotal > 0) scanCurrent.toFloat() / scanTotal.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "$scanCurrent / $scanTotal · $scanLinksCreated koblinger opprettet",
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val handoffRepo = remember {
                        com.shelf.reader.data.repository.HandoffRepository(
                            com.shelf.reader.data.local.ShelfDatabase.getInstance(ctx.applicationContext as android.app.Application)
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (scanRunning) return@FilledTonalButton
                            scanRunning = true
                            scanCurrent = 0
                            scanTotal = 0
                            scanLinksCreated = 0
                            scope.launch(Dispatchers.IO) {
                                val created = runCatching {
                                    handoffRepo.scanAndLinkLibrary { cur, tot, links ->
                                        scope.launch {
                                            scanCurrent = cur
                                            scanTotal = tot
                                            scanLinksCreated = links
                                        }
                                    }
                                }.getOrDefault(0)
                                withContext(Dispatchers.Main.immediate) {
                                    scanRunning = false
                                    snackbarHostState.showSnackbar(
                                        "Ferdig. $created nye koblinger ble opprettet."
                                    )
                                }
                            }
                        },
                        enabled = !scanRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (scanRunning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.SyncAlt, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (scanRunning) "Skanner biblioteket…" else "Skann nå og koble utgaver"
                        )
                    }
                }
            }

            SettingsSection("Om") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Versjon",
                                style = ShelfTypography.BodyLarge
                            )
                        }
                        Text(
                            "1.0.0",
                            style = ShelfTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Shelf – en privat, reklamefri leser og lydbokspiller",
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    OutlinedButton(
                        onClick = {
                            vm.clearCache()
                            scope.launch {
                                snackbarHostState.showSnackbar("Cache tømt")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tøm cache")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title.uppercase(),
            style = ShelfTypography.LabelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncSourceRow(
    label: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    intervalMinutes: Int,
    onIntervalChange: (Int) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    chargingOnly: Boolean,
    onChargingOnlyChange: (Boolean) -> Unit
) {
    val intervalOptions = listOf(
        15 to "15 min",
        60 to "1 time",
        360 to "6 timer",
        1440 to "24 timer"
    )
    val selectedLabel = intervalOptions.firstOrNull { it.first == intervalMinutes }?.second
        ?: "${intervalMinutes / 60}t"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = ShelfTypography.BodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (enabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Intervall",
                        style = ShelfTypography.BodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .width(140.dp),
                            shape = MaterialTheme.shapes.medium,
                            textStyle = ShelfTypography.LabelMedium
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            intervalOptions.forEach { (mins, text) ->
                                DropdownMenuItem(
                                    text = { Text(text) },
                                    onClick = {
                                        onIntervalChange(mins)
                                        expanded = false
                                    },
                                    leadingIcon = {
                                        if (intervalMinutes == mins) {
                                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Kun på Wi-Fi",
                        style = ShelfTypography.BodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = onWifiOnlyChange
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Kun under lading",
                        style = ShelfTypography.BodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = chargingOnly,
                        onCheckedChange = onChargingOnlyChange
                    )
                }
            }
        }
    }
}
