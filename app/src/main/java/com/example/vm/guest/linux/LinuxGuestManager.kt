package com.example.vm.guest.linux

/**
 * Generic Linux Guest Module.
 * Defines standard Linux boot parameters, devicetree setup, and standard TTY consoles.
 */
data class LinuxGuestProfile(
    val distroName: String = "Generic ARM64 Linux",
    val defaultCmdline: String = "console=ttyAMA0,115200 root=/dev/vda rw earlycon=pl011,0x09000000",
    val defaultConsole: String = "ttyAMA0 (PL011 UART)",
    val requiredVirtioDevices: List<String> = listOf("virtio-blk", "virtio-net", "virtio-gpu", "virtio-input")
)

object LinuxGuestManager {
    fun getStandardProfile(): LinuxGuestProfile = LinuxGuestProfile()
}
