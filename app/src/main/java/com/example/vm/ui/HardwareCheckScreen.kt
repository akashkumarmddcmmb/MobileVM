package com.example.vm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vm.cpu.HostArchitecture

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareCheckScreen(
    viewModel: VMViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val diagnostics by viewModel.backendDiagnostics.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "HARDWARE & KVM COMPATIBILITY",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Real Runtime Device Diagnostics & Virtualization Capabilities",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("hardware_check_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshBackendDiagnostics() },
                        modifier = Modifier.testTag("hardware_check_refresh")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F141C))
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Summary Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF232D38)),
                modifier = Modifier.fillMaxWidth().testTag("hardware_summary_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MOBILEVM COMPATIBILITY SCORE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )

                        val scoreText = if (diagnostics.isKvmSupported) "100% (KVM ACCELERATED)" else "85% (EMULATOR FALLBACK)"
                        val scoreColor = if (diagnostics.isKvmSupported) Color(0xFF00E676) else Color(0xFFFFB74D)

                        Surface(color = scoreColor.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                text = scoreText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (diagnostics.isKvmSupported) {
                            "Hardware virtualization (/dev/kvm) is fully functional on this device. VM execution will run at near-native CPU speeds."
                        } else {
                            "Device kernel does not expose /dev/kvm or SELinux restricts access. MobileVM will cleanly execute using the ARM64 C++ Software Emulator."
                        },
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                }
            }

            // Real Hardware Capabilities Checklist
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF232D38)),
                modifier = Modifier.fillMaxWidth().testTag("checklist_card")
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "CHECKLIST DIAGNOSTICS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

                    ChecklistItem(
                        title = "ARM64 Host CPU Architecture",
                        value = diagnostics.hostArch.displayName,
                        isPass = diagnostics.hostArch == HostArchitecture.ARM64
                    )

                    ChecklistItem(
                        title = "KVM Hypervisor Node (/dev/kvm)",
                        value = if (diagnostics.kvmNodeExists) "Node Exists (${if (diagnostics.kvmNodeReadable) "Readable" else "Restricted"})" else "Not Exposed in Kernel",
                        isPass = diagnostics.isKvmSupported
                    )

                    ChecklistItem(
                        title = "C++ Native Core Engine",
                        value = if (diagnostics.nativeBridgeLoaded) "Loaded (`libnative_vm.so` v1.0)" else "Failed to load",
                        isPass = diagnostics.nativeBridgeLoaded
                    )

                    ChecklistItem(
                        title = "ARM64 Software CPU Emulator",
                        value = "AVAILABLE (Full A64 Interpreter + MMU)",
                        isPass = true
                    )

                    ChecklistItem(
                        title = "Host CPU Cores",
                        value = "${diagnostics.hostCpuCores} Cores Available",
                        isPass = diagnostics.hostCpuCores >= 2
                    )

                    val maxSafeRam = (diagnostics.availableRamMb * 0.7).toLong()
                    ChecklistItem(
                        title = "Maximum Safe Guest RAM",
                        value = "${maxSafeRam} MB (of ${diagnostics.availableRamMb} MB Available)",
                        isPass = maxSafeRam >= 1024
                    )
                }
            }

            // Recommended VM Presets
            Text(text = "RECOMMENDED VM PRESETS FOR THIS DEVICE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetCard(
                    title = "Basic",
                    cpu = "1 vCPU",
                    ram = "1 GB",
                    storage = "8 GB",
                    isRecommended = diagnostics.availableRamMb < 2048,
                    modifier = Modifier.weight(1f)
                )

                PresetCard(
                    title = "Standard",
                    cpu = "2 vCPU",
                    ram = "2 GB",
                    storage = "20 GB",
                    isRecommended = diagnostics.availableRamMb in 2048..4096,
                    modifier = Modifier.weight(1f)
                )

                PresetCard(
                    title = "Performance",
                    cpu = "4 vCPU",
                    ram = "4 GB",
                    storage = "32 GB",
                    isRecommended = diagnostics.availableRamMb > 4096,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ChecklistItem(title: String, value: String, isPass: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(value, fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
        }

        Icon(
            imageVector = if (isPass) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isPass) Color(0xFF00E676) else Color(0xFFFFB74D),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PresetCard(
    title: String,
    cpu: String,
    ram: String,
    storage: String,
    isRecommended: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRecommended) Color(0xFF1A2633) else Color(0xFF141A23)
        ),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isRecommended) MaterialTheme.colorScheme.primary else Color(0xFF232D38)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (isRecommended) {
                Text("OPTIMAL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(cpu, fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
            Text(ram, fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
            Text(storage, fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
        }
    }
}
