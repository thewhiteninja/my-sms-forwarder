package com.example.mysmsforwarder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mysmsforwarder.data.AppDatabase
import com.example.mysmsforwarder.data.ForwardingHistory
import com.example.mysmsforwarder.data.SmsFilter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = AppDatabase.getDatabase(applicationContext)
        createNotificationChannel()

        requestPermissions.launch(arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        ))

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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
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
    var selectedTab by remember { mutableStateOf(0) }
    val filters by database.smsFilterDao().getAllFilters().collectAsState(initial = emptyList())
    val history by database.forwardingHistoryDao().getRecentHistory().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Forwarder") },
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
            }

            when (selectedTab) {
                0 -> FiltersScreen(filters, database, scope)
                1 -> HistoryScreen(history)
            }
        }

        if (showAddDialog) {
            AddFilterDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { newFilter ->
                    scope.launch {
                        database.smsFilterDao().insertFilter(newFilter)
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
                        }
                    },
                    onToggle = {
                        scope.launch {
                            database.smsFilterDao().updateFilter(
                                filter.copy(isEnabled = !filter.isEnabled)
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
                        (senderNumber.isNotEmpty() || senderName.isNotEmpty())) {
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