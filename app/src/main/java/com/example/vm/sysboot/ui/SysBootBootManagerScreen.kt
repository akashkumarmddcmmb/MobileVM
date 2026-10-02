package com.example.vm.sysboot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vm.sysboot.data.BootEntryEntity
import com.example.vm.sysboot.viewmodel.SysBootViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SysBootBootManagerScreen(viewModel: SysBootViewModel) {
    val bootEntries by viewModel.bootEntries.collectAsState()
    val bootSimState by viewModel.bootSimState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<BootEntryEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "UEFI Boot Entry Manager",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure UEFI loaders & kernel startup parameters for Windows & Linux",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SysBootStatusBadge(
                    text = "${bootEntries.size} OS ENTRIES",
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }

            // Live Simulation Output if Active
            if (bootSimState.isRunning || bootSimState.isFinished) {
                SysBootTerminalWindowCard(
                    title = "bootloader_simulation_${bootSimState.targetOsName.lowercase().replace(" ", "_")}.log",
                    logs = bootSimState.terminalLogs,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Boot Entries List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(bootEntries, key = { it.id }) { entry ->
                    BootEntryCard(
                        entry = entry,
                        onSimulate = { viewModel.simulateBoot(entry) },
                        onSetDefault = { viewModel.setDefaultBootEntry(entry.id) },
                        onEdit = { editingEntry = entry },
                        onDelete = { viewModel.deleteBootEntry(entry) },
                        onToggle = { updated -> viewModel.updateBootEntry(updated) }
                    )
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("add_boot_entry_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Boot Entry")
        }

        if (showAddDialog) {
            AddOrEditBootEntryDialog(
                initialEntry = null,
                onDismiss = { showAddDialog = false },
                onSave = { name, loader, osType, params, isDef ->
                    viewModel.addBootEntry(name, loader, osType, params, isDef)
                    showAddDialog = false
                }
            )
        }

        editingEntry?.let { entry ->
            AddOrEditBootEntryDialog(
                initialEntry = entry,
                onDismiss = { editingEntry = null },
                onSave = { name, loader, osType, params, isDef ->
                    viewModel.updateBootEntry(
                        entry.copy(
                            name = name,
                            loaderPath = loader,
                            osType = osType,
                            kernelParams = params,
                            isDefault = isDef
                        )
                    )
                    if (isDef && !entry.isDefault) {
                        viewModel.setDefaultBootEntry(entry.id)
                    }
                    editingEntry = null
                }
            )
        }
    }
}

@Composable
private fun BootEntryCard(
    entry: BootEntryEntity,
    onSimulate: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (BootEntryEntity) -> Unit
) {
    val osColor = when (entry.osType) {
        "WINDOWS" -> Color(0xFF00A4EF)
        "LINUX" -> Color(0xFFE95420)
        "RECOVERY" -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("boot_entry_card_${entry.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(osColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = entry.osType,
                            tint = osColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Order: #${entry.displayOrder} | ${entry.osType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isDefault) {
                        SysBootStatusBadge(
                            text = "DEFAULT",
                            containerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                            contentColor = Color(0xFF10B981)
                        )
                    } else {
                        IconButton(
                            onClick = onSetDefault,
                            modifier = Modifier.testTag("set_default_btn_${entry.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.StarOutline,
                                contentDescription = "Set Default",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = entry.isEnabled,
                        onCheckedChange = { onToggle(entry.copy(isEnabled = it)) },
                        modifier = Modifier.testTag("enable_switch_${entry.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .padding(10.dp)
            ) {
                Text(
                    text = "EFI Binary: ${entry.loaderPath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Params: ${entry.kernelParams}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSimulate,
                    enabled = entry.isEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("simulate_entry_${entry.id}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Boot Target", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOrEditBootEntryDialog(
    initialEntry: BootEntryEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, loader: String, osType: String, params: String, isDefault: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialEntry?.name ?: "") }
    var loaderPath by remember { mutableStateOf(initialEntry?.loaderPath ?: "EFI/Linux/grubaa64.efi") }
    var osType by remember { mutableStateOf(initialEntry?.osType ?: "LINUX") }
    var params by remember { mutableStateOf(initialEntry?.kernelParams ?: "quiet splash console=ttyAMA0,115200") }
    var isDefault by remember { mutableStateOf(initialEntry?.isDefault ?: false) }
    val osTypes = listOf("WINDOWS", "LINUX", "RECOVERY", "DIAGNOSTICS", "SHELL")
    var expandedOsDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEntry == null) "Add OS Boot Entry" else "Edit Boot Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("OS Entry Title") },
                    modifier = Modifier.fillMaxWidth().testTag("entry_name_input")
                )
                ExposedDropdownMenuBox(
                    expanded = expandedOsDropdown,
                    onExpandedChange = { expandedOsDropdown = !expandedOsDropdown }
                ) {
                    OutlinedTextField(
                        value = osType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("OS Platform Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedOsDropdown) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedOsDropdown,
                        onDismissRequest = { expandedOsDropdown = false }
                    ) {
                        osTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    osType = type
                                    expandedOsDropdown = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = loaderPath,
                    onValueChange = { loaderPath = it },
                    label = { Text("EFI Loader Target Path") },
                    modifier = Modifier.fillMaxWidth().testTag("loader_path_input")
                )
                OutlinedTextField(
                    value = params,
                    onValueChange = { params = it },
                    label = { Text("Kernel / BCD Parameters") },
                    modifier = Modifier.fillMaxWidth().testTag("params_input")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isDefault,
                        onCheckedChange = { isDefault = it },
                        modifier = Modifier.testTag("default_switch_dialog")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set as Primary Default OS")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && loaderPath.isNotBlank()) {
                        onSave(name, loaderPath, osType, params, isDefault)
                    }
                },
                modifier = Modifier.testTag("save_boot_entry_btn")
            ) {
                Text("Save Entry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
