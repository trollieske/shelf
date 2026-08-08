package com.shelf.reader.smb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.smb.client.SmbEntry
import com.shelf.reader.smb.client.SmbEntryType
import com.shelf.reader.smb.viewmodel.SmbUiState
import com.shelf.reader.smb.viewmodel.SmbViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbScreen(
    serverId: Long = -1L,
    onBack: () -> Unit = {},
    onImport: (() -> Unit)? = null,
    vm: SmbViewModel = viewModel(factory = defaultSmbVmFactory())
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        if (serverId > 0) {
            vm.loadServer(serverId)
            runCatching { vm.connect() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMB / Windows-nettverk", style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
                    }
                },
                actions = {
                    if (state.host.isNotBlank()) {
                        IconButton(onClick = { showSaveDialog = true }) {
                            Icon(if (state.activeServerId != null) Icons.Default.Edit else Icons.Default.Save, "Lagre server")
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
                        vm.downloadAndImportSelected()
                        scope.launch { snackbarHostState.showSnackbar("Laster ned ${state.selected.size} fil(er)…") }
                    },
                    icon = { Icon(Icons.Default.Download, null) },
                    text = { Text("Last ned (${state.selected.size})") }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (state.savedServers.isNotEmpty() && !state.isConnected) {
                SavedSmbServersPanel(
                    saved = state.savedServers,
                    activeId = state.activeServerId,
                    onLoad = { vm.loadServer(it) },
                    onConnect = { vm.loadServer(it); vm.connect() },
                    onDelete = { id ->
                        vm.deleteSaved(id)
                        scope.launch { snackbarHostState.showSnackbar("Server slettet") }
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            SmbServerCard(
                state = state,
                showPassword = showPassword,
                onTogglePasswordVisibility = { showPassword = !showPassword },
                onDisplayNameChange = vm::updateDisplayName,
                onHostChange = vm::updateHost,
                onPortChange = { vm.updatePort(it.toIntOrNull() ?: 445) },
                onShareNameChange = vm::updateShareName,
                onDomainChange = vm::updateDomain,
                onUsernameChange = vm::updateUsername,
                onPasswordChange = vm::updatePassword,
                onSmbVersionChange = vm::updateSmbVersion,
                onEnableEncryptionChange = vm::updateEnableEncryption,
                onConnect = { if (state.isConnected) vm.disconnect() else vm.connect() },
                isLoading = state.isLoading
            )

            if (showSaveDialog) {
                SaveServerDialog(
                    initialName = state.savedServers.firstOrNull { it.id == state.activeServerId }?.displayName
                        ?: state.displayName.ifBlank { state.host },
                    onDismiss = { showSaveDialog = false },
                    onSave = { name ->
                        vm.saveCurrentAs(name)
                        showSaveDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Lagret") }
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            if (state.isConnected) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PathBreadcrumb(currentPath = state.currentPath, onNavigateUp = vm::navigateUp)
                    TextButton(onClick = {
                        vm.downloadAndImportCurrentFolder()
                        scope.launch { snackbarHostState.showSnackbar("Synkroniserer mappe…") }
                    }) { Text("Synk mappe") }
                }

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

                if (state.isLoading && state.entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text("Mappen er tom", style = ShelfTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.entries, key = { it.path }) { entry ->
                            SmbEntryRow(
                                entry = entry,
                                isSelected = entry.path in state.selected,
                                downloadProgress = state.downloading[entry.path],
                                onClick = {
                                    when (entry.type) {
                                        SmbEntryType.FOLDER -> vm.navigateTo(entry)
                                        SmbEntryType.FILE -> vm.toggleSelected(entry.path)
                                        else -> {}
                                    }
                                },
                                onLongClick = { vm.toggleSelected(entry.path) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedSmbServersPanel(
    saved: List<com.shelf.reader.smb.data.SmbSavedServer>,
    activeId: Long?,
    onLoad: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Lagrede servere", style = ShelfTypography.TitleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            saved.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.displayName.ifBlank { "${s.host}/${s.shareName}" },
                            style = ShelfTypography.BodyMedium,
                            fontWeight = if (activeId == s.id) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            "${s.host}:${s.port} · ${s.shareName}",
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = { onConnect(s.id) }) {
                            Icon(Icons.Default.PowerSettingsNew, "Koble til", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onLoad(s.id) }) {
                            Icon(Icons.Default.Edit, "Rediger")
                        }
                        IconButton(onClick = { onDelete(s.id) }) {
                            Icon(Icons.Default.Delete, "Slett", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmbServerCard(
    state: SmbUiState,
    showPassword: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onShareNameChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSmbVersionChange: (String) -> Unit,
    onEnableEncryptionChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    isLoading: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Serveroppkobling", style = ShelfTypography.TitleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Visningsnavn (valgfritt)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = onHostChange,
                    label = { Text("Vert / IP") },
                    singleLine = true,
                    modifier = Modifier.weight(3f)
                )
                OutlinedTextField(
                    value = state.port.toString(),
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.shareName,
                onValueChange = onShareNameChange,
                label = { Text("Navn på delt ressurs (share)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.domain,
                onValueChange = onDomainChange,
                label = { Text("Domene (valgfritt, f.eks. WORKGROUP)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text("Brukernavn") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Passord") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SMB-versjon", Modifier.weight(1f), style = ShelfTypography.BodyMedium)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = state.smbVersion,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().width(140.dp),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("AUTO", "SMB1", "SMB2", "SMB3").forEach { v ->
                            DropdownMenuItem(text = { Text(v) }, onClick = {
                                onSmbVersionChange(v); expanded = false
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SMB 3-kryptering", Modifier.weight(1f), style = ShelfTypography.BodyMedium)
                Switch(checked = state.enableEncryption, onCheckedChange = onEnableEncryptionChange)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = !isLoading && state.host.isNotBlank() && state.shareName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isConnected) "Koble fra" else "Koble til")
            }
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.error!!, color = MaterialTheme.colorScheme.error, style = ShelfTypography.BodySmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmbEntryRow(
    entry: SmbEntry,
    isSelected: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 2.dp else 0.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (entry.type) {
                    SmbEntryType.FOLDER -> Icons.Default.Folder
                    SmbEntryType.FILE -> Icons.Default.Description
                    else -> Icons.Default.HelpOutline
                },
                contentDescription = null,
                tint = when (entry.type) {
                    SmbEntryType.FOLDER -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = ShelfTypography.BodyMedium, fontWeight = FontWeight.Medium)
                if (downloadProgress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                } else if (entry.type == SmbEntryType.FILE) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatSize(entry.sizeBytes),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, "Valgt", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PathBreadcrumb(currentPath: String, onNavigateUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = onNavigateUp, enabled = currentPath != "/") {
            Icon(Icons.Default.ArrowUpward, "Opp")
        }
        Text(
            if (currentPath.isBlank()) "/" else currentPath,
            style = ShelfTypography.BodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun SaveServerDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lagre server") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Navn på server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Lagre") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

@Composable
fun defaultSmbVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            SmbViewModel(app)
        }
    }
}
