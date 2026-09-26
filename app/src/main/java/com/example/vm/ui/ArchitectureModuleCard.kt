package com.example.vm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MobileVM Modular Architecture Overview Card.
 * Displays the verified modular tree hierarchy requested by the user:
 *
 * MobileVM
 * │
 * ├── Android App
 * ├── VM Manager
 * ├── CPU (ARM64 Virtualization, ARM64 Emulation, x86_64 Emulation [Future])
 * ├── Memory
 * ├── Storage
 * ├── Display
 * ├── Input (Touch, Keyboard, Mouse)
 * ├── USB (Host Manager, HID, Keyboard, Mouse, Storage)
 * ├── Console
 * ├── Network
 * ├── Guest (Kernel, Initramfs, Linux, Ubuntu, Windows [Future])
 * ├── JNI
 * └── Security
 */
@Composable
fun ArchitectureModuleCard(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1015)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF1E2833)),
        modifier = modifier.fillMaxWidth().testTag("architecture_module_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "Architecture Modules",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "MODULAR SUBSYSTEM ARCHITECTURE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Full Module Tree (13 Core Subsystems Active)",
                            fontSize = 10.sp,
                            color = Color.LightGray
                        )
                    }
                }

                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (expanded) "Collapse" else "View Tree",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = Color(0xFF070A0D),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "MobileVM ├── Android App ├── VM Manager ├── CPU ├── Memory ├── Storage ├── Display ├── Input ├── USB ├── Console ├── Network ├── Guest ├── JNI └── Security",
                    fontSize = 10.sp,
                    color = Color(0xFF80D8FF),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF1E2833), modifier = Modifier.padding(bottom = 4.dp))

                    ModuleTreeItem("📱 Android App", "UI (Jetpack Compose), Scoped Storage, Lifecycle, ViewModel", Color(0xFF00E676))
                    ModuleTreeItem("⚙️ VM Manager", "VMEngine, VMConfig, VMInstance, Room Persistence, Telemetry", Color(0xFF00E5FF))
                    ModuleTreeItem("🧠 CPU Subsystem", "• ARM64 Virtualization (KVM/pKVM)\n• ARM64 Emulation (Native C++ / AArch64 A64)\n• x86_64 Emulation [Future Roadmap]", Color(0xFF69F0AE))
                    ModuleTreeItem("💾 Memory", "HostByteBufferMemoryBackend, Direct Buffers, MMIO Bus Mapping", Color(0xFF40C4FF))
                    ModuleTreeItem("💽 Storage", "AndroidStorageDiskBackend, VirtIO-Blk (0x0A000000), Raw Ext4/MBR", Color(0xFFFFD54F))
                    ModuleTreeItem("🖥️ Display", "VirtIO-GPU, Framebuffer Scanout, Dirty-Rect Blitter, 60 FPS", Color(0xFFFF80AB))
                    ModuleTreeItem("🖱️ Input Subsystem", "• Touch (Absolute Tablet / Virtual Trackpad)\n• Keyboard (Scancode Injection)\n• Mouse (Relative Deltas / Scroll Wheel)", Color(0xFFB388FF))
                    ModuleTreeItem("🔌 USB Subsystem", "• Host Manager (Android UsbManager OTG)\n• HID (Gamepads, Barcodes)\n• Keyboard (HID Boot/Report)\n• Mouse (VirtIO Pointer)\n• Storage (SCSI BOT & Container Mode)", Color(0xFF00E676))
                    ModuleTreeItem("📟 Console", "PL011 UART @ 0x09000000, VT100 / ANSI Interactive Terminal", Color(0xFF80D8FF))
                    ModuleTreeItem("🌐 Network Subsystem", "VirtIO-Net @ 0x0D000000, DHCP (10.0.2.15), DNS Proxy, User-Mode NAT", Color(0xFFFFAB40))
                    ModuleTreeItem("🐧 Guest Subsystem", "• Kernel (ARM64 Linux Image / vmlinuz)\n• Initramfs (CPIO Gzip /init shell)\n• Linux (PL011 earlycon, ttyAMA0, ext4)\n• Ubuntu (ARM64 Rootfs, bash, apt, gcc, python, XFCE4)\n• Windows [Future Target: EDK2 UEFI, ACPI 6.0, VirtIO-Win]", Color(0xFF00E676))
                    ModuleTreeItem("🔗 JNI Native Bridge", "libmobilevm_native.so, KVM ioctls, C++ vCPU loop, MMIO dispatcher", Color(0xFFE040FB))
                    ModuleTreeItem("🛡️ Security & Sandbox", "Zero Root, Scoped Storage Containment, Read-Only System Partitions", Color(0xFF00E676))
                }
            }
        }
    }
}

@Composable
private fun ModuleTreeItem(name: String, description: String, accentColor: Color) {
    Surface(
        color = Color(0xFF080C0F),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Color(0xFF161E28)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 10.sp,
                color = Color.LightGray,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp
            )
        }
    }
}
