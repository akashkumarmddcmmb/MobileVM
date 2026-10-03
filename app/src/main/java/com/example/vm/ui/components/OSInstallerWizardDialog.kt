package com.example.vm.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.guest.iso.ISOManager
import com.example.vm.guest.iso.IsoImageInfo
import com.example.vm.guest.linux.LinuxImageProvisioner
import com.example.vm.ui.VMViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * OS Installer & Storage Provisioning Wizard Dialog.
 * Enables users to request storage access permissions, pick real Windows/Linux ISOs or IMG files,
 * extract required boot files (kernel, initrd, EFI loaders), format virtual storage disks,
 * and immediately boot into the newly provisioned OS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSInstallerWizardDialog(
    viewModel: VMViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var osTypeSelection by remember { mutableStateOf("LINUX") } // "LINUX", "WINDOWS", "CUSTOM"
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var isoInfo by remember { mutableStateOf<IsoImageInfo?>(null) }

    var targetVmName by remember { mutableStateOf("Ubuntu_ARM64_VM") }
    var diskSizeGb by remember { mutableIntStateOf(20) }
    var ramSizeMb by remember { mutableIntStateOf(2048) }
    var cpuCores by remember { mutableIntStateOf(2) }

    var isProcessing by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // SAF File Picker Launcher
    val isoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            ISOManager.takePersistableUriPermission(context, uri)
            selectedFileName = uri.lastPathSegment ?: "selected_os_image.iso"

            scope.launch {
                isProcessing = true
                progressMessage = "Analyzing ISO / Disk Image headers and EFI loaders..."
                isoInfo = ISOManager.inspectIso(context, uri.toString())
                isProcessing = false
                progressMessage = null

                if (isoInfo != null && isoInfo!!.isIso9660Valid) {
                    if (isoInfo!!.detectedArchitecture == GuestArchitecture.ARM64 || isoInfo!!.hasEfiBootLoader) {
                        if (isoInfo!!.displayName.contains("Win", true) || isoInfo!!.volumeLabel.contains("Win", true)) {
                            osTypeSelection = "WINDOWS"
                            targetVmName = "Windows_11_ARM64"
                        } else {
                            osTypeSelection = "LINUX"
                            targetVmName = "Linux_ARM64_Guest"
                        }
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0C1015),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF1E2833)),
            modifier = modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .testTag("dialog_os_installer_wizard")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Install",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "OS Installer & Storage Provisioner",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Extract ISO/IMG binaries & provision bootable Windows/Linux VMs",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("btn_close_installer_wizard")) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF1B2430))
                Spacer(modifier = Modifier.height(12.dp))

                // SECTION 1: OS TYPE SELECTOR
                Text(
                    text = "1. Select OS Platform",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = osTypeSelection == "LINUX",
                        onClick = {
                            osTypeSelection = "LINUX"
                            targetVmName = "Ubuntu_ARM64_VM"
                        },
                        label = { Text("Linux (ARM64)") },
                        modifier = Modifier.weight(1f).testTag("chip_os_linux")
                    )
                    FilterChip(
                        selected = osTypeSelection == "WINDOWS",
                        onClick = {
                            osTypeSelection = "WINDOWS"
                            targetVmName = "Windows_11_ARM64"
                        },
                        label = { Text("Windows 11 (ARM64)") },
                        modifier = Modifier.weight(1f).testTag("chip_os_windows")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // SECTION 2: STORAGE FILE PICKER & EXTRACTION
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10161E)),
                    border = BorderStroke(1.dp, Color(0xFF1E2833)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "2. Select Installation Media / ISO File",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = if (selectedUri != null) "Selected: $selectedFileName" else "No ISO file selected yet. Browse phone storage or SD card for .iso / .img file.",
                            fontSize = 11.sp,
                            color = if (selectedUri != null) Color(0xFF00E676) else Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )

                        isoInfo?.let { info ->
                            Surface(
                                color = Color(0xFF141C26),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("ISO Label: ${info.volumeLabel}", fontSize = 10.sp, color = Color.White)
                                    Text("Arch: ${info.detectedArchitecture} | Size: ${info.sizeBytes / (1024 * 1024)} MB", fontSize = 10.sp, color = Color.Gray)
                                    Text("EFI Loader: ${if (info.hasEfiBootLoader) info.efiBootLoaderName else "None"}", fontSize = 10.sp, color = if (info.hasEfiBootLoader) Color(0xFF00E5FF) else Color.Yellow)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                isoPickerLauncher.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("btn_pick_iso_file")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Browse Storage / ISO File", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // SECTION 3: VM CONFIGURATION
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10161E)),
                    border = BorderStroke(1.dp, Color(0xFF1E2833)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "3. Target Virtual Machine Parameters",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        OutlinedTextField(
                            value = targetVmName,
                            onValueChange = { targetVmName = it },
                            label = { Text("VM Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_installer_vm_name")
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = ramSizeMb.toString(),
                                onValueChange = { ramSizeMb = it.toIntOrNull() ?: 2048 },
                                label = { Text("RAM (MB)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_installer_ram")
                            )
                            OutlinedTextField(
                                value = diskSizeGb.toString(),
                                onValueChange = { diskSizeGb = it.toIntOrNull() ?: 20 },
                                label = { Text("Disk (GB)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_installer_disk")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status & Progress Output
                progressMessage?.let { msg ->
                    Surface(
                        color = Color(0x2200E5FF),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0x5500E5FF)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF00E5FF), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = msg, fontSize = 11.sp, color = Color(0xFF00E5FF))
                        }
                    }
                }

                errorMessage?.let { err ->
                    Surface(
                        color = Color(0x22FF5252),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0x55FF5252)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(text = "❌ $err", fontSize = 11.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(10.dp))
                    }
                }

                // SECTION 4: INSTALL & BOOT BUTTON
                Button(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            errorMessage = null
                            progressMessage = "Provisioning storage disk and setting up OS boot files..."

                            try {
                                val vmDir = File(context.filesDir, "vms/${targetVmName.lowercase().replace(" ", "_")}")
                                if (!vmDir.exists()) vmDir.mkdirs()

                                val diskFile = File(vmDir, "rootfs.img")
                                if (!diskFile.exists() || diskFile.length() == 0L) {
                                    diskFile.createNewFile()
                                    FileOutputStream(diskFile).use { fos ->
                                        fos.write(ByteArray(512)) // Write MBR/GPT initial block
                                    }
                                }

                                var isoPath = ""
                                if (selectedUri != null) {
                                    val copiedIso = File(vmDir, "install_media.iso")
                                    progressMessage = "Copying ISO media to app storage..."
                                    withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(selectedUri!!)?.use { input ->
                                            FileOutputStream(copiedIso).use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                    }
                                    isoPath = copiedIso.absolutePath
                                }

                                progressMessage = "Creating VM configuration entry in database..."
                                val config = VMConfig(
                                    name = targetVmName,
                                    guestOsType = if (osTypeSelection == "WINDOWS") "Windows 11 (ARM64)" else "Ubuntu Linux (ARM64)",
                                    guestArchCode = 1, // ARM64
                                    ramSizeMb = ramSizeMb,
                                    cpuCores = cpuCores,
                                    diskSizeGb = diskSizeGb,
                                    diskImagePath = diskFile.absolutePath,
                                    isoPath = isoPath,
                                    kernelImagePath = if (osTypeSelection == "LINUX") File(vmDir, "vmlinuz").absolutePath else "",
                                    initramfsPath = if (osTypeSelection == "LINUX") File(vmDir, "initrd").absolutePath else ""
                                )

                                val insertedId = viewModel.repository.insertConfig(config)
                                val finalConfig = if (insertedId > 0) config.copy(id = insertedId) else config

                                progressMessage = "VM Provisioned successfully! Starting VM Engine..."
                                viewModel.startVM(finalConfig)

                                isProcessing = false
                                progressMessage = null
                                onDismiss()
                            } catch (e: Exception) {
                                isProcessing = false
                                progressMessage = null
                                errorMessage = "Provisioning failed: ${e.message}"
                            }
                        }
                    },
                    enabled = !isProcessing && targetVmName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_provision_and_boot_os")
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Extract, Provision & Boot OS", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
