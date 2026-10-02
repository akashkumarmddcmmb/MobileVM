package com.example.vm.sysboot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.vm.sysboot.viewmodel.SysBootViewModel

@Composable
fun SysBootBootChainSimulatorScreen(viewModel: SysBootViewModel) {
    val failureSims by viewModel.failureSimulations.collectAsState()
    val recoveryState by viewModel.recoveryState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Boot Chain & Failure Matrix Inspector",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Analyze Boot Steps, C Modules & Fallback Recovery Matrix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SysBootStatusBadge(
                text = "BOOT CHAIN READY",
                containerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                contentColor = Color(0xFF10B981)
            )
        }

        // Call Graph Diagram Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "MobileVM Boot Loader Call Graph",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "main.c -> boot_manager.c -> boot_entries.c -> [windows.c / linux.c / recovery.c]",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                val chainSteps = listOf(
                    "MobileVM Power ON" to "CPU Reset Vector 0x00080000",
                    "ARM64 UEFI Firmware" to "QEMU_EFI.fd & LPDDR5 Memory Init",
                    "Hardware Detection" to "PCIe Bus & VirtIO Devices (Net/Block/GPU)",
                    "TPM 2.0 + Secure Boot" to "PCR 0-7 Extend & Platform Key (PK)",
                    "GPT / ESP Partition" to "Mount FAT32 System Volume",
                    "MobileVMBootManager.efi" to "main.c execution entry point"
                )

                chainSteps.forEachIndexed { idx, (title, desc) ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$title: ",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Failure Handling Matrix
        Text(
            text = "Simulate Failure Handling & Recovery Redirects",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val failures = listOf(
            "WINDOWS_MISSING" to "Windows Loader Absent (bootmgfw.efi)",
            "LINUX_MISSING" to "Linux Shim/GRUB Missing (shimx64.efi)",
            "ESP_DAMAGED" to "FAT32 System Volume Corrupt",
            "SECURE_BOOT_FAIL" to "Key Signature Mismatch",
            "STORAGE_UNAVAILABLE" to "VirtIO Block Controller Timeout"
        )

        failures.forEach { (type, label) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = label,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tests boot_fallback.c & Recovery redirect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.simulateFailureScenario(type) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("sim_fail_$type")
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Inject Fail", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Active Recovery Output
        if (recoveryState.isRunning || recoveryState.isCompleted) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Active Recovery & Repair Output Log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SysBootTerminalWindowCard(
                title = "step6_recovery_engine.log",
                logs = recoveryState.resultLog
            )
        }

        // Simulation History Log
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Failure Simulation & Recovery History",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (failureSims.isEmpty()) {
            Text(
                text = "No failure simulation events logged yet. Click 'Inject Fail' above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            failureSims.forEach { sim ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("sim_log_${sim.id}")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sim.failureType,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                            SysBootStatusBadge(
                                text = sim.status,
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Root Cause: ${sim.rootCause}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Action: ${sim.recoveryAction}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }
        }
    }
}
