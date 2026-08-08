package com.shelf.reader.webdav.ui

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
import com.shelf.reader.webdav.client.WebdavEntry
import com.shelf.reader.webdav.client.WebdavEntryType
import com.shelf.reader.webdav.viewmodel.WebdavUiState
import com.shelf.reader.webdav.viewmodel.WebdavViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebdavScreen(
    serverId: Long = -1L,
    onBack: () -> Unit = {},
    onImport: (() -> Unit)? = null,
    vm: WebdavViewModel = viewModel(factory = defaultWebdavVmFactory())
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        if (serverId > 0) {
            vm.loadServer(serverId)
            runCatching { vm.connect() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV (Nextcloud o.l.)", style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
                    }
                },
                actions = {
                    if (state.baseUrl.isNotBlank()) {
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
                SavedWebdavServersPanel(
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

            WebdavServerCard(
                state = state,
                showSecret = showSecret,
                onToggleSecretVisibility = { showSecret = !showSecret },
                onDisplayNameChange = vm::updateDisplayName,
                onBaseUrlChange = vm::updateBaseUrl,
                onBasePathChange = vm::updateBasePath,
                onUsernameChange = vm::updateUsername,
                onPasswordChange = vm::updatePassword,
                onBearerTokenChange = vm::updateBearerToken,
                onAuthTypeChange = vm::updateAuthType,
                onTrustAllCertsChange = vm::updateTrustAllCerts,
                onConnect = { if (state.isConnected) vm.disconnect() else vm.connect() },
                isLoading = state.isLoading
            )

            if (showSaveDialog) {
                SaveServerDialog(
                    initialName = state.savedServers.firstOrNull { it.id == state.activeServerId }?.displayName
                        ?: state.displayName.ifBlank { state.baseUrl },
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
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text("Mappen er tom", style = ShelfTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.entries, key = { it.path }) { entry ->
                            WebdavEntryRow(
                                entry = entry,
                                isSelected = entry.path in state.selected,
                                downloadProgress = state.downloading[entry.path],
                                onClick = {
                                    when (entry.type) {
                                        WebdavEntryType.FOLDER -> vm.navigateTo(entry)
                                        WebdavEntryType.FILE -> vm.toggleSelected(entry.path)
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
private fun SavedWebdavServersPanel(
    saved: List<com.shelf.reader.webdav.data.WebdavSavedServer>,
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
                            s.displayName.ifBlank { s.baseUrl },
                            style = ShelfTypography.BodyMedium,
                            fontWeight = if (activeId == s.id) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            "${s.authType} · ${s.username}",
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = { onConnect(s.id) }) {
                            Icon(Icons.Default.PowerSettingsNew, "Koble til", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onLoad(s.id) }) { Icon(Icons.Default.Edit, "Rediger") }
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
private fun WebdavServerCard(
    state: WebdavUiState,
    showSecret: Boolean,
    onToggleSecretVisibility: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onBasePathChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onAuthTypeChange: (String) -> Unit,
    onTrustAllCertsChange: (Boolean) -> Unit,
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
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base-URL (f.eks. https://cloud.example.com)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Autentisering", Modifier.weight(1f), style = ShelfTypography.BodyMedium)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = state.authType,
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().width(180.dp), singleLine = true
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("BASIC", "BEARER", "DIGEST", "NONE").forEach { v ->
                            DropdownMenuItem(text = { Text(v) }, onClick = {
                                onAuthTypeChange(v); expanded = false
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state.authType != "NONE") {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text("Brukernavn") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = state.authType != "BEARER" || state.username.isNotBlank()
                )
                Spacer(Modifier.height(8.dp))
            }
            when (state.authType) {
                "BEARER" -> {
                    OutlinedTextField(
                        value = state.bearerToken,
                        onValueChange = onBearerTokenChange,
                        label = { Text("Bearer Token (App-passord i Nextcloud)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onToggleSecretVisibility) {
                                Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        }
                    )
                }
                "DIGEST", "BASIC" -> {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Passord") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onToggleSecretVisibility) {
                                Icon(if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.basePath,
                onValueChange = onBasePathChange,
                label = { Text("DAV-sti (f.eks. /remote.php/dav/files/)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stol på alle SSL-sertifikater", Modifier.weight(1f), style = ShelfTypography.BodyMedium)
                Switch(checked = state.trustAllCertificates, onCheckedChange = onTrustAllCertsChange)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = !isLoading && state.baseUrl.isNotBlank(),
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
private fun WebdavEntryRow(
    entry: WebdavEntry,
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
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (entry.type) {
                    WebdavEntryType.FOLDER -> Icons.Default.Folder
                    WebdavEntryType.FILE -> Icons.Default.Description
                    else -> Icons.Default.HelpOutline
                },
                contentDescription = null,
                tint = when (entry.type) {
                    WebdavEntryType.FOLDER -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = ShelfTypography.BodyMedium, fontWeight = FontWeight.Medium)
                if (downloadProgress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                } else if (entry.type == WebdavEntryType.FILE) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatSize(entry.sizeBytes),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isSelected) Icon(Icons.Default.CheckCircle, "Valgt", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PathBreadcrumb(currentPath: String, onNavigateUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = onNavigateUp, enabled = currentPath != "/") { Icon(Icons.Default.ArrowUpward, "Opp") }
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
                value = name, onValueChange = { name = it },
                label = { Text("Navn på server") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Lagre") } },
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
fun defaultWebdavVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            WebdavViewModel(app)
        }
    }
}
