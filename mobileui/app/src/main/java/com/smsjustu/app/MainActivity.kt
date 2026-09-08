package com.smsjustu.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smsjustu.app.ui.theme.SmsJustuTheme
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidProviderSettings

enum class WorkerFilter { ALL, ACTIVE, REVOKED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsJustuTheme {
                AdminApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApp() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var adminToken by remember { mutableStateOf(settings.adminToken) }
    var workers by remember { mutableStateOf<List<Worker>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(adminToken.isBlank()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<Worker?>(null) }
    var logTarget by remember { mutableStateOf<Worker?>(null) }
    var workerFilter by remember { mutableStateOf(WorkerFilter.ALL) }
    var backgroundSyncEnabled by remember { mutableStateOf(settings.backgroundSyncEnabled) }
    var pullEnabled by remember { mutableStateOf(settings.pullEnabled) }
    var workerId by remember { mutableStateOf(settings.workerId) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification is best-effort; service runs either way */ }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* if denied, sending just fails per-message and logs Undelivered */ }

    fun setBackgroundSync(enabled: Boolean) {
        backgroundSyncEnabled = enabled
        settings.backgroundSyncEnabled = enabled
    }

    fun setPullEnabled(enabled: Boolean) {
        pullEnabled = enabled
        settings.pullEnabled = enabled
        val serviceIntent = Intent(context, SyncService::class.java)
        if (enabled && adminToken.isNotBlank()) {
            EventLog.clear(context)
            EventLog.markStarted(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    fun refresh() {
        if (adminToken.isBlank()) {
            showSettingsDialog = true
            return
        }
        scope.launch {
            isLoading = true
            try {
                workers = AdminApiClient(baseUrl, adminToken).listWorkers()
                EventLog.recordPull(context, workers.size)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Failed to load workers")
                EventLog.add(context, EventType.ERROR, "Pull failed: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        EventLog.markStarted(context)
        if (adminToken.isNotBlank()) refresh()
        if (settings.pullEnabled && adminToken.isNotBlank()) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("smsJustu") },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showLogDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Activity log")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (adminToken.isBlank()) {
                    showSettingsDialog = true
                } else {
                    showCreateDialog = true
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Create worker")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (adminToken.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        if (pullEnabled) "Pull: On" else "Pull: Off",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (pullEnabled)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = workerFilter == WorkerFilter.ALL,
                        onClick = { workerFilter = WorkerFilter.ALL },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = workerFilter == WorkerFilter.ACTIVE,
                        onClick = { workerFilter = WorkerFilter.ACTIVE },
                        label = { Text("Active") }
                    )
                    FilterChip(
                        selected = workerFilter == WorkerFilter.REVOKED,
                        onClick = { workerFilter = WorkerFilter.REVOKED },
                        label = { Text("Revoked") }
                    )
                }
            }
            when {
                adminToken.isBlank() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "This app manages worker devices through the Admin API. " +
                                "Set the server URL and admin token in Settings to begin.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showSettingsDialog = true }) {
                            Text("Open Settings")
                        }
                    }
                }
                isLoading && workers.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                    }
                }
                workers.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("No workers yet. Tap + to register one.")
                    }
                }
                else -> {
                    val filteredWorkers = workers.filter {
                        when (workerFilter) {
                            WorkerFilter.ALL -> true
                            WorkerFilter.ACTIVE -> it.revokedAt == null
                            WorkerFilter.REVOKED -> it.revokedAt != null
                        }
                    }
                    if (filteredWorkers.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("No workers match this filter.")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            items(filteredWorkers, key = { it.id }) { worker ->
                                WorkerCard(
                                    worker = worker,
                                    onRevoke = { revokeTarget = worker },
                                    onShowLog = { logTarget = worker }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            initialBaseUrl = baseUrl,
            backgroundSyncEnabled = backgroundSyncEnabled,
            onToggleBackgroundSync = { setBackgroundSync(!backgroundSyncEnabled) },
            pullEnabled = pullEnabled,
            onTogglePull = { setPullEnabled(!pullEnabled) },
            workers = workers,
            initialWorkerId = workerId,
            onDismiss = { showSettingsDialog = false },
            onSave = { newBaseUrl, newToken, newWorkerId ->
                baseUrl = newBaseUrl
                adminToken = newToken
                workerId = newWorkerId
                settings.baseUrl = newBaseUrl
                settings.adminToken = newToken
                settings.workerId = newWorkerId
                if (newWorkerId != null &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.SEND_SMS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                }
                showSettingsDialog = false
                refresh()
            }
        )
    }

    if (showCreateDialog) {
        CreateWorkerDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, phone, isPublic ->
                scope.launch {
                    try {
                        val result = AdminApiClient(baseUrl, adminToken)
                            .createWorker(name, phone, isPublic)
                        showCreateDialog = false
                        EventLog.add(context, EventType.CREATE, "Created ${result.name} (${result.phone})", result.id)
                        snackbarHostState.showSnackbar("Created ${result.name}")
                        refresh()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Failed to create worker")
                        EventLog.add(context, EventType.ERROR, "Create failed: ${e.message}")
                    }
                }
            }
        )
    }

    revokeTarget?.let { worker ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke worker?") },
            text = { Text("\"${worker.name}\" (${worker.phone}) will no longer be able to authenticate or be selected as a sender.") },
            confirmButton = {
                TextButton(onClick = {
                    revokeTarget = null
                    scope.launch {
                        try {
                            AdminApiClient(baseUrl, adminToken).revokeWorker(worker.id)
                            EventLog.add(context, EventType.REVOKE, "Revoked ${worker.name} (${worker.phone})", worker.id)
                            refresh()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(e.message ?: "Failed to revoke worker")
                            EventLog.add(context, EventType.ERROR, "Revoke failed: ${e.message}", worker.id)
                        }
                    }
                }) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showLogDialog) {
        ActivityLogDialog(
            onDismiss = { showLogDialog = false },
            onClear = { EventLog.clear(context) }
        )
    }

    logTarget?.let { worker ->
        WorkerLogDialog(worker = worker, onDismiss = { logTarget = null })
    }
}

@Composable
fun ActivityLogDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    val events = EventLog.state.value
    val stats = EventLog.sessionStats()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activity log") },
        text = {
            Column {
                Text(
                    "Started: " + (EventLog.startedAt.value?.let { Formatting.humanDate(it) } ?: "—"),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Pulled: ${EventLog.lastPullCount.value ?: "—"}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Sent: ${stats.sent}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Undelivered: ${stats.undelivered}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stats.undelivered > 0)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (events.isEmpty()) {
                    Text("No activity yet.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(events) { event -> LogEventRow(event) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text("Clear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun WorkerLogDialog(worker: Worker, onDismiss: () -> Unit) {
    val events = EventLog.eventsFor(worker.id)
    // Successful sends are routine and pile up fast - a count says enough.
    // Everything else (create/revoke, and especially undelivered/error) is
    // rare or worth investigating, so those stay itemized.
    val sentCount = events.count { it.type == EventType.SENT }
    val notableEvents = events.filter { it.type != EventType.SENT }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${worker.name} · log") },
        text = {
            Column {
                Text(worker.phone, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Sent: $sentCount", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                if (notableEvents.isEmpty()) {
                    Text("No other activity logged for this worker yet.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(notableEvents) { event -> LogEventRow(event) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun LogEventRow(event: LogEvent) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            event.type.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (event.type == EventType.ERROR || event.type == EventType.UNDELIVERED)
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
        Text(Formatting.humanDate(event.timestamp), style = MaterialTheme.typography.labelSmall)
        Text(event.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun WorkerCard(worker: Worker, onRevoke: () -> Unit, onShowLog: () -> Unit) {
    val revoked = worker.revokedAt != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(worker.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (revoked) "Revoked" else if (worker.isPublic) "Public" else "Private",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(worker.phone, style = MaterialTheme.typography.bodyMedium)
            Text("id: ${worker.id}", style = MaterialTheme.typography.bodySmall)
            worker.createdAt?.let {
                Text("created: ${Formatting.humanDate(it)}", style = MaterialTheme.typography.bodySmall)
            }
            worker.revokedAt?.let {
                Text("revoked: ${Formatting.humanDate(it)}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onShowLog) { Text("Log") }
                if (!revoked) {
                    TextButton(onClick = onRevoke) { Text("Revoke") }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    initialBaseUrl: String,
    backgroundSyncEnabled: Boolean,
    onToggleBackgroundSync: () -> Unit,
    pullEnabled: Boolean,
    onTogglePull: () -> Unit,
    workers: List<Worker>,
    initialWorkerId: Long?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?) -> Unit
) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var selectedWorkerId by remember { mutableStateOf(initialWorkerId) }
    var workerMenuExpanded by remember { mutableStateOf(false) }
    // Only workers a message could actually be queued against - matches the
    // 'from' constraint POST /sms enforces server-side (active + public).
    val eligibleWorkers = workers.filter { it.revokedAt == null && it.isPublic }
    val selectedWorker = eligibleWorkers.find { it.id == selectedWorkerId }
    // Never prefilled with the stored token - write-only. Saving anything,
    // even just a new server URL, requires re-entering it (same value or a
    // new one) since there's no way to tell "left blank" apart from "clear it".
    var token by remember { mutableStateOf("") }
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin API settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("X-Admin-Token") },
                    placeholder = { Text("Required to save (write-only)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pull")
                        Text(
                            "Poll the server for worker status",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = pullEnabled,
                        onCheckedChange = { onTogglePull() }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Run in background")
                        Text(
                            "Auto-starts on boot, restarts if killed or crashed",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = backgroundSyncEnabled,
                        onCheckedChange = { onToggleBackgroundSync() }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Send as worker", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "This device's own SIM must actually be that worker's number - " +
                        "there's no way to fake the sender on a real SMS",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { workerMenuExpanded = true }
                    ) {
                        Text(selectedWorker?.let { "${it.name} (${it.phone})" } ?: "Not configured")
                    }
                    DropdownMenu(
                        expanded = workerMenuExpanded,
                        onDismissRequest = { workerMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Not configured") },
                            onClick = {
                                selectedWorkerId = null
                                workerMenuExpanded = false
                            }
                        )
                        eligibleWorkers.forEach { worker ->
                            DropdownMenuItem(
                                text = { Text("${worker.name} (${worker.phone})") },
                                onClick = {
                                    selectedWorkerId = worker.id
                                    workerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                if (pullEnabled && !ignoringBatteryOptimizations) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            context.startActivity(
                                Intent(AndroidProviderSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                            ignoringBatteryOptimizations =
                                powerManager.isIgnoringBatteryOptimizations(context.packageName)
                        }
                    ) { Text("Exempt from battery optimization") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(baseUrl.trim(), token.trim(), selectedWorkerId) },
                enabled = baseUrl.isNotBlank() && token.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateWorkerDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, phone: String, isPublic: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }

    val canSubmit = name.isNotBlank() && phone.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register worker") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone (e.g. +15551234567)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Public (selectable by every customer)")
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onCreate(name.trim(), phone.trim(), isPublic) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

