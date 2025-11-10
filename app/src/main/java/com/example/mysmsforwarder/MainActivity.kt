package com.example.mysmsforwarder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mysmsforwarder.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private var showPermissionDialog by mutableStateOf(false)
    private var deniedPermissions by mutableStateOf<Set<String>>(emptySet())

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            deniedPermissions = permissions.filterValues { !it }.keys
            showPermissionDialog = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = AppDatabase.getDatabase(applicationContext)
        Logger.init(applicationContext)
        Logger.i("MainActivity", "App started")
        createNotificationChannel()

        requestPermissions.launch(
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        setContent {
            MaterialTheme {
                SmsForwarderApp(database)

                if (showPermissionDialog) {
                    PermissionDialog(
                        deniedPermissions = deniedPermissions,
                        onDismiss = { showPermissionDialog = false },
                        onOpenSettings = {
                            openAppSettings()
                            showPermissionDialog = false
                        }
                    )
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "sms_forwarding",
            "SMS Forwarding",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "SMS forwarding notifications"
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

@Composable
fun PermissionDialog(
    deniedPermissions: Set<String>,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val permissionMessages = mapOf(
        Manifest.permission.RECEIVE_SMS to "Receive SMS: to detect incoming messages",
        Manifest.permission.SEND_SMS to "Send SMS: to forward messages",
        Manifest.permission.READ_SMS to "Read SMS: to read message content",
        Manifest.permission.POST_NOTIFICATIONS to "Notifications: to inform you about forwarding"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text("Permissions Required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This app requires the following permissions to function:")

                Spacer(modifier = Modifier.height(8.dp))

                deniedPermissions.forEach { permission ->
                    permissionMessages[permission]?.let { message ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", style = MaterialTheme.typography.bodyMedium)
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Please enable these permissions in settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsForwarderApp(database: AppDatabase) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val filters by database.smsFilterDao().getAllFilters().collectAsState(initial = emptyList())
    val history by database.forwardingHistoryDao().getRecentHistory()
        .collectAsState(initial = emptyList())
    val logs by database.appLogDao().getAllLogs().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My SMS Forwarder") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Add filter")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Filters") },
                    icon = { Icon(Icons.Default.FilterList, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("History") },
                    icon = { Icon(Icons.Default.History, null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Logs") },
                    icon = { Icon(Icons.Default.Description, null) }
                )
            }

            when (selectedTab) {
                0 -> FiltersScreen(filters, database, scope)
                1 -> HistoryScreen(history)
                2 -> LogsScreen(logs, database, scope)
            }
        }

        if (showAddDialog) {
            AddFilterDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { newFilter ->
                    scope.launch {
                        database.smsFilterDao().insertFilter(newFilter)
                        Logger.i("MainActivity", "Filter added: ${newFilter.name}")
                        showAddDialog = false
                    }
                }
            )
        }
    }
}

@Composable
fun FiltersScreen(
    filters: List<SmsFilter>,
    database: AppDatabase,
    scope: kotlinx.coroutines.CoroutineScope
) {
    if (filters.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.FilterList,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No filters configured",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Tap + to add one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filters) { filter ->
                FilterCard(
                    filter = filter,
                    onDelete = {
                        scope.launch {
                            database.smsFilterDao().deleteFilter(filter)
                            Logger.i("FiltersScreen", "Filter deleted: ${filter.name}")
                        }
                    },
                    onToggle = {
                        scope.launch {
                            val newState = !filter.isEnabled
                            database.smsFilterDao().updateFilter(
                                filter.copy(isEnabled = newState)
                            )
                            Logger.i(
                                "FiltersScreen",
                                "Filter ${filter.name} ${if (newState) "enabled" else "disabled"}"
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HistoryScreen(history: List<ForwardingHistory>) {
    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.History,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No history",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history) { entry ->
                HistoryCard(entry)
            }
        }
    }
}

@Composable
fun LogsScreen(
    logs: List<AppLog>,
    database: AppDatabase,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Clear logs button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    scope.launch {
                        database.appLogDao().clearAllLogs()
                        Logger.i("LogsScreen", "All logs cleared")
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear Logs")
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Description,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    LogCard(log)
                }
            }
        }
    }
}

@Composable
fun LogCard(log: AppLog) {
    val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val backgroundColor = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.errorContainer
        "WARNING" -> MaterialTheme.colorScheme.tertiaryContainer
        "INFO" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.onErrorContainer
        "WARNING" -> MaterialTheme.colorScheme.onTertiaryContainer
        "INFO" -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[${log.level}] ${log.tag}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = textColor
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

@Composable
fun FilterCard(
    filter: SmsFilter,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (filter.isEnabled)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (filter.isEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (filter.isEnabled)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = filter.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = filter.isEnabled,
                    onCheckedChange = { onToggle() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "From: ${filter.senderNumber.ifEmpty { filter.senderName }}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "To: ${filter.forwardToNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete")
            }
        }
    }
}

@Composable
fun HistoryCard(entry: ForwardingHistory) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.success)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = entry.filterName,
                    style = MaterialTheme.typography.titleSmall
                )
                Icon(
                    if (entry.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = if (entry.success)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${entry.originalSender} → ${entry.forwardedTo}",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = entry.messagePreview.take(50) + if (entry.messagePreview.length > 50) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = dateFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AddFilterDialog(
    onDismiss: () -> Unit,
    onAdd: (SmsFilter) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var senderNumber by remember { mutableStateOf("") }
    var senderName by remember { mutableStateOf("") }
    var forwardTo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Add, null) },
        title = { Text("New Filter") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Filter name") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = senderNumber,
                    onValueChange = { senderNumber = it },
                    label = { Text("Sender number") },
                    placeholder = { Text("+33612345678") },
                    leadingIcon = { Icon(Icons.Default.Phone, null) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("OR", style = MaterialTheme.typography.labelSmall)

                OutlinedTextField(
                    value = senderName,
                    onValueChange = { senderName = it },
                    label = { Text("Sender name") },
                    placeholder = { Text("e.g. BANK, UBER") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = forwardTo,
                    onValueChange = { forwardTo = it },
                    label = { Text("Forward to *") },
                    placeholder = { Text("+33698765432") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotEmpty() && forwardTo.isNotEmpty() &&
                        (senderNumber.isNotEmpty() || senderName.isNotEmpty())
                    ) {
                        onAdd(
                            SmsFilter(
                                name = name,
                                senderNumber = senderNumber,
                                senderName = senderName,
                                forwardToNumber = forwardTo
                            )
                        )
                    }
                },
                enabled = name.isNotEmpty() && forwardTo.isNotEmpty() &&
                        (senderNumber.isNotEmpty() || senderName.isNotEmpty())
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}