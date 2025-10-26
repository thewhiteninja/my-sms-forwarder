package com.example.mysmsforwarder

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            val deniedPermissions = permissions.filterValues { !it }.keys
            ShowPermissionDialog(deniedPermissions)
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun ShowPermissionDialog(deniedPermissions: Set<String>) {
        val message = buildString {
            append("L'application nécessite les permissions suivantes pour fonctionner :\n\n")
            deniedPermissions.forEach { permission ->
                when (permission) {
                    Manifest.permission.RECEIVE_SMS -> append("• Réception de SMS : pour détecter les messages entrants\n")
                    Manifest.permission.SEND_SMS -> append("• Envoi de SMS : pour transférer les messages\n")
                    Manifest.permission.READ_SMS -> append("• Lecture de SMS : pour lire le contenu des messages\n")
                    Manifest.permission.POST_NOTIFICATIONS -> append("• Notifications : pour vous informer des transferts\n")
                }
            }
            append("\nVeuillez activer ces permissions dans les paramètres.")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions requises")
            .setMessage(message)
            .setPositiveButton("Paramètres") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Plus tard", null)
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
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
            }
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "sms_forwarding",
            "Transfert SMS",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications de transfert SMS"
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsForwarderApp(database: AppDatabase) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val filters by database.smsFilterDao().getAllFilters().collectAsState(initial = emptyList())
    val history by database.forwardingHistoryDao().getRecentHistory().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfert SMS") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Ajouter filtre")
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
                    text = { Text("Filtres") },
                    icon = { Icon(Icons.Default.FilterList, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Historique") },
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
                    "Aucun filtre configuré",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Appuyez sur + pour ajouter",
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
                    "Aucun historique",
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
                    text = "De: ${filter.senderNumber.ifEmpty { filter.senderName }}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Vers: ${filter.forwardToNumber}",
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
                Text("Supprimer")
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
        title = { Text("Nouveau filtre") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom du filtre") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = senderNumber,
                    onValueChange = { senderNumber = it },
                    label = { Text("Numéro émetteur") },
                    placeholder = { Text("+33612345678") },
                    leadingIcon = { Icon(Icons.Default.Phone, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text("OU", style = MaterialTheme.typography.labelSmall)
                
                OutlinedTextField(
                    value = senderName,
                    onValueChange = { senderName = it },
                    label = { Text("Nom émetteur") },
                    placeholder = { Text("Ex: BANK, UBER") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = forwardTo,
                    onValueChange = { forwardTo = it },
                    label = { Text("Transférer vers *") },
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
                Text("Ajouter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}