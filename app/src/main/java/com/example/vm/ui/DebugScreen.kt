package com.example.vm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vm.core.VMState

@Composable
fun DebugScreen(
    viewModel: VMViewModel,
    modifier: Modifier = Modifier
) {
    val diagnostics by viewModel.backendDiagnostics.collectAsStateWithLifecycle()
    val activeVM by viewModel.activeVM.collectAsStateWithLifecycle()
    val activeState = activeVM?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED
    val activeRegisters by activeVM?.cpuRegisters?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) }
    val clipboardManager = LocalClipboardManager.current
    var copyNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeVM, activeState) {
        viewModel.refreshBackendDiagnostics()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("debug_diagnostics_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header & Quick Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141A)),
                border = BorderStroke(1.dp, Color(0xFF1E2833)),
                modifier = Modifier.fillMaxWidth().testTag("debug_header_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Diagnostics Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Developer & System Debugger",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Live telemetry from actual system, kernel & native virtualization backend",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.refreshBackendDiagnostics()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_refresh_diagnostics")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Re-probe Hardware", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val report = buildDiagnosticsReport(diagnostics, activeVM != null, activeState.name)
                                clipboardManager.setText(AnnotatedString(report))
                                copyNotice = "Debug information copied to clipboard!"
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_copy_diagnostics")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Debug Info", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    copyNotice?.let { notice ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = notice,
                            fontSize = 11.sp,
                            color = Color(0xFF00E676),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 2. Hardware Virtualization Status & Availability (CRITICAL INTEGRITY)
        item {
            val isAccActuallyEnabled = diagnostics.activeVmIsHardwareAccelerated
            val isRunning = diagnostics.activeVmRunning

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isAccActuallyEnabled) Color(0xFF0D2318) else Color(0xFF1E170A)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isAccActuallyEnabled) Color(0xFF00E676) else Color(0xFFFFB300)
                ),
                modifier = Modifier.fillMaxWidth().testTag("card_accel_status")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isAccActuallyEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = "Acceleration Status",
                            tint = if (isAccActuallyEnabled) Color(0xFF00E676) else Color(0xFFFFB300),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isAccActuallyEnabled) "HARDWARE VIRTUALIZATION: ACTIVELY ACCELERATING"
                                       else if (diagnostics.isKvmSupported) "HARDWARE VIRTUALIZATION: AVAILABLE (KVM node ready)"
                                       else "HARDWARE VIRTUALIZATION: UNAVAILABLE (Emulation active)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAccActuallyEnabled) Color(0xFF00E676) else Color(0xFFFFB300),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (isAccActuallyEnabled) "Native ARM64 KVM/pKVM hypervisor active via kernel ioctl."
                                       else if (diagnostics.isKvmSupported) "KVM ioctl interface is accessible. Start VM to bind KVM core."
                                       else "Device kernel lacks /dev/kvm or permission denied. Supported software emulation core active.",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = if (isAccActuallyEnabled) Color(0x3300E676) else Color(0x33FFB300))
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagnosticRow("Hardware Virtualization Availability", if (diagnostics.isKvmSupported) "AVAILABLE (/dev/kvm present & accessible)" else "UNAVAILABLE (${diagnostics.kvmReason})")
                    DiagnosticRow("Selected CPU Backend", diagnostics.selectedCpuBackend)
                    DiagnosticRow("Active Runtime Core", diagnostics.activeVmBackendName)
                    DiagnosticRow("Acceleration Verified in Kernel", if (isAccActuallyEnabled) "YES (KVM ioctl verified)" else "NO (Software Emulation fallback)")
                    DiagnosticRow("Fallback Emulation Active", if (diagnostics.activeVmIsFallbackEmulation) "YES" else "NO")
                    DiagnosticRow("Backend Status Message", diagnostics.activeVmStatusMessage)
                }
            }
        }

        // 3. Host System & Platform Information (Host Arch, Android Version, RAM, CPU Count)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141A)),
                border = BorderStroke(1.dp, Color(0xFF1E2833)),
                modifier = Modifier.fillMaxWidth().testTag("card_host_platform")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Host System & Hardware Specifications",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagnosticRow("Host Architecture", diagnostics.hostArch.displayName)
                    DiagnosticRow("Android Version", diagnostics.osVersion)
                    DiagnosticRow("Host Linux Kernel", diagnostics.kernelRelease)
                    DiagnosticRow("Device Model", diagnostics.deviceModel)
                    DiagnosticRow("Host CPU Count (Cores)", "${diagnostics.hostCpuCores} logical processors")
                    DiagnosticRow("Available RAM", "${diagnostics.availableRamMb} MB / ${diagnostics.totalRamMb} MB total (${diagnostics.totalRamMb - diagnostics.availableRamMb} MB used)")
                    DiagnosticRow("Native C++ Bridge", if (diagnostics.nativeBridgeLoaded) "Loaded (libmobilevm_native.so)" else "JVM Fallback (Shared library missing)")

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = Color(0xFF1E2833))
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Hypervisor Device Node (/dev/kvm)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    DiagnosticRow("Node Present in /dev", if (diagnostics.kvmNodeExists) "YES" else "NO")
                    DiagnosticRow("Read Permission", if (diagnostics.kvmNodeReadable) "GRANTED" else "DENIED")
                    DiagnosticRow("Write Permission", if (diagnostics.kvmNodeWritable) "GRANTED" else "DENIED")
                    DiagnosticRow("KVM Kernel Diagnostic", diagnostics.kvmReason)
                }
            }
        }

        // 4. Virtual Machine Specifications & Images (VM allocated RAM, Guest arch, Selected disk, Kernel, Initramfs, VM state)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141A)),
                border = BorderStroke(1.dp, Color(0xFF1E2833)),
                modifier = Modifier.fillMaxWidth().testTag("card_vm_specs")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Virtual Machine & Image Configuration",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagnosticRow("VM Name", diagnostics.activeVmName)
                    DiagnosticRow("VM State", diagnostics.activeVmState.name)
                    DiagnosticRow("Guest Architecture", diagnostics.guestArchitecture)
                    DiagnosticRow("VM Allocated RAM", if (diagnostics.vmAllocatedRamMb > 0) "${diagnostics.vmAllocatedRamMb} MB (Host Mapped)" else "Not specified")
                    DiagnosticRow("VM vCPU Cores", if (diagnostics.vmCpuCores > 0) "${diagnostics.vmCpuCores} vCPU" else "Default (2 vCPU)")
                    DiagnosticRow("Selected Disk Image", diagnostics.diskImagePath)
                    DiagnosticRow("Kernel Image", diagnostics.kernelImagePath)
                    DiagnosticRow("Initramfs", diagnostics.initramfsPath)
                    DiagnosticRow("Native Engine Handle", if (diagnostics.nativeHandle != 0L) "0x${diagnostics.nativeHandle.toString(16).uppercase()}" else "None (Engine stopped)")
                    DiagnosticRow("Disk Sectors Total", diagnostics.totalSectors.toString())
                    DiagnosticRow("Disk Sectors Read", diagnostics.readSectors.toString())
                    DiagnosticRow("Disk Sectors Written", diagnostics.writtenSectors.toString())
                }
            }
        }

        // 5. Peripherals: USB Devices & Console Connection State
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141A)),
                border = BorderStroke(1.dp, Color(0xFF1E2833)),
                modifier = Modifier.fillMaxWidth().testTag("card_peripherals_telemetry")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "I/O Devices & Peripheral Telemetry",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    DiagnosticRow(
                        "Console Connection State",
                        if (diagnostics.consoleConnected) "CONNECTED (${diagnostics.consoleDeviceName})"
                        else "DISCONNECTED (Awaiting VM start)"
                    )
                    DiagnosticRow("Console Device", diagnostics.consoleDeviceName)

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = Color(0xFF1E2833))
                    Spacer(modifier = Modifier.height(6.dp))

                    DiagnosticRow(
                        "USB Devices (OTG)",
                        if (diagnostics.usbDeviceCount > 0) "${diagnostics.usbDeviceCount} physical device(s) detected"
                        else "0 devices (No USB-OTG peripherals connected)"
                    )

                    if (diagnostics.usbDeviceSummaries.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        diagnostics.usbDeviceSummaries.forEachIndexed { index, summary ->
                            Text(
                                text = "  [$index] $summary",
                                fontSize = 10.sp,
                                color = Color(0xFF80D8FF),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (activeRegisters.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Live CPU Registers (64-bit Hex)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Surface(
                            color = Color(0xFF050709),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0xFF1E2833)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                activeRegisters.entries.chunked(2).forEach { chunk ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        chunk.forEach { entry ->
                                            Text(
                                                text = "${entry.key}: 0x${entry.value.toString(16).uppercase().padStart(8, '0')}",
                                                fontSize = 10.sp,
                                                color = Color(0xFF00E676),
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
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
fun DiagnosticRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1.3f)
        )
    }
}

private fun buildDiagnosticsReport(
    d: BackendDiagnostics,
    isVmActive: Boolean,
    vmState: String
): String {
    return """
=== MOBILEVM DEVELOPER & DEBUG REPORT ===
Timestamp: ${System.currentTimeMillis()}

[HOST SYSTEM & HARDWARE]
Host Architecture: ${d.hostArch.displayName}
Android Version: ${d.osVersion}
Host Kernel: ${d.kernelRelease}
Device Model: ${d.deviceModel}
Host CPU Count: ${d.hostCpuCores} cores
Available RAM: ${d.availableRamMb} MB / ${d.totalRamMb} MB total

[HARDWARE VIRTUALIZATION & CPU BACKEND]
Hardware Virtualization Availability: ${if (d.isKvmSupported) "AVAILABLE" else "UNAVAILABLE (${d.kvmReason})"}
Selected CPU Backend: ${d.selectedCpuBackend}
Active CPU Backend: ${d.activeVmBackendName}
Hardware Virtualization Active: ${d.activeVmIsHardwareAccelerated}
Emulation Fallback Active: ${d.activeVmIsFallbackEmulation}
Native C++ Library Loaded: ${d.nativeBridgeLoaded}

[VIRTUAL MACHINE STATE & IMAGES]
VM Name: ${d.activeVmName}
VM State: ${d.activeVmState.name}
Guest Architecture: ${d.guestArchitecture}
VM Allocated RAM: ${d.vmAllocatedRamMb} MB
VM vCPU Cores: ${d.vmCpuCores}
Selected Disk Image: ${d.diskImagePath}
Kernel Image: ${d.kernelImagePath}
Initramfs: ${d.initramfsPath}
Native Engine Handle: 0x${d.nativeHandle.toString(16).uppercase()}
Virtual Disk Sectors Total: ${d.totalSectors}
Virtual Disk Sectors Read: ${d.readSectors}
Virtual Disk Sectors Written: ${d.writtenSectors}

[PERIPHERALS & CONNECTIONS]
Console Connection State: ${if (d.consoleConnected) "CONNECTED" else "DISCONNECTED"}
Console Device: ${d.consoleDeviceName}
USB Devices Connected: ${d.usbDeviceCount}
USB Device Details: ${if (d.usbDeviceSummaries.isEmpty()) "None" else d.usbDeviceSummaries.joinToString(", ")}
=========================================
    """.trimIndent()
}
