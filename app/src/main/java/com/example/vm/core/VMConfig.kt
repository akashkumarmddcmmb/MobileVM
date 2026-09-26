package com.example.vm.core

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.vm.cpu.GuestArchitecture

@Entity(tableName = "vm_configurations")
data class VMConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val guestOsType: String = "Linux ARM64",
    val guestArchCode: Int = GuestArchitecture.ARM64.code,
    val cpuCores: Int = 2,
    val ramSizeMb: Int = 2048,
    val diskSizeGb: Int = 20,
    val diskImagePath: String = "",
    val useHardwareVirtualization: Boolean = true,
    val networkEnabled: Boolean = true,
    val serialConsoleEnabled: Boolean = true,
    val kernelImagePath: String = "",
    val initramfsPath: String = "",
    val kernelCmdline: String = "console=ttyAMA0,115200 root=/dev/vda1 rw init=/init earlycon=pl011,0x09000000",
    val consoleDevice: String = "ttyAMA0 (PL011 UART)",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getGuestArch(): GuestArchitecture {
        return GuestArchitecture.fromCode(guestArchCode)
    }

    fun getGuestArchName(): String {
        return getGuestArch().displayName
    }
}
