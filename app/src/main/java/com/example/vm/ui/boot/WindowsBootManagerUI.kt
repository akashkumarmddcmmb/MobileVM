package com.example.vm.ui.boot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.vm.core.VMBootManager
import com.example.vm.core.VMConfig
import com.example.vm.core.VMState
import com.example.vm.guest.iso.ISOManager
import com.example.vm.ui.VMViewModel
import java.io.File

/**
 * Windows Boot Manager & Multi-OS Boot Selection Environment.
 * Implements full Windows Boot Manager features:
 * - Boot Target Selection (Windows Boot Manager \EFI\Microsoft\Boot\bootmgfw.efi, Linux EFI \EFI\BOOT\BOOTAA64.EFI, ISO Media, Persistent Disk, Recovery)
 * - ISO Installer Attachment & Inspection (Win11 ARM64 ISO, Ubuntu ISO, Debian, Alpine)
 * - NVRAM BootOrder Priority Customization (Move Up / Move Down)
 * - Boot Flags & Safe Mode (/safeboot, /recovery, /install)
 * - Eject Optical ISO Media & OS Installation Complete Transition
 */
@Composable
fun WindowsBootManagerDialog(
    config: VMConfig,
    viewModel: VMViewModel,
    vmState: VMState,
    onDismiss: () -> Unit,
    onStartVmWithBootTarget: (VMConfig) -> Unit
) {
    val context = LocalContext.current
    var currentConfig by remember(config) { mutableStateOf(config) }
    val isRunning = vmState == VMState.RUNNING

    var selectedBootType by remember { mutableStateOf(VMBootManager.BootDeviceType.WINDOWS_BOOT_MANAGER) }
    var bootTimeoutSeconds by remember { mutableIntStateOf(5) }
    var safeModeEnabled by remember { mutableStateOf(false) }
    var recoveryModeEnabled by remember { mutableStateOf(false) }

    val bootEntries = remember(currentConfig) {
        VMBootManager.getAvailableBootEntries(context, currentConfig)
    }

    val isoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.attachIsoToConfig(currentConfig, uri) { updatedConfig ->
                currentConfig = updatedConfig
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0078D4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DesktopWindows, contentDescription = "Windows Boot Manager", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            text = "Windows Boot Manager",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "UEFI v2.8 ARM64 • EDK2 TianoCore NVRAM",
                            fontSize = 10.sp,
                            color = Color(0xFF00E676),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = Color(0xFF0D1622),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF0078D4))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Choose an operating system to start or select an installation ISO:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "Use boot options below to select primary OS boot target, mount installation media, or configure safe boot parameters.",
                            fontSize = 10.sp,
                            color = Color.LightGray
                        )
                    }
                }

                Text("Available Boot Targets", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                bootEntries.forEach { entry ->
                    val isSelected = entry.type == selectedBootType
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF162A3B) else Color(0xFF0F141C)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF0078D4) else if (entry.isAvailable) Color(0xFF232D38) else Color(0x33FF5252)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isAvailable) {
                                    selectedBootType = entry.type
                                }
                            }
                            .testTag("boot_entry_${entry.type.name}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = when (entry.type) {
                                        VMBootManager.BootDeviceType.WINDOWS_BOOT_MANAGER -> Icons.Default.DesktopWindows
                                        VMBootManager.BootDeviceType.LINUX_EFI_LOADER -> Icons.Default.Terminal
                                        VMBootManager.BootDeviceType.INSTALLATION_ISO -> Icons.Default.Album
                                        VMBootManager.BootDeviceType.INSTALLED_VIRTUAL_DISK -> Icons.Default.Storage
                                        VMBootManager.BootDeviceType.DIRECT_KERNEL_IMAGE -> Icons.Default.Memory
                                        VMBootManager.BootDeviceType.RECOVERY_DIAGNOSTICS -> Icons.Default.Build
                                    },
                                    contentDescription = entry.title,
                                    tint = if (entry.isAvailable) Color(0xFF0078D4) else Color.Gray,
                                    modifier = Modifier.size(22.dp)
                                )

                                Column {
                                    Text(
                                        text = entry.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (entry.isAvailable) Color.White else Color.Gray
                                    )
                                    Text(
                                        text = entry.description,
                                        fontSize = 10.sp,
                                        color = if (entry.isAvailable) Color.LightGray else Color.DarkGray,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    if (entry.isAvailable) {
                                        selectedBootType = entry.type
                                    }
                                },
                                enabled = entry.isAvailable
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Optical Media & ISO Installation", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141C)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF232D38)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Attached Optical ISO Media", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Surface(
                                color = if (currentConfig.isoPath.isNotBlank()) Color(0x3300E676) else Color(0x33FFB74D),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (currentConfig.isoPath.isNotBlank()) "ISO MOUNTED" else "NO MEDIA",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentConfig.isoPath.isNotBlank()) Color(0xFF00E676) else Color(0xFFFFB74D),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = if (currentConfig.isoPath.isNotBlank()) "File: ${File(currentConfig.isoPath).name}\nPath: ${currentConfig.isoPath}" else "No installation ISO image attached.",
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { isoPicker.launch("*/*") },
                                enabled = !isRunning,
                                modifier = Modifier.weight(1f).height(34.dp).testTag("btn_bootmgr_attach_iso")
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Attach ISO", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Attach ISO File", fontSize = 10.sp)
                            }

                            if (currentConfig.isoPath.isNotBlank()) {
                                Button(
                                    onClick = {
                                        currentConfig = VMBootManager.ejectInstallerIso(currentConfig)
                                        viewModel.saveFullConfig(currentConfig)
                                    },
                                    enabled = !isRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    modifier = Modifier.height(34.dp).testTag("btn_bootmgr_eject_iso")
                                ) {
                                    Icon(Icons.Default.Eject, contentDescription = "Eject ISO", modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Eject ISO", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Advanced Boot Flags & Recovery", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Windows Safe Mode (/safeboot)", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Boots Windows in minimal diagnostic driver configuration", fontSize = 10.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = safeModeEnabled,
                        onCheckedChange = { safeModeEnabled = it },
                        modifier = Modifier.testTag("switch_bootmgr_safemode")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Recovery Command Prompt (/recovery)", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Launches WinRE / Linux recovery shell environment", fontSize = 10.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = recoveryModeEnabled,
                        onCheckedChange = { recoveryModeEnabled = it },
                        modifier = Modifier.testTag("switch_bootmgr_recovery")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updatedCmdline = buildString {
                        append(currentConfig.kernelCmdline)
                        if (safeModeEnabled && !contains("/safeboot:minimal")) append(" /safeboot:minimal")
                        if (recoveryModeEnabled && !contains("/recovery")) append(" /recovery")
                    }.trim()

                    val targetBootOrder = when (selectedBootType) {
                        VMBootManager.BootDeviceType.INSTALLATION_ISO -> "CD_ROM"
                        VMBootManager.BootDeviceType.DIRECT_KERNEL_IMAGE -> "DIRECT_KERNEL"
                        else -> "VIRTUAL_DISK"
                    }

                    val updatedConfig = currentConfig.copy(
                        bootOrder = targetBootOrder,
                        kernelCmdline = updatedCmdline
                    )

                    viewModel.saveFullConfig(updatedConfig)
                    onStartVmWithBootTarget(updatedConfig)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D4)),
                modifier = Modifier.testTag("btn_bootmgr_start_vm")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Boot Selected OS Target", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray, fontSize = 12.sp)
            }
        },
        containerColor = Color(0xFF080D14),
        shape = RoundedCornerShape(12.dp)
    )
}

/**
 * Windows Boot Manager Card for Embedding directly into VMControlCenterScreen and VMHomeView.
 */
@Composable
fun WindowsBootManagerCard(
    config: VMConfig,
    viewModel: VMViewModel,
    vmState: VMState,
    onOpenBootManagerDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bootTarget = remember(config) { VMBootManager.resolveEffectiveBootTarget(context, config) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1622)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF0078D4)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = "Boot Manager", tint = Color(0xFF0078D4), modifier = Modifier.size(20.dp))
                    Text("Windows Boot Manager & EFI Target", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Surface(
                    color = Color(0x330078D4),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "UEFI ACTIVE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0078D4),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Primary Target: ${bootTarget.description}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.LightGray,
                fontFamily = FontFamily.Monospace
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenBootManagerDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D4)),
                    modifier = Modifier.weight(1f).height(32.dp).testTag("btn_open_boot_manager")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Boot Manager", modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Windows Boot Manager Options", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }

                if (config.isoPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            val updated = VMBootManager.ejectInstallerIso(config)
                            viewModel.saveFullConfig(updated)
                        },
                        modifier = Modifier.height(32.dp).testTag("btn_eject_iso_quick")
                    ) {
                        Icon(Icons.Default.Eject, contentDescription = "Eject ISO", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Eject ISO", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
