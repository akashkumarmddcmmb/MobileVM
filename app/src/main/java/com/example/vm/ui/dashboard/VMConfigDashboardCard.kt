package com.example.vm.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.memory.MemoryManager
import com.example.vm.ui.VMViewModel
import java.io.File

/**
 * Interactive Virtual Machine Configuration & Hardware Resource Allocator Dashboard.
 * Allows users to allocate vCPU cores, RAM size, and virtual storage for new VM instances
 * with real-time physical host resource safety checks.
 */
@Composable
fun VMConfigDashboardCard(
    viewModel: VMViewModel,
    onLaunchVM: (VMConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val memoryManager = remember { MemoryManager(context) }
    val hostStats = remember { memoryManager.getHostMemoryStats() }

    var isExpanded by remember { mutableStateOf(false) }
    var vmName by remember { mutableStateOf("Ubuntu_ARM64") }
    var selectedOs by remember { mutableStateOf("Ubuntu 24.04 ARM64") }
    var selectedArch by remember { mutableStateOf(GuestArchitecture.ARM64) }
    var cpuCores by remember { mutableIntStateOf(2) }
    var ramSizeMb by remember { mutableIntStateOf(2048) }
    var diskSizeGb by remember { mutableIntStateOf(20) }
    var useHardwareVirt by remember {
        mutableStateOf(viewModel.hostArchitecture == HostArchitecture.ARM64 && viewModel.isKvmSupported)
    }

    val safetyResult = memoryManager.getMemorySafetyRecommendation(ramSizeMb)
    val isMemSafe = safetyResult !is MemoryManager.SafetyResult.Danger

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E131A)),
        border = BorderStroke(1.dp, Color(0xFF232D38)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("vm_config_dashboard_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header with toggle
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
                                Icons.Default.Tune,
                                contentDescription = "Hardware Configuration",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "VM HARDWARE CONFIGURATION",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                        Text(
                            text = "CPU, RAM & Storage Resource Allocator",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand Configuration Panel",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Quick Stats Row (always visible)
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = Color(0xFF141A22),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF1E2631)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text("CPU CORES", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Text("$cpuCores vCPUs", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("RAM SIZE", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Text("${if (ramSizeMb >= 1024) "${ramSizeMb / 1024} GB" else "$ramSizeMb MB"}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("STORAGE", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            Text("$diskSizeGb GB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300), fontFamily = FontFamily.Monospace)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (useHardwareVirt) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFFFFB300).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (useHardwareVirt) "KVM ACCELERATED" else "EMULATED",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (useHardwareVirt) Color(0xFF00E676) else Color(0xFFFFB300),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Expanded Detailed Allocator Controls
            AnimatedVisibility(visible = isExpanded, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF1E2631))

                    // 1. Guest OS Template
                    Column {
                        Text("1. Guest OS Template", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        val osTemplates = listOf(
                            "Ubuntu 24.04 ARM64" to (2 to 2048),
                            "Windows 11 ARM64" to (4 to 4096),
                            "Kali Linux ARM64" to (4 to 3072),
                            "Debian 12 ARM64" to (2 to 1536),
                            "Alpine Linux ARM64" to (1 to 512),
                            "Arch Linux ARM64" to (2 to 2048)
                        )
                        osTemplates.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                row.forEach { (osOption, defaults) ->
                                    val isSelected = selectedOs == osOption
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedOs = osOption
                                            vmName = osOption.replace("\\s+".toRegex(), "_")
                                            cpuCores = defaults.first
                                            ramSizeMb = defaults.second
                                            diskSizeGb = if (osOption.contains("Windows", ignoreCase = true)) 64 else 20
                                        },
                                        label = { Text(osOption, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // 2. VM Instance Name
                    OutlinedTextField(
                        value = vmName,
                        onValueChange = { vmName = it },
                        label = { Text("VM Instance Name", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // 3. vCPU Cores Selector
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("2. Allocate vCPU Cores", fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                            Text("$cpuCores vCPUs selected", fontSize = 10.sp, color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(1, 2, 4, 6, 8).forEach { cores ->
                                val isSelected = cpuCores == cores
                                Button(
                                    onClick = { cpuCores = cores },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF161B21),
                                        contentColor = if (isSelected) Color.Black else Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("$cores Core${if (cores > 1) "s" else ""}", fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }

                    // 4. RAM Allocation Selector
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("3. Allocate Guest RAM", fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                            Text("Host Free: ${hostStats.availableMb} MB", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                512 to "512M",
                                1024 to "1G",
                                2048 to "2G",
                                3072 to "3G",
                                4096 to "4G",
                                6144 to "6G",
                                8192 to "8G"
                            ).forEach { (ram, label) ->
                                val isSelected = ramSizeMb == ram
                                val optionSafety = memoryManager.getMemorySafetyRecommendation(ram)
                                val isDanger = optionSafety is MemoryManager.SafetyResult.Danger

                                Button(
                                    onClick = { ramSizeMb = ram },
                                    enabled = !isDanger || isSelected,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) {
                                            if (isDanger) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                                        } else {
                                            Color(0xFF161B21)
                                        },
                                        contentColor = if (isSelected) {
                                            if (isDanger) Color.White else Color.Black
                                        } else {
                                            Color.White
                                        },
                                        disabledContainerColor = Color(0xFF101418),
                                        disabledContentColor = Color.DarkGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }

                        // Memory Safety Banner
                        when (safetyResult) {
                            is MemoryManager.SafetyResult.Danger -> {
                                Surface(
                                    color = Color(0x22FF5252),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Color(0x55FF5252)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(safetyResult.message, color = Color(0xFFFF8A80), fontSize = 10.sp)
                                    }
                                }
                            }
                            is MemoryManager.SafetyResult.Warning -> {
                                Surface(
                                    color = Color(0x22FFA000),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Color(0x55FFA000)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(safetyResult.message, color = Color(0xFFFFD54F), fontSize = 10.sp)
                                    }
                                }
                            }
                            is MemoryManager.SafetyResult.Safe -> {
                                Text("✅ Memory allocation is within safe Android host limits.", color = Color(0xFF00E676), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }

                    // 5. Storage Allocation Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("4. Allocate Virtual Storage", fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                            Text("$diskSizeGb GB Sparse Image", fontSize = 10.sp, color = Color(0xFFFFB300), fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = diskSizeGb.toFloat(),
                            onValueChange = { diskSizeGb = it.toInt() },
                            valueRange = 5f..120f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 6. Installation ISO File Picker
                    var selectedIsoUri by remember { mutableStateOf<android.net.Uri?>(null) }
                    var selectedIsoName by remember { mutableStateOf<String>("") }

                    com.example.vm.ui.components.ISOFilePickerComponent(
                        selectedIsoUri = selectedIsoUri,
                        onIsoSelected = { res ->
                            selectedIsoUri = res.uri
                            selectedIsoName = res.fileName
                            if (res.detectedOs.isNotBlank() && !res.detectedOs.contains("Generic")) {
                                selectedOs = res.detectedOs
                                vmName = res.fileName.substringBeforeLast('.').replace("[^a-zA-Z0-9_]".toRegex(), "_")
                            }
                        },
                        onIsoCleared = {
                            selectedIsoUri = null
                            selectedIsoName = ""
                        },
                        title = "Installation ISO Media (Optional)"
                    )

                    // 7. Deploy & Launch Action Button
                    Button(
                        onClick = {
                            val disksDir = File(context.filesDir, "app_disks").apply { mkdirs() }
                            val targetDisk = File(disksDir, "${vmName.lowercase()}_disk.img").absolutePath
                            val newConfig = VMConfig(
                                id = 0L,
                                name = vmName,
                                guestOsType = selectedOs,
                                guestArchCode = selectedArch.code,
                                cpuCores = cpuCores,
                                ramSizeMb = ramSizeMb,
                                diskSizeGb = diskSizeGb,
                                diskImagePath = targetDisk,
                                useHardwareVirtualization = useHardwareVirt,
                                networkEnabled = true,
                                serialConsoleEnabled = true
                            )
                            onLaunchVM(newConfig)
                        },
                        enabled = isMemSafe && vmName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_deploy_vm_from_dashboard")
                    ) {
                        Icon(Icons.Default.RocketLaunch, contentDescription = "Deploy", tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMemSafe) "Deploy & Start Virtual Machine" else "Unsafe Memory Allocation",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
