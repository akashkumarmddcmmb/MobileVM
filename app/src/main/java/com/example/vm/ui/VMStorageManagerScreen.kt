package com.example.vm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vm.storage.*
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMStorageManagerScreen(
    viewModel: VMViewModel,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val vmConfigs by viewModel.vmConfigurations.collectAsState()
    val disks by viewModel.allDisks.collectAsState()
    val snapshots by viewModel.allSnapshots.collectAsState()
    val backups by viewModel.allBackups.collectAsState()
    val storageLogs by viewModel.storageLogs.collectAsState()

    var showCreateDiskDialog by remember { mutableStateOf(false) }
    var selectedDiskForResize by remember { mutableStateOf<VmDisk?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VM Storage & Disk Manager", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp)
        ) {
            // Top Overview Cards: Compact Host Free Space & Metrics
            val totalBytes = remember { viewModel.storageManager.getTotalHostStorageBytes() }
            val availBytes = remember { viewModel.storageManager.getAvailableHostStorageBytes() }
            val usedBytes = totalBytes - availBytes
            val usedPercent = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes) * 100).toInt() else 0

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Host Device Sandbox Storage",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "$usedPercent% Used",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { usedPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Available: ${availBytes / (1024 * 1024 * 1024)} GB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        Text(
                            "Total Sandbox: ${totalBytes / (1024 * 1024 * 1024)} GB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Tab Selector: Scrollable Disks / Snapshots / Backups / Shared Folders / Audit Logs
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                edgePadding = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("Virtual Disks (${disks.size})", maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.testTag("storage_tab_disks")
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Snapshots (${snapshots.size})", maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.testTag("storage_tab_snapshots")
                )
                Tab(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    text = { Text("Backups (${backups.size})", maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.testTag("storage_tab_backups")
                )
                Tab(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    text = { Text("Shared Folders", maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.testTag("storage_tab_shared")
                )
                Tab(
                    selected = activeTab == 4,
                    onClick = { activeTab = 4 },
                    text = { Text("Audit Log", maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.testTag("storage_tab_logs")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (activeTab) {
                0 -> DisksTabContent(
                    disks = disks,
                    onCreateDisk = { showCreateDiskDialog = true },
                    onExpandDisk = { selectedDiskForResize = it },
                    onTakeSnapshot = { disk ->
                        coroutineScope.launch {
                            try {
                                val res = viewModel.storageManager.createSnapshot(disk, "Snap_${System.currentTimeMillis() % 10000}")
                                if (res == null) {
                                    snackbarHostState.showSnackbar("Failed to create snapshot. Please check available storage space.")
                                } else {
                                    snackbarHostState.showSnackbar("Snapshot created successfully.")
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error creating snapshot: ${e.message}")
                            }
                        }
                    }
                )
                1 -> SnapshotsTabContent(snapshots = snapshots)
                2 -> BackupsTabContent(
                    backups = backups,
                    vmConfigs = vmConfigs,
                    onCreateBackup = { config ->
                        coroutineScope.launch {
                            try {
                                val res = viewModel.storageManager.createBackup(config, "${config.name}_Backup")
                                if (res == null) {
                                    snackbarHostState.showSnackbar("Failed to create backup archive. Storage space may be insufficient.")
                                } else {
                                    snackbarHostState.showSnackbar("Backup created successfully.")
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error creating backup: ${e.message}")
                            }
                        }
                    },
                    onRestoreBackup = { backup ->
                        coroutineScope.launch {
                            try {
                                viewModel.storageManager.restoreBackup(backup)
                                snackbarHostState.showSnackbar("Backup restored.")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error restoring backup: ${e.message}")
                            }
                        }
                    }
                )
                3 -> SharedFoldersTabContent(sharedFolderManager = viewModel.sharedFolderManager)
                4 -> AuditLogTabContent(logs = storageLogs)
            }
        }
    }

    if (showCreateDiskDialog) {
        CreateDiskDialog(
            vmConfigs = vmConfigs,
            onDismiss = { showCreateDiskDialog = false },
            onCreate = { vmId, name, sizeGb, role, bootable ->
                coroutineScope.launch {
                    viewModel.storageManager.createDiskForVm(vmId, name, sizeGb, role, bootable)
                    showCreateDiskDialog = false
                }
            }
        )
    }

    selectedDiskForResize?.let { disk ->
        ExpandDiskDialog(
            disk = disk,
            onDismiss = { selectedDiskForResize = null },
            onExpand = { newSizeGb ->
                coroutineScope.launch {
                    viewModel.storageManager.resizeDisk(disk, newSizeGb)
                    selectedDiskForResize = null
                }
            }
        )
    }
}

@Composable
private fun DisksTabContent(
    disks: List<VmDisk>,
    onCreateDisk: () -> Unit,
    onExpandDisk: (VmDisk) -> Unit,
    onTakeSnapshot: (VmDisk) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = onCreateDisk,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag("btn_create_new_disk")
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create New Persistent Virtual Disk")
        }

        if (disks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No virtual disk images configured.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(disks, key = { it.id }) { disk ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(disk.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(disk.role.name) }
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Path: ${disk.diskPath}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Capacity: ${disk.sizeGb} GB", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("Allocated Host: ${disk.actualAllocatedBytes / (1024 * 1024)} MB", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { onExpandDisk(disk) },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Icon(Icons.Default.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Expand Size", fontSize = 12.sp)
                                }
                                Button(onClick = { onTakeSnapshot(disk) }) {
                                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Snapshot", fontSize = 12.sp)
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
private fun SnapshotsTabContent(snapshots: List<VmSnapshot>) {
    if (snapshots.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No disk snapshots available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(snapshots, key = { it.id }) { snap ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(snap.snapshotName, fontWeight = FontWeight.Bold)
                        Text("Size: ${snap.sizeBytes / (1024 * 1024)} MB", fontSize = 12.sp)
                        Text("Path: ${snap.snapshotPath}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupsTabContent(
    backups: List<VmBackup>,
    vmConfigs: List<com.example.vm.core.VMConfig>,
    onCreateBackup: (com.example.vm.core.VMConfig) -> Unit,
    onRestoreBackup: (VmBackup) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (vmConfigs.isNotEmpty()) {
            Text("Create Backup for Existing VM:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vmConfigs.forEach { cfg ->
                    Button(onClick = { onCreateBackup(cfg) }) {
                        Text("Backup ${cfg.name}")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("Saved Backup Archives (.vmbackup):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        if (backups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No backup archives created.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(backups, key = { it.id }) { backup ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(backup.backupName, fontWeight = FontWeight.Bold)
                                Text("Archive Size: ${backup.sizeBytes / (1024 * 1024)} MB", fontSize = 12.sp)
                            }
                            Button(onClick = { onRestoreBackup(backup) }) {
                                Icon(Icons.Default.Restore, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore VM")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedFoldersTabContent(sharedFolderManager: com.example.vm.sharing.SharedFolderManager) {
    var shares by remember { mutableStateOf(sharedFolderManager.getShares()) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Android ↔ Guest Shared Folders:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_shared_folder_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Folder")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (shares.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No shared folders configured.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shares, key = { it.id }) { share ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderShared, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(share.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                if (share.id != "default_shared") {
                                    IconButton(
                                        onClick = {
                                            sharedFolderManager.removeShare(share.id)
                                            shares = sharedFolderManager.getShares()
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove share", tint = MaterialTheme.colorScheme.error)
                                    }
                                } else {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "Default",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Guest Mount: ${share.guestMountPath}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("Host Location: ${share.hostAbsolutePath}", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (share.isReadOnly) "🔒 Read-Only" else "✏️ Read/Write",
                                    fontSize = 11.sp,
                                    color = if (share.isReadOnly) Color(0xFFFFB300) else Color(0xFF00E676)
                                )
                                Text("• Path Traversal Protected", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var folderName by remember { mutableStateOf("Documents") }
        var guestPath by remember { mutableStateOf("/shared/documents") }
        var isReadOnly by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Host Shared Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Folder Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = guestPath,
                        onValueChange = { guestPath = it },
                        label = { Text("Guest Mount Path") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isReadOnly, onCheckedChange = { isReadOnly = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mount as Read-Only")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hostPath = File(sharedFolderManager.getSharedFolderRoot(), folderName.lowercase()).absolutePath
                        sharedFolderManager.addShare(folderName, guestPath, hostPath, isReadOnly)
                        shares = sharedFolderManager.getShares()
                        showAddDialog = false
                    }
                ) {
                    Text("Add Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AuditLogTabContent(logs: List<StorageOperationLog>) {
    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No storage activity logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(logs, key = { it.id }) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (log.result == "SUCCESS") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(log.operation, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(log.result, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(log.details, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateDiskDialog(
    vmConfigs: List<com.example.vm.core.VMConfig>,
    onDismiss: () -> Unit,
    onCreate: (vmId: Long, name: String, sizeGb: Int, role: DiskRole, bootable: Boolean) -> Unit
) {
    var diskName by remember { mutableStateOf("data_disk") }
    var sizeGbText by remember { mutableStateOf("16") }
    var selectedVmId by remember { mutableLongStateOf(vmConfigs.firstOrNull()?.id ?: 1L) }
    var selectedRole by remember { mutableStateOf(DiskRole.ROOTFS) }
    var isBootable by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Virtual Disk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = diskName,
                    onValueChange = { diskName = it },
                    label = { Text("Disk Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sizeGbText,
                    onValueChange = { sizeGbText = it },
                    label = { Text("Size (GB)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isBootable, onCheckedChange = { isBootable = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bootable Disk")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sizeGb = sizeGbText.toIntOrNull() ?: 16
                    onCreate(selectedVmId, diskName, sizeGb, selectedRole, isBootable)
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ExpandDiskDialog(
    disk: VmDisk,
    onDismiss: () -> Unit,
    onExpand: (newSizeGb: Int) -> Unit
) {
    var newSizeText by remember { mutableStateOf((disk.sizeGb + 16).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expand Capacity for ${disk.name}") },
        text = {
            Column {
                Text("Current size: ${disk.sizeGb} GB")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newSizeText,
                    onValueChange = { newSizeText = it },
                    label = { Text("New Target Size (GB)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newSizeGb = newSizeText.toIntOrNull() ?: disk.sizeGb
                    onExpand(newSizeGb)
                }
            ) {
                Text("Expand Disk")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
