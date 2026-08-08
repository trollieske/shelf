package com.shelf.reader.ftp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.ftp.client.FtpEntry
import com.shelf.reader.ftp.client.FtpEntryType
import com.shelf.reader.ftp.client.FtpProtocol
import com.shelf.reader.ftp.data.FtpSavedServer
import com.shelf.reader.ftp.viewmodel.FtpUiState
import com.shelf.reader.ftp.viewmodel.FtpViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpScreen(
    serverId: Long = -1L,
    onBack: () -> Unit = {},
    onImport: (() -> Unit)? = null,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: FtpViewModel = viewModel(factory = vmFactory ?: defaultFtpVmFactory())
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        if (serverId > 0) {
            vm.loadServer(serverId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FTP & Fildeling", style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
                    }
                },
                actions = {
                    if (state.server.isNotBlank() || state.isConnected) {
                        IconButton(
                            onClick = {
                                vm.syncCurrentFolderNow(ctx) { count ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (count > 0) "Synkronisering fullført! $count nye fil(er) importert."
                                            else "Ingen nye mediefiler funnet i '${state.currentPath}'."
                                        )
                                    }
                                }
                            },
                            enabled = !state.isLoading
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Synkroniser nå",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (state.server.isNotBlank()) {
                        IconButton(onClick = { showSaveDialog = true }) {
                            Icon(
                                if (state.activeServerId != null) Icons.Default.Edit else Icons.Default.Save,
                                "Lagre server"
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val count = state.selected.size
                        vm.downloadSelected(ctx) { successCount ->
                            scope.launch {
                                snackbarHostState.showSnackbar("Importert $successCount fil(er) til biblioteket")
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Download, null) },
                    text = { Text("Last ned (${state.selected.size})") }
                )
            }
        }
    ) { pad ->
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            if (state.savedServers.isNotEmpty() && !state.isConnected) {
                SavedServersPanel(
                    saved = state.savedServers,
                    activeId = state.activeServerId,
                    onLoad = { vm.loadServer(it) },
                    onConnect = { id -> vm.loadServer(id); vm.connect() },
                    onSyncNow = { id ->
                        vm.syncServerNow(id, ctx) { count ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (count > 0) "Synkronisert! $count nye bøker lagt til i biblioteket."
                                    else "Ingen nye bøker funnet i synk-mappen."
                                )
                            }
                        }
                    },
                    onUpdatePath = { id, newPath ->
                        vm.loadServer(id)
                        vm.updateCurrentPath(newPath)
                        scope.launch { snackbarHostState.showSnackbar("Synk-mappe oppdatert til '$newPath'") }
                    },
                    onDelete = { id ->
                        vm.deleteSaved(id)
                        scope.launch { snackbarHostState.showSnackbar("Server slettet") }
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            ServerCard(
                state = state,
                onServerChange = vm::updateServer,
                onPortChange = { vm.updatePort(it.toIntOrNull() ?: 21) },
                onUsernameChange = vm::updateUsername,
                onPasswordChange = vm::updatePassword,
                onCurrentPathChange = vm::updateCurrentPath,
                onProtocolChange = vm::updateProtocol,
                onPassiveModeChange = vm::updateUsePassiveMode,
                onMaxConcurrencyChange = vm::updateMaxConcurrency,
                onConnect = {
                    if (state.isConnected) vm.disconnect() else vm.connect()
                },
                isLoading = state.isLoading
            )

            if (showSaveDialog) {
                SaveServerDialog(
                    initialName = state.savedServers
                        .firstOrNull { it.id == state.activeServerId }?.name
                        ?: state.server,
                    initialPath = state.currentPath,
                    onDismiss = { showSaveDialog = false },
                    onSave = { name, path ->
                        vm.updateCurrentPath(path)
                        vm.saveCurrentAs(name)
                        showSaveDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Server lagret med synk-mappe '$path'") }
                    }
                )
            }

            if (state.isLoading || state.syncStage != com.shelf.reader.ftp.viewmodel.SyncStage.IDLE || state.downloadProgressText != null) {
                Spacer(Modifier.height(12.dp))
                SyncProgressCard(
                    state = state,
                    onCancel = vm::cancelSync
                )
            }

            Spacer(Modifier.height(16.dp))

            if (state.isConnected) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.FolderSpecial, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Gjeldende mappe:", style = ShelfTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    state.currentPath.ifBlank { "/" },
                                    style = ShelfTypography.TitleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!state.isLoading) {
                            Button(
                                onClick = {
                                    vm.syncCurrentFolderNow(ctx) { count ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (count > 0) "Synkronisert! $count nye mediefiler lagt til."
                                                else "Ingen nye filer funnet i '${state.currentPath}'."
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Sync, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Synkroniser denne mappen",
                                    style = ShelfTypography.LabelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    vm.updateDefaultRemotePath(state.currentPath)
                                    if (state.activeServerId == null) {
                                        vm.saveCurrentAs(state.server)
                                    }
                                    scope.launch { snackbarHostState.showSnackbar("Lagret '${state.currentPath}' som synk-mappe!") }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Sett som synk-mappe",
                                    style = ShelfTypography.LabelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = vm::cancelSync,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Close, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Avbryt synkronisering",
                                    style = ShelfTypography.LabelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                PathBreadcrumb(
                    currentPath = state.currentPath,
                    onNavigateUp = vm::navigateUp
                )

                Spacer(Modifier.height(8.dp))

                if (state.isLoading && state.entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val selectedCount = state.selected.size
                    if (selectedCount > 0) {
                        Text(
                            "$selectedCount valgt",
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    if (state.entries.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Mappen er tom",
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.entries, key = { it.path }) { entry ->
                                EntryRow(
                                    entry = entry,
                                    isSelected = entry.name in state.selected,
                                    onClick = {
                                        when (entry.type) {
                                            FtpEntryType.FOLDER -> vm.navigateTo(entry)
                                            else -> vm.toggleSelect(entry.name)
                                        }
                                    },
                                    onLongClick = { vm.toggleSelect(entry.name) }
                                )
                            }
                        }
                    }
                }
            }

            state.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedServersPanel(
    saved: List<FtpSavedServer>,
    activeId: Long?,
    onLoad: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onSyncNow: (Long) -> Unit,
    onUpdatePath: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    var editingServerPathId by remember { mutableStateOf<Long?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Lagrede servere", style = ShelfTypography.TitleSmall, fontWeight = FontWeight.SemiBold)
        }
        saved.forEach { sv ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (activeId == sv.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                onClick = { onLoad(sv.id) }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            sv.name.ifBlank { sv.server },
                            style = ShelfTypography.BodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${sv.username}@${sv.server}:${sv.port} · ${sv.protocol.displayName}",
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Synk-mappe: ${sv.defaultRemotePath.ifBlank { "/" }}",
                            style = ShelfTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = { editingServerPathId = sv.id }) {
                        Icon(Icons.Default.Edit, "Endre synk-mappe", tint = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = { onSyncNow(sv.id) }) {
                        Icon(Icons.Default.Sync, "Synkroniser nå", tint = MaterialTheme.colorScheme.tertiary)
                    }
                    IconButton(onClick = { onConnect(sv.id) }) {
                        Icon(Icons.AutoMirrored.Filled.Login, "Koble til og bla i mapper", tint = MaterialTheme.colorScheme.primary)
                    }
                    var confirmDelete by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, "Slett", tint = MaterialTheme.colorScheme.error)
                    }
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            confirmButton = {
                                TextButton(onClick = { onDelete(sv.id); confirmDelete = false }) {
                                    Text("Slett")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") }
                            },
                            title = { Text("Slett server?") },
                            text = { Text("Vil du slette «${sv.name.ifBlank { sv.server }}»? Passordet blir fjernet.") }
                        )
                    }
                }
            }
        }
    }

    editingServerPathId?.let { targetId ->
        val sv = saved.firstOrNull { it.id == targetId }
        if (sv != null) {
            var pathText by remember { mutableStateOf(sv.defaultRemotePath) }
            AlertDialog(
                onDismissRequest = { editingServerPathId = null },
                confirmButton = {
                    TextButton(onClick = {
                        onUpdatePath(sv.id, pathText.trim())
                        editingServerPathId = null
                    }) { Text("Lagre") }
                },
                dismissButton = {
                    TextButton(onClick = { editingServerPathId = null }) { Text("Avbryt") }
                },
                title = { Text("Endre synk-mappe") },
                text = {
                    Column {
                        Text("Skriv inn mappen på serveren som skal synkroniseres (f.eks. /Lydbøker):", style = ShelfTypography.BodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pathText,
                            onValueChange = { pathText = it },
                            singleLine = true,
                            label = { Text("Synk-mappe") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveServerDialog(
    initialName: String,
    initialPath: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var path by remember { mutableStateOf(initialPath.ifBlank { "/" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), path.trim()) }) { Text("Lagre") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Avbryt") }
        },
        title = { Text("Lagre server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Oppgi servernavn og synk-mappe:", style = ShelfTypography.BodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Navn") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    label = { Text("Synk-mappe / Startmappe (f.eks. /Lydbøker)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(
    state: FtpUiState,
    onServerChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCurrentPathChange: (String) -> Unit,
    onProtocolChange: (FtpProtocol) -> Unit,
    onPassiveModeChange: (Boolean) -> Unit,
    onMaxConcurrencyChange: (Int) -> Unit,
    onConnect: () -> Unit,
    isLoading: Boolean
) {
    var showPassword by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (state.isConnected) Icons.Default.CloudDone else Icons.Default.Dns,
                    null,
                    tint = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.isConnected) "Tilkoblet: ${state.server}:${state.port}" else "Tilkoble til server",
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            if (!state.isConnected) {
                Text("Protokoll:", style = ShelfTypography.LabelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FtpProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = state.protocol == proto,
                            onClick = { onProtocolChange(proto) },
                            label = { Text(proto.displayName, style = ShelfTypography.LabelSmall) }
                        )
                    }
                }

                if (state.protocol != FtpProtocol.SFTP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Passiv modus (PASV)", style = ShelfTypography.BodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Slå av hvis brannmur blokkerer mappelisting", style = ShelfTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.usePassiveMode,
                            onCheckedChange = onPassiveModeChange
                        )
                    }
                }

                OutlinedTextField(
                    value = state.server,
                    onValueChange = onServerChange,
                    label = { Text("Vert (IP eller domenenavn)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.port.toString(),
                        onValueChange = onPortChange,
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f)
                    )
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = onUsernameChange,
                        label = { Text("Brukernavn") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                }

                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Passord") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.currentPath,
                    onValueChange = onCurrentPathChange,
                    label = { Text("Synk-mappe / Startmappe (f.eks. /Lydbøker)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Samtidige overføringer:", style = ShelfTypography.BodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("${state.maxConcurrency} tråder", style = ShelfTypography.LabelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = state.maxConcurrency.toFloat(),
                        onValueChange = { onMaxConcurrencyChange(it.toInt()) },
                        valueRange = 1f..15f,
                        steps = 13,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Parallelle tråder (standard: 6). Høyere verdier gir betydelig raskere nedlasting av mange små filer.",
                        style = ShelfTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "Synk-mappe: ${state.currentPath}",
                    style = ShelfTypography.BodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Icon(
                    if (state.isConnected) Icons.Default.Close else Icons.AutoMirrored.Filled.Login,
                    null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.isConnected) "Koble fra" else "Koble til og bla i mapper")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PathBreadcrumb(currentPath: String, onNavigateUp: () -> Unit) {
    val segments = currentPath.split('/').filter { it.isNotBlank() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AssistChip(
            onClick = { },
            leadingIcon = { Icon(Icons.Default.Home, null, Modifier.size(14.dp)) },
            label = { Text("/", style = ShelfTypography.LabelSmall) }
        )
        if (currentPath != "/" && currentPath.isNotBlank()) {
            AssistChip(
                onClick = onNavigateUp,
                leadingIcon = { Icon(Icons.Default.ArrowUpward, null, Modifier.size(14.dp)) },
                label = { Text("..", style = ShelfTypography.LabelSmall) }
            )
        }
        segments.forEach { seg ->
            Text("/", style = ShelfTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(
                onClick = { },
                label = { Text(seg, style = ShelfTypography.LabelSmall) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FtpEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, tint) = when (entry.type) {
                FtpEntryType.FOLDER -> Icons.Default.Folder to MaterialTheme.colorScheme.tertiary
                FtpEntryType.LINK -> Icons.Default.Link to MaterialTheme.colorScheme.secondary
                else -> Icons.Default.Description to MaterialTheme.colorScheme.primary
            }
            Icon(
                icon,
                null,
                tint = tint,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = ShelfTypography.BodyMedium,
                    fontWeight = if (entry.type == FtpEntryType.FOLDER) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
                val sizeText = if (entry.type == FtpEntryType.FOLDER) "" else formatSize(entry.sizeBytes)
                Text(
                    buildString {
                        if (sizeText.isNotEmpty()) append(sizeText)
                    },
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            } else if (entry.type != FtpEntryType.FOLDER) {
                Spacer(Modifier.width(22.dp))
            }
            if (entry.type == FtpEntryType.FOLDER) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Åpne mappe",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

@Composable
private fun SyncProgressCard(
    state: FtpUiState,
    onCancel: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (state.syncStage) {
                            com.shelf.reader.ftp.viewmodel.SyncStage.CONNECTING -> "Kobler til FTP-server…"
                            com.shelf.reader.ftp.viewmodel.SyncStage.SCANNING -> "Sammenligner og analyserer filer…"
                            com.shelf.reader.ftp.viewmodel.SyncStage.DOWNLOADING -> "Synkroniserer filer"
                            com.shelf.reader.ftp.viewmodel.SyncStage.COVER_FETCHING -> "Henter bokomslag…"
                            else -> state.downloadProgressText ?: "Synkronisering pågår"
                        },
                        style = ShelfTypography.TitleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.syncStage == com.shelf.reader.ftp.viewmodel.SyncStage.DOWNLOADING && state.activeDownloadsCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${state.activeDownloadsCount} av ${state.maxConcurrency} tråder",
                            style = ShelfTypography.LabelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.totalFilesToSync > 0) {
                val progressFraction = (state.syncedFilesCount.toFloat() / state.totalFilesToSync.toFloat()).coerceIn(0f, 1f)
                val percentInt = (progressFraction * 100).toInt()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.syncedFilesCount} av ${state.totalFilesToSync} filer ($percentInt%)",
                        style = ShelfTypography.BodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (state.bytesPerSec > 0) {
                        Text(
                            text = formatSpeed(state.bytesPerSec),
                            style = ShelfTypography.LabelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val etaText = if (state.estimatedRemainingSec > 60) {
                        "~${state.estimatedRemainingSec / 60}m ${state.estimatedRemainingSec % 60}s igjen"
                    } else if (state.estimatedRemainingSec > 0) {
                        "~${state.estimatedRemainingSec} sekunder igjen"
                    } else "Beregner tid..."

                    Text(
                        text = etaText,
                        style = ShelfTypography.LabelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )

                    Text(
                        text = "${formatBytes(state.transferredBytesTotal)} overført",
                        style = ShelfTypography.LabelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                if (state.activeFileNames.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.activeFileNames.take(2).forEach { name ->
                            Text(
                                text = "⚡ $name",
                                style = ShelfTypography.LabelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (showDetails) "Skjul detaljer ▲" else "Vis detaljer ▼",
                        style = ShelfTypography.LabelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Avbryt", style = ShelfTypography.LabelMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (showDetails) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Hastighet:", formatSpeed(state.bytesPerSec))
                    DetailRow("Overført hittil:", formatBytes(state.transferredBytesTotal))
                    DetailRow("Ferdige filer:", "${state.syncedFilesCount} av ${state.totalFilesToSync}")
                    DetailRow("Ventende filer:", "${(state.totalFilesToSync - state.syncedFilesCount).coerceAtLeast(0)}")
                    DetailRow("Feil / Re-forsøk:", "${state.failedFilesCount} feilet (${state.retryCount} forsøkt på nytt)")
                    DetailRow("Aktive tråder:", "${state.activeDownloadsCount} av max ${state.maxConcurrency}")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = ShelfTypography.LabelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Text(value, style = ShelfTypography.LabelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}

private fun formatSpeed(bytesPerSec: Long): String {
    return "${formatBytes(bytesPerSec)}/s"
}

@Composable
fun defaultFtpVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            val db = ShelfDatabase.getInstance(app)
            FtpViewModel(
                application = app,
                dispatchers = DefaultDispatcherProvider
            )
        }
    }
}
