package com.example.vm.ui

import android.widget.Toast
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
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.guest.linux.LinuxImageProvisioner
import com.example.vm.guest.os.InstallationMode
import com.example.vm.guest.os.OSManifest
import com.example.vm.guest.os.OSManifestRegistry
import com.example.vm.guest.os.OSStorageManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMCreatorWizardDialog(
    onDismiss: () -> Unit,
    onSaveConfig: (VMConfig) -> Unit,
    initialManifest: OSManifest? = null
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(1) }

    // Step 1: OS
    var selectedOsCategory by remember { mutableStateOf(initialManifest?.osCategory ?: "Ubuntu") }

    // Step 2: Image
    val availableManifests = remember(selectedOsCategory) {
        OSManifestRegistry.OFFICIAL_MANIFESTS.filter {
            selectedOsCategory == "Custom Linux" || it.osCategory.equals(selectedOsCategory, ignoreCase = true)
        }
    }
    var selectedManifest by remember { mutableStateOf(initialManifest ?: availableManifests.firstOrNull()) }

    // Step 3: Name & User
    var vmName by remember { mutableStateOf(selectedManifest?.name ?: "$selectedOsCategory ARM64") }
    var hostname by remember { mutableStateOf("mobilevm-guest") }
    var username by remember { mutableStateOf(selectedManifest?.defaultUser ?: "ubuntu") }
    var password by remember { mutableStateOf(selectedManifest?.defaultPassword ?: "ubuntu") }
    var sshKey by remember { mutableStateOf("") }

    // Step 4: Hardware
    var selectedBackendPref by remember { mutableStateOf("AUTO") } // AUTO, KVM, ARM64_SOFTWARE_EMULATOR
    var cpuCores by remember { mutableIntStateOf(2) }
    var ramSizeMb by remember { mutableIntStateOf(selectedManifest?.recommendedRamMb ?: 2048) }
    var storageGb by remember { mutableIntStateOf(selectedManifest?.minimumStorageGb ?: 16) }

    // Step 5: Boot & Network
    var bootOrder by remember {
        mutableStateOf(
            if (selectedManifest?.installationMode == InstallationMode.MODE_B_ISO_INSTALLER) "CD_ROM" else "VIRTUAL_DISK"
        )
    }
    var networkMode by remember { mutableStateOf("NAT") }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("vm_creator_wizard_dialog"),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F141C),
            border = BorderStroke(1.dp, Color(0xFF232D38)),
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar with Step Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "VM CREATION WIZARD",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Step $currentStep of 5: " + when (currentStep) {
                                1 -> "Select OS Category"
                                2 -> "Choose Image & Mode"
                                3 -> "Name & First-Boot User"
                                4 -> "Hardware Allocation"
                                else -> "Boot & Network Settings"
                            },
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp).testTag("wizard_close")) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = { currentStep / 5.0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF1B2330)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Step Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (currentStep) {
                        1 -> Step1OsSelection(
                            selectedCategory = selectedOsCategory,
                            onSelectCategory = {
                                selectedOsCategory = it
                                vmName = "$it ARM64"
                                val newManifests = OSManifestRegistry.OFFICIAL_MANIFESTS.filter { m ->
                                    it == "Custom Linux" || m.osCategory.equals(it, ignoreCase = true)
                                }
                                selectedManifest = newManifests.firstOrNull()
                            }
                        )

                        2 -> Step2ImageSelection(
                            manifests = availableManifests,
                            selectedManifest = selectedManifest,
                            onSelectManifest = {
                                selectedManifest = it
                                vmName = "${it.name}"
                                bootOrder = if (it.installationMode == InstallationMode.MODE_B_ISO_INSTALLER) "CD_ROM" else "VIRTUAL_DISK"
                            }
                        )

                        3 -> Step3NameAndUser(
                            vmName = vmName,
                            onVmNameChange = { vmName = it },
                            hostname = hostname,
                            onHostnameChange = { hostname = it },
                            username = username,
                            onUsernameChange = { username = it },
                            password = password,
                            onPasswordChange = { password = it },
                            sshKey = sshKey,
                            onSshKeyChange = { sshKey = it }
                        )

                        4 -> Step4HardwareAllocation(
                            backendPref = selectedBackendPref,
                            onBackendPrefChange = { selectedBackendPref = it },
                            cpuCores = cpuCores,
                            onCpuCoresChange = { cpuCores = it },
                            ramMb = ramSizeMb,
                            onRamMbChange = { ramSizeMb = it },
                            storageGb = storageGb,
                            onStorageGbChange = { storageGb = it }
                        )

                        5 -> Step5BootAndNetwork(
                            bootOrder = bootOrder,
                            onBootOrderChange = { bootOrder = it },
                            networkMode = networkMode,
                            onNetworkModeChange = { networkMode = it },
                            installationMode = selectedManifest?.installationMode ?: InstallationMode.MODE_A_PREINSTALLED
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Navigation Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentStep > 1) {
                        OutlinedButton(
                            onClick = { currentStep-- },
                            modifier = Modifier.testTag("wizard_prev")
                        ) {
                            Text("Previous")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (currentStep < 5) {
                        Button(
                            onClick = { currentStep++ },
                            modifier = Modifier.testTag("wizard_next")
                        ) {
                            Text("Next")
                        }
                    } else {
                        Button(
                            onClick = {
                                val manifest = selectedManifest
                                val isWindows = selectedOsCategory.contains("Windows", ignoreCase = true)
                                val modeStr = if (isWindows || manifest?.installationMode == InstallationMode.MODE_B_ISO_INSTALLER) {
                                    "MODE_B_ISO_INSTALLER"
                                } else {
                                    "MODE_A_PREINSTALLED"
                                }

                                val isHwVirt = selectedBackendPref != "ARM64_SOFTWARE_EMULATOR"

                                val installedFiles = if (manifest != null) OSStorageManager.getInstalledFiles(context, manifest) else null

                                val diskPath = if (isWindows) {
                                    val disksDir = File(context.filesDir, "app_disks").apply { if (!exists()) mkdirs() }
                                    File(disksDir, "${vmName.replace("\\s+".toRegex(), "_").lowercase()}_sys.img").absolutePath
                                } else if (installedFiles?.diskFile != null && installedFiles.diskFile.exists()) {
                                    installedFiles.diskFile.absolutePath
                                } else {
                                    LinuxImageProvisioner.getDefaultDiskFile(context, vmName).absolutePath
                                }

                                val kernelPath = if (isWindows) {
                                    ""
                                } else if (installedFiles?.kernelFile != null && installedFiles.kernelFile.exists()) {
                                    installedFiles.kernelFile.absolutePath
                                } else {
                                    LinuxImageProvisioner.getKernelFile(context).absolutePath
                                }

                                val initrdPath = if (isWindows) {
                                    ""
                                } else if (installedFiles?.initrdFile != null && installedFiles.initrdFile.exists()) {
                                    installedFiles.initrdFile.absolutePath
                                } else {
                                    LinuxImageProvisioner.getInitramfsFile(context).absolutePath
                                }

                                val config = VMConfig(
                                    name = vmName.ifBlank { if (isWindows) "Windows 11 ARM64" else "MobileVM Guest" },
                                    guestOsType = selectedOsCategory,
                                    osVersion = if (isWindows) "Windows 11 on ARM" else (manifest?.version ?: "24.04 LTS"),
                                    installationMode = modeStr,
                                    cpuBackendPreference = selectedBackendPref,
                                    bootOrder = bootOrder,
                                    isoPath = if (modeStr == "MODE_B_ISO_INSTALLER" && manifest?.diskUrl?.isNotBlank() == true) manifest.diskUrl else "",
                                    guestArchCode = GuestArchitecture.ARM64.code,
                                    cpuCores = if (isWindows) maxOf(cpuCores, 2) else cpuCores,
                                    ramSizeMb = if (isWindows) maxOf(ramSizeMb, 2048) else ramSizeMb,
                                    diskSizeGb = if (isWindows) maxOf(storageGb, 64) else storageGb,
                                    diskImagePath = diskPath,
                                    useHardwareVirtualization = isHwVirt,
                                    networkEnabled = networkMode != "OFF",
                                    networkMode = networkMode,
                                    hostname = hostname.ifBlank { if (isWindows) "WIN-ARM64" else "mobilevm-guest" },
                                    username = username.ifBlank { if (isWindows) "User" else "ubuntu" },
                                    password = password.ifBlank { if (isWindows) "Password" else "ubuntu" },
                                    sshPublicKey = sshKey,
                                    kernelImagePath = kernelPath,
                                    initramfsPath = initrdPath,
                                    kernelCmdline = if (isWindows) "" else (manifest?.defaultKernelCmdline ?: "console=ttyAMA0,115200 root=/dev/vda1 rw init=/init earlycon=pl011,0x09000000"),
                                    consoleDevice = if (isWindows) "VirtIO-GPU Framebuffer" else (manifest?.consoleDevice ?: "ttyAMA0 (PL011 UART)")
                                )

                                onSaveConfig(config)
                                Toast.makeText(context, "VM Configuration Saved!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("wizard_finish")
                        ) {
                            Text("Create VM", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Step1OsSelection(selectedCategory: String, onSelectCategory: (String) -> Unit) {
    val categories = listOf(
        "Ubuntu",
        "Windows ARM64",
        "Kali Linux",
        "Debian",
        "Alpine",
        "Fedora",
        "AlmaLinux",
        "Rocky Linux",
        "Arch Linux",
        "openSUSE",
        "Custom Linux"
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Select Operating System Ecosystem", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        categories.forEach { category ->
            val isSelected = category == selectedCategory
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1A2633) else Color(0xFF141A23)),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF232D38)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectCategory(category) }
                    .testTag("os_category_$category")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            category.contains("Windows") -> Icons.Default.Window
                            category.contains("Kali") -> Icons.Default.Security
                            category.contains("Fedora") || category.contains("Alma") || category.contains("Rocky") -> Icons.Default.Cloud
                            category.contains("Arch") -> Icons.Default.Terminal
                            else -> Icons.Default.Computer
                        },
                        contentDescription = category,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(category, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            when (category) {
                                "Ubuntu" -> "Official Canonical Ubuntu Server / Desktop Cloud Images & ISOs"
                                "Windows ARM64" -> "Microsoft Windows 11 on ARM via UEFI EDK2 & Virtual TPM 2.0 (User ISO/Disk)"
                                "Kali Linux" -> "Official OffSec Kali Penetration Testing Cloud Images & ISOs"
                                "Debian" -> "Official Debian 12 (Bookworm) Cloud Images & Netinst ISOs"
                                "Alpine" -> "Ultra-lightweight musl-based Alpine Linux 3.20 virtualized image"
                                "Fedora" -> "Official Fedora 40 Cloud Edition RPM-based Linux"
                                "AlmaLinux" -> "Official AlmaLinux 9 RHEL-compatible Enterprise Linux Cloud Image"
                                "Rocky Linux" -> "Official Rocky Linux 9 RHEL-compatible Enterprise Cloud Image"
                                "Arch Linux" -> "Official Arch Linux ARM (aarch64) Rolling Release with pacman"
                                "openSUSE" -> "Official openSUSE Leap 15.6 JeOS Minimal Cloud Image with zypper"
                                else -> "Custom ARM64 Linux vmlinuz kernel, initramfs & disk image"
                            },
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }
                    RadioButton(selected = isSelected, onClick = { onSelectCategory(category) })
                }
            }
        }
    }
}

@Composable
fun Step2ImageSelection(
    manifests: List<OSManifest>,
    selectedManifest: OSManifest?,
    onSelectManifest: (OSManifest) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Select OS Image & Installation Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        if (manifests.isEmpty()) {
            Text("No pre-configured manifests found for this OS.", fontSize = 12.sp, color = Color.Gray)
        } else {
            manifests.forEach { manifest ->
                val isSelected = manifest.id == selectedManifest?.id
                val isIso = manifest.format == "ISO"

                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1A2633) else Color(0xFF141A23)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF232D38)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectManifest(manifest) }
                        .testTag("manifest_item_${manifest.id}")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(manifest.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Surface(
                                color = if (isIso) Color(0x33FFB74D) else Color(0x3381D4FA),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isIso) "MODE B: ISO INSTALLER" else "MODE A: PRE-INSTALLED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isIso) Color(0xFFFFB74D) else Color(0xFF81D4FA),
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Architecture: ${manifest.architecture} • Format: ${manifest.format}", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                        Text(manifest.notes, fontSize = 11.sp, color = Color.LightGray)
                    }
                }
            }
        }
    }
}

@Composable
fun Step3NameAndUser(
    vmName: String, onVmNameChange: (String) -> Unit,
    hostname: String, onHostnameChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    sshKey: String, onSshKeyChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("VM Identity & First-Boot Cloud-Init Credentials", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(
            value = vmName, onValueChange = onVmNameChange,
            label = { Text("VM Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_vm_name")
        )

        OutlinedTextField(
            value = hostname, onValueChange = onHostnameChange,
            label = { Text("Hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input_hostname")
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = username, onValueChange = onUsernameChange,
                label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f).testTag("input_username")
            )
            OutlinedTextField(
                value = password, onValueChange = onPasswordChange,
                label = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f).testTag("input_password")
            )
        }

        OutlinedTextField(
            value = sshKey, onValueChange = onSshKeyChange,
            label = { Text("Optional SSH Public Key (id_rsa.pub)") }, modifier = Modifier.fillMaxWidth().testTag("input_ssh_key")
        )
    }
}

@Composable
fun Step4HardwareAllocation(
    backendPref: String, onBackendPrefChange: (String) -> Unit,
    cpuCores: Int, onCpuCoresChange: (Int) -> Unit,
    ramMb: Int, onRamMbChange: (Int) -> Unit,
    storageGb: Int, onStorageGbChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CPU Backend & Hardware Resource Allocation", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        // CPU Backend Options
        Text("CPU Backend Mode", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("AUTO", "KVM", "ARM64_SOFTWARE_EMULATOR").forEach { mode ->
                FilterChip(
                    selected = backendPref == mode,
                    onClick = { onBackendPrefChange(mode) },
                    label = { Text(if (mode == "ARM64_SOFTWARE_EMULATOR") "EMULATOR" else mode, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.testTag("backend_chip_$mode")
                )
            }
        }

        // vCPU Cores
        Text("vCPU Cores", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4).forEach { cores ->
                FilterChip(
                    selected = cpuCores == cores,
                    onClick = { onCpuCoresChange(cores) },
                    label = { Text("$cores Core${if (cores > 1) "s" else ""}") },
                    modifier = Modifier.testTag("cores_chip_$cores")
                )
            }
        }

        // RAM Allocation
        Text("Guest RAM Allocation", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(512, 1024, 1536, 2048, 3072, 4096).forEach { mb ->
                FilterChip(
                    selected = ramMb == mb,
                    onClick = { onRamMbChange(mb) },
                    label = { Text(if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else if (mb >= 1024) "${mb / 1024.0} GB" else "$mb MB", fontSize = 10.sp) },
                    modifier = Modifier.testTag("ram_chip_$mb")
                )
            }
        }

        // Virtual Storage Size
        Text("Virtual Disk Size (Allocated on VM disk, master image untouched)", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(8, 16, 32, 64, 128).forEach { gb ->
                FilterChip(
                    selected = storageGb == gb,
                    onClick = { onStorageGbChange(gb) },
                    label = { Text("$gb GB") },
                    modifier = Modifier.testTag("storage_chip_$gb")
                )
            }
        }
    }
}

@Composable
fun Step5BootAndNetwork(
    bootOrder: String, onBootOrderChange: (String) -> Unit,
    networkMode: String, onNetworkModeChange: (String) -> Unit,
    installationMode: InstallationMode
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Boot Sequence & Virtual Network Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Text("Primary Boot Order", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = bootOrder == "VIRTUAL_DISK",
                onClick = { onBootOrderChange("VIRTUAL_DISK") },
                label = { Text("1st: Virtual Disk") },
                modifier = Modifier.testTag("boot_order_disk")
            )
            FilterChip(
                selected = bootOrder == "CD_ROM",
                onClick = { onBootOrderChange("CD_ROM") },
                label = { Text("1st: Virtual CD-ROM (ISO)") },
                modifier = Modifier.testTag("boot_order_cdrom")
            )
        }

        if (installationMode == InstallationMode.MODE_B_ISO_INSTALLER) {
            Surface(color = Color(0x33FFB74D), shape = RoundedCornerShape(6.dp)) {
                Text(
                    text = "Mode B Installer ISO detected: Default boot order set to CD-ROM 1st. After installation completes, switch boot order to Virtual Disk 1st.",
                    fontSize = 11.sp, color = Color(0xFFFFB74D), modifier = Modifier.padding(8.dp)
                )
            }
        }

        Text("Virtual Network Mode", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("NAT", "OFF", "USER_MODE").forEach { mode ->
                FilterChip(
                    selected = networkMode == mode,
                    onClick = { onNetworkModeChange(mode) },
                    label = { Text(mode) },
                    modifier = Modifier.testTag("network_mode_$mode")
                )
            }
        }
    }
}
