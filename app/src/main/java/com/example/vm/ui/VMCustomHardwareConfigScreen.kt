package com.example.vm.ui

import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.memory.MemoryManager
import java.io.File

/**
 * Dedicated Hardware Configuration UI Screen to specify custom vCPU counts,
 * Memory (RAM) allocation in MB, and virtual Disk Image resizing options for new VM instances.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMCustomHardwareConfigScreen(
    viewModel: VMViewModel,
    initialConfig: VMConfig? = null,
    onDismiss: () -> Unit,
    onLaunchVm: (VMConfig) -> Unit = {}
) {
    val context = LocalContext.current
    val hostProcessors = remember { Runtime.getRuntime().availableProcessors() }
    val hostMemoryStats = remember { viewModel.memoryManager.getHostMemoryStats() }

    // StatFs for host disk space calculation
    val hostFreeStorageGb = remember {
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            (stat.availableBytes / (1024L * 1024L * 1024L)).toInt()
        } catch (_: Exception) {
            32
        }
    }

    var vmName by remember { mutableStateOf(initialConfig?.name ?: "Custom_ARM64_VM") }
    var selectedOsType by remember { mutableStateOf(initialConfig?.guestOsType ?: "Ubuntu 24.04 ARM64") }
    var selectedArch by remember { mutableStateOf(GuestArchitecture.ARM64) }

    // 1. Custom vCPU count state
    var customCpuCores by remember { mutableIntStateOf(initialConfig?.cpuCores?.coerceAtLeast(1) ?: 2) }
    var cpuInputText by remember { mutableStateOf(customCpuCores.toString()) }

    // 2. Custom RAM in MB state
    var customRamMb by remember { mutableIntStateOf(initialConfig?.ramSizeMb?.coerceAtLeast(512) ?: 2048) }
    var ramInputText by remember { mutableStateOf(customRamMb.toString()) }

    // 3. Custom Disk Resizing in GB state
    var customDiskGb by remember { mutableIntStateOf(initialConfig?.diskSizeGb?.coerceAtLeast(5) ?: 30) }
    var diskInputText by remember { mutableStateOf(customDiskGb.toString()) }
    var isSparseDisk by remember { mutableStateOf(true) }

    // 4. Hardware Acceleration & Networking
    var useHardwareVirt by remember { mutableStateOf(initialConfig?.useHardwareVirtualization ?: true) }
    var networkEnabled by remember { mutableStateOf(initialConfig?.networkEnabled ?: true) }

    // Memory Safety Check
    val safetyResult = remember(customRamMb) {
        viewModel.memoryManager.getMemorySafetyRecommendation(customRamMb)
    }
    val isMemSafe = safetyResult !is MemoryManager.SafetyResult.Danger

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (initialConfig == null) "New VM Hardware Configurator" else "Customize VM Hardware",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Custom vCPUs, RAM (MB) & Disk Resizing",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("btn_close_custom_hardware")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF090D12))
            )
        },
        containerColor = Color(0xFF070A0E)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // VM Identification Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10151D)),
                border = BorderStroke(1.dp, Color(0xFF1F2937)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Virtual Machine Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    OutlinedTextField(
                        value = vmName,
                        onValueChange = { vmName = it },
                        label = { Text("Instance Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_vm_name"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF2B3848)
                        )
                    )

                    Text("Guest Operating System", fontSize = 11.sp, color = Color.Gray)
                    val osOptions = listOf(
                        "Ubuntu 24.04 ARM64",
                        "Windows 11 ARM64",
                        "Debian 12 ARM64",
                        "Kali Linux ARM64",
                        "Alpine Linux"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        osOptions.take(3).forEach { os ->
                            FilterChip(
                                selected = selectedOsType == os,
                                onClick = {
                                    selectedOsType = os
                                    if (os.contains("Windows")) {
                                        customCpuCores = 4.coerceAtMost(hostProcessors)
                                        cpuInputText = customCpuCores.toString()
                                        customRamMb = 4096
                                        ramInputText = "4096"
                                        customDiskGb = 40
                                        diskInputText = "40"
                                    } else if (os.contains("Alpine")) {
                                        customCpuCores = 1
                                        cpuInputText = "1"
                                        customRamMb = 512
                                        ramInputText = "512"
                                        customDiskGb = 10
                                        diskInputText = "10"
                                    }
                                },
                                label = { Text(os.substringBefore(" "), fontSize = 10.sp) }
                            )
                        }
                    }
                }
            }

            // SECTION 1: CUSTOM vCPU COUNT
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10151D)),
                border = BorderStroke(1.dp, Color(0xFF1F2937)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("card_custom_vcpu_config")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = "CPU", tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("2. Custom vCPU Cores Allocation", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Surface(
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$customCpuCores vCPUs",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "Physical Android Host Topology: $hostProcessors Processors Available",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )

                    // Numeric Manual Input + Quick Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = cpuInputText,
                            onValueChange = { text ->
                                cpuInputText = text.filter { it.isDigit() }
                                val parsed = cpuInputText.toIntOrNull()
                                if (parsed != null && parsed in 1..16) {
                                    customCpuCores = parsed
                                }
                            },
                            label = { Text("vCPU Count (1-16)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_custom_cpu_cores"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF2B3848)
                            )
                        )

                        listOf(1, 2, 4, 8).forEach { cores ->
                            FilterChip(
                                selected = customCpuCores == cores,
                                onClick = {
                                    customCpuCores = cores
                                    cpuInputText = cores.toString()
                                },
                                label = { Text("${cores}c", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                            )
                        }
                    }

                    // Slider Stepper
                    Slider(
                        value = customCpuCores.toFloat(),
                        onValueChange = {
                            customCpuCores = it.toInt()
                            cpuInputText = customCpuCores.toString()
                        },
                        valueRange = 1f..16f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth().testTag("slider_custom_vcpu")
                    )
                }
            }

            // SECTION 2: CUSTOM RAM ALLOCATION IN MB
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10151D)),
                border = BorderStroke(1.dp, if (!isMemSafe) Color(0xFFFF5252).copy(alpha = 0.5f) else Color(0xFF1F2937)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("card_custom_ram_config")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, contentDescription = "RAM", tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("3. Custom Memory Allocation (MB)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Surface(
                            color = Color(0xFF00E676).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$customRamMb MB (${String.format("%.1f", customRamMb / 1024.0)} GB)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "Android Host RAM: ${hostMemoryStats.availableMb} MB Available / ${hostMemoryStats.totalMb} MB Total",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )

                    // Numeric Manual Input in MB + Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = ramInputText,
                            onValueChange = { text ->
                                ramInputText = text.filter { it.isDigit() }
                                val parsed = ramInputText.toIntOrNull()
                                if (parsed != null && parsed in 256..16384) {
                                    customRamMb = parsed
                                }
                            },
                            label = { Text("RAM in MB (512 - 16384)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_custom_ram_mb"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E676),
                                unfocusedBorderColor = Color(0xFF2B3848)
                            )
                        )

                        listOf(1024, 2048, 4096, 6144, 8192).forEach { mb ->
                            FilterChip(
                                selected = customRamMb == mb,
                                onClick = {
                                    customRamMb = mb
                                    ramInputText = mb.toString()
                                },
                                label = { Text("${mb / 1024}G", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                            )
                        }
                    }

                    // Slider Stepper
                    Slider(
                        value = customRamMb.toFloat(),
                        onValueChange = {
                            customRamMb = (it.toInt() / 256) * 256
                            ramInputText = customRamMb.toString()
                        },
                        valueRange = 512f..8192f,
                        steps = 29,
                        modifier = Modifier.fillMaxWidth().testTag("slider_custom_ram")
                    )

                    // Live Memory Safety Status Banner
                    when (safetyResult) {
                        is MemoryManager.SafetyResult.Danger -> {
                            Surface(
                                color = Color(0x22FF5252),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0x55FF5252)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = "Danger", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
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
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(safetyResult.message, color = Color(0xFFFFD54F), fontSize = 10.sp)
                                }
                            }
                        }
                        is MemoryManager.SafetyResult.Safe -> {
                            Text(safetyResult.details, color = Color(0xFF00E676), fontSize = 10.sp)
                        }
                    }
                }
            }

            // SECTION 3: CUSTOM DISK IMAGE RESIZING OPTIONS
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10151D)),
                border = BorderStroke(1.dp, Color(0xFF1F2937)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("card_custom_disk_config")
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = "Disk", tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("4. Virtual Disk Image Resizing", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Surface(
                            color = Color(0xFFFFB300).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$customDiskGb GB Capacity",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB300),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "Device Storage: ~$hostFreeStorageGb GB Free Physical Space on Host",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )

                    // Numeric Manual Input in GB + Quick Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = diskInputText,
                            onValueChange = { text ->
                                diskInputText = text.filter { it.isDigit() }
                                val parsed = diskInputText.toIntOrNull()
                                if (parsed != null && parsed in 5..500) {
                                    customDiskGb = parsed
                                }
                            },
                            label = { Text("Disk Capacity (5 - 500 GB)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_custom_disk_gb"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFFFB300),
                                unfocusedBorderColor = Color(0xFF2B3848)
                            )
                        )

                        listOf(10, 20, 40, 64, 120).forEach { gb ->
                            FilterChip(
                                selected = customDiskGb == gb,
                                onClick = {
                                    customDiskGb = gb
                                    diskInputText = gb.toString()
                                },
                                label = { Text("${gb}G", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                            )
                        }
                    }

                    // Slider Stepper
                    Slider(
                        value = customDiskGb.toFloat(),
                        onValueChange = {
                            customDiskGb = it.toInt()
                            diskInputText = customDiskGb.toString()
                        },
                        valueRange = 5f..120f,
                        steps = 22,
                        modifier = Modifier.fillMaxWidth().testTag("slider_custom_disk")
                    )

                    // Sparse File Allocation Option
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sparse Virtual Disk Allocation", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                text = "Sparse images only consume storage space when written to by the guest OS.",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = isSparseDisk,
                            onCheckedChange = { isSparseDisk = it },
                            modifier = Modifier.testTag("switch_sparse_disk")
                        )
                    }
                }
            }

            // SECTION 4: ACTIONS (SAVE & LAUNCH)
            Button(
                onClick = {
                    val disksDir = File(context.filesDir, "app_disks").apply { mkdirs() }
                    val targetDisk = File(disksDir, "${vmName.replace("\\s+".toRegex(), "_").lowercase()}_disk.img").absolutePath
                    val newConfig = VMConfig(
                        id = initialConfig?.id ?: 0L,
                        name = vmName,
                        guestOsType = selectedOsType,
                        guestArchCode = selectedArch.code,
                        cpuCores = customCpuCores,
                        ramSizeMb = customRamMb,
                        diskSizeGb = customDiskGb,
                        diskImagePath = targetDisk,
                        useHardwareVirtualization = useHardwareVirt,
                        networkEnabled = networkEnabled,
                        serialConsoleEnabled = true
                    )

                    viewModel.createOrSaveVM(newConfig) { saved ->
                        Toast.makeText(context, "Configured '${saved.name}' (${saved.cpuCores} vCPUs, ${saved.ramSizeMb}MB RAM, ${saved.diskSizeGb}GB Disk)", Toast.LENGTH_SHORT).show()
                        onLaunchVm(saved)
                    }
                },
                enabled = isMemSafe && vmName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_save_and_launch_custom_vm")
            ) {
                Icon(Icons.Default.RocketLaunch, contentDescription = "Deploy", tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMemSafe) "Save Configuration & Deploy VM" else "Unsafe Memory Allocation",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
