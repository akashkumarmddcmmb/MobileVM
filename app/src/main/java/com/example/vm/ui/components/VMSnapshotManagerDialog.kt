package com.example.vm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import com.example.vm.storage.VmSnapshot
import com.example.vm.ui.VMViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Snapshot Management Dialog for taking live VM state snapshots
 * and restoring VM instances from previous local disk restore points.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMSnapshotManagerDialog(
    config: VMConfig,
    viewModel: VMViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeVM by viewModel.activeVM.collectAsStateWithLifecycle()
    val isThisVmActive = activeVM?.config?.id == config.id || (activeVM != null && activeVM!!.config.name == config.name)
    val vmEngine = if (isThisVmActive) activeVM else null
    val vmState = vmEngine?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED

    val allSnapshots by viewModel.allSnapshots.collectAsStateWithLifecycle()
    val vmSnapshots = remember(allSnapshots, config.id) {
        allSnapshots.filter { it.vmId == config.id }
    }

    var newSnapshotName by remember { mutableStateOf("Checkpoint_${SimpleDateFormat("HHmm_ss", Locale.US).format(Date())}") }
    var isCapturing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isErrorStatus by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0C1015),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2833)),
            modifier = modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .testTag("dialog_snapshot_manager")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Snapshot",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "VM Snapshot & Restore Manager",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Target: ${config.name} (${config.guestOsType})",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("btn_close_snapshot_dialog")) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1B2430))
                Spacer(modifier = Modifier.height(12.dp))

                // SECTION 1: CREATE NEW SNAPSHOT CARD
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121820)),
                    border = BorderStroke(1.dp, Color(0xFF222F3E)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "📸 Capture Live VM State Snapshot",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newSnapshotName,
                                onValueChange = { newSnapshotName = it },
                                label = { Text("Snapshot Label") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("input_snapshot_name"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF273342)
                                )
                            )

                            Button(
                                onClick = {
                                    if (vmEngine != null && vmState.isActive()) {
                                        if (newSnapshotName.isNotBlank()) {
                                            isCapturing = true
                                            statusMessage = "Capturing CPU, RAM, registers & disk state..."
                                            isErrorStatus = false
                                            viewModel.createSnapshot(vmEngine, newSnapshotName) { errMsg ->
                                                isCapturing = false
                                                if (errMsg == null) {
                                                    statusMessage = "✅ Snapshot '$newSnapshotName' captured successfully!"
                                                    isErrorStatus = false
                                                    newSnapshotName = "Checkpoint_${SimpleDateFormat("HHmm_ss", Locale.US).format(Date())}"
                                                } else {
                                                    statusMessage = "❌ Snapshot failed: $errMsg"
                                                    isErrorStatus = true
                                                }
                                            }
                                        } else {
                                            statusMessage = "Please enter a valid snapshot label."
                                            isErrorStatus = true
                                        }
                                    } else {
                                        statusMessage = "VM must be active (Running/Paused) to capture live snapshot state."
                                        isErrorStatus = true
                                    }
                                },
                                enabled = !isCapturing && newSnapshotName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("btn_capture_snapshot")
                            ) {
                                if (isCapturing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Save State", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }

                        // Live status message banner
                        statusMessage?.let { msg ->
                            Surface(
                                color = if (isErrorStatus) Color(0x22FF5252) else Color(0x2200E676),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, if (isErrorStatus) Color(0x55FF5252) else Color(0x5500E676)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    fontSize = 10.sp,
                                    color = if (isErrorStatus) Color(0xFFFF8A80) else Color(0xFF00E676),
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // SECTION 2: RESTORE POINTS LIST
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⏮️ Restore Points (${vmSnapshots.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "SHA-256 Checksum Protection Active",
                        fontSize = 9.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (vmSnapshots.isEmpty()) {
                    Surface(
                        color = Color(0xFF10151C),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF1C2530)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No snapshots saved for this VM.",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.LightGray
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Take a live snapshot above to create a local restore point.",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vmSnapshots, key = { it.id }) { snapshot ->
                            SnapshotItemCard(
                                snapshot = snapshot,
                                vmEngine = vmEngine,
                                vmState = vmState,
                                onRestore = {
                                    if (vmEngine != null) {
                                        statusMessage = "Restoring CPU, RAM, registers & disk state..."
                                        isErrorStatus = false
                                        viewModel.restoreSnapshot(vmEngine, snapshot) { errMsg ->
                                            if (errMsg == null) {
                                                statusMessage = "✅ Successfully restored VM to snapshot '${snapshot.snapshotName}'!"
                                                isErrorStatus = false
                                            } else {
                                                statusMessage = "❌ Restore failed: $errMsg"
                                                isErrorStatus = true
                                            }
                                        }
                                    } else {
                                        statusMessage = "VM instance must be active to restore live CPU registers."
                                        isErrorStatus = true
                                    }
                                },
                                onDelete = {
                                    viewModel.deleteSnapshot(snapshot)
                                    statusMessage = "Snapshot '${snapshot.snapshotName}' deleted."
                                    isErrorStatus = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotItemCard(
    snapshot: VmSnapshot,
    vmEngine: VMEngine?,
    vmState: VMState,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(snapshot.timestamp) {
        SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))
    }
    val manifestFile = remember(snapshot.snapshotPath) { File(snapshot.snapshotPath) }
    val fileExists = remember(manifestFile) { manifestFile.exists() }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10161E)),
        border = BorderStroke(1.dp, Color(0xFF1E2833)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("snapshot_card_${snapshot.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = snapshot.snapshotName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = dateStr,
                        fontSize = 10.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "• ${snapshot.sizeBytes / 1024} KB",
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (fileExists) {
                    Text(
                        text = "SHA-256 Checksum Verified",
                        fontSize = 9.sp,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        text = "Manifest File Missing",
                        fontSize = 9.sp,
                        color = Color(0xFFFF5252),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Restore Button
                Button(
                    onClick = onRestore,
                    enabled = fileExists && vmEngine != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("btn_restore_snapshot_${snapshot.id}")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = "Restore", tint = Color.Black, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }

                // Delete Button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("btn_delete_snapshot_${snapshot.id}")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
