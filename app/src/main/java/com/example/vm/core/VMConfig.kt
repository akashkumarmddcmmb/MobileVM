package com.example.vm.core

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.vm.cpu.GuestArchitecture

@Entity(tableName = "vm_configurations")
data class VMConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val guestOsType: String = "Linux ARM64",
    val osVersion: String = "24.04 LTS",
    val installationMode: String = "MODE_A_PREINSTALLED",
    val cpuBackendPreference: String = "AUTO", // "AUTO", "KVM", "ARM64_SOFTWARE_EMULATOR"
    val bootOrder: String = "VIRTUAL_DISK", // "VIRTUAL_DISK", "CD_ROM"
    val isoPath: String = "",
    val guestArchCode: Int = GuestArchitecture.ARM64.code,
    val cpuCores: Int = 2,
    val ramSizeMb: Int = 2048,
    val diskSizeGb: Int = 20,
    val diskImagePath: String = "",
    val useHardwareVirtualization: Boolean = true,
    val networkEnabled: Boolean = true,
    val networkMode: String = "NAT", // "OFF", "NAT", "USER_MODE"
    val dnsServer: String = "8.8.8.8",
    val serialConsoleEnabled: Boolean = true,
    val kernelImagePath: String = "",
    val initramfsPath: String = "",
    val kernelCmdline: String = "console=ttyAMA0,115200 root=/dev/vda1 rw init=/init earlycon=pl011,0x09000000",
    val consoleDevice: String = "ttyAMA0 (PL011 UART)",
    val hostname: String = "mobilevm-guest",
    val username: String = "ubuntu",
    val password: String = "ubuntu",
    val sshPublicKey: String = "",
    val terminalFontSize: Int = 12,
    val displayMode: String = "Serial / Terminal",
    val displayResolution: String = "1280x720",
    val baudRate: Int = 115200,
    val keyboardMode: String = "Android Keyboard",
    val mouseMode: String = "Touch Mouse",
    val debugLogging: Boolean = false,
    val cpuTracing: Boolean = false,
    val lastStarted: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getGuestArch(): GuestArchitecture {
        return GuestArchitecture.fromCode(guestArchCode)
    }

    fun getGuestArchName(): String {
        return getGuestArch().displayName
    }

    fun determineEffectiveCmdline(): String {
        if (kernelCmdline.isNotBlank()) return kernelCmdline
        return if (diskImagePath.isEmpty() && initramfsPath.isNotEmpty()) {
            "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init"
        } else {
            "console=ttyAMA0,115200 root=/dev/vda1 rw init=/init earlycon=pl011,0x09000000"
        }
    }
}
