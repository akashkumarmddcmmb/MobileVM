package com.example.vm.core

import android.content.Context
import android.util.Log
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.firmware.UefiFirmwareManager
import com.example.vm.guest.iso.ISOManager
import com.example.vm.storage.AndroidStorageDiskBackend
import java.io.File

/**
 * VMBootManager: Authoritative Boot Manager and Boot Selection Engine.
 *
 * Implements real PC-style boot selection:
 * - Windows Boot Manager (\EFI\Microsoft\Boot\bootmgfw.efi)
 * - Linux EFI Bootloader (\EFI\BOOT\BOOTAA64.EFI / grubaa64.efi)
 * - Installed Persistent Virtual Disk
 * - Installation Media (ISO / CD-ROM)
 * - Direct ARM64 Kernel Boot
 * - Recovery / Diagnostics
 *
 * Maintains persistent boot order across reboots and synchronizes with EDK2 NVRAM variables.
 */
object VMBootManager {
    private const val TAG = "VMBootManager"

    enum class BootDeviceType {
        WINDOWS_BOOT_MANAGER,
        LINUX_EFI_LOADER,
        INSTALLED_VIRTUAL_DISK,
        INSTALLATION_ISO,
        DIRECT_KERNEL_IMAGE,
        RECOVERY_DIAGNOSTICS
    }

    data class BootEntry(
        val type: BootDeviceType,
        val title: String,
        val description: String,
        val isAvailable: Boolean,
        val filePath: String,
        val priority: Int
    )

    data class ResolvedBootTarget(
        val primaryDevice: BootDeviceType,
        val targetPath: String,
        val isUefiBoot: Boolean,
        val isIsoBoot: Boolean,
        val isDirectKernelBoot: Boolean,
        val description: String
    )

    /**
     * Enumerates all boot options currently available for the given VM configuration.
     */
    fun getAvailableBootEntries(context: Context, config: VMConfig): List<BootEntry> {
        val entries = mutableListOf<BootEntry>()

        // 1. Installed Virtual Disk / OS
        val diskFile = if (config.diskImagePath.isNotBlank()) File(config.diskImagePath) else null
        val diskExists = diskFile != null && diskFile.exists() && diskFile.length() >= 512
        val diskBackend = AndroidStorageDiskBackend(context)
        val partitionScheme = if (diskExists) {
            diskBackend.detectPartitionScheme(diskFile!!.absolutePath)
        } else null

        val isInstalledDisk = config.osInstalled ||
                (partitionScheme != null && (partitionScheme.isEfiBootable || partitionScheme.isLegacyBootable))

        if (config.guestOsType.contains("Windows", ignoreCase = true)) {
            entries.add(
                BootEntry(
                    type = BootDeviceType.WINDOWS_BOOT_MANAGER,
                    title = "Windows Boot Manager",
                    description = if (isInstalledDisk) "Installed Windows ARM64 (GPT / ESP)" else "Not installed on virtual disk",
                    isAvailable = isInstalledDisk,
                    filePath = config.diskImagePath,
                    priority = if (isInstalledDisk && config.bootOrder != "CD_ROM") 1 else 2
                )
            )
        } else {
            entries.add(
                BootEntry(
                    type = BootDeviceType.LINUX_EFI_LOADER,
                    title = "Linux EFI Bootloader",
                    description = if (isInstalledDisk) "Installed Linux System (GPT/MBR rootfs)" else "Not installed on virtual disk",
                    isAvailable = isInstalledDisk,
                    filePath = config.diskImagePath,
                    priority = if (isInstalledDisk && config.bootOrder != "CD_ROM") 1 else 2
                )
            )
        }

        // 2. Installation Media (ISO)
        val isoFile = if (config.isoPath.isNotBlank()) File(config.isoPath) else null
        val isoExists = isoFile != null && isoFile.exists() && isoFile.length() > 2048
        entries.add(
            BootEntry(
                type = BootDeviceType.INSTALLATION_ISO,
                title = "Installation Media (CD/DVD ISO)",
                description = if (isoExists) "Installer: ${isoFile?.name}" else "No ISO media attached",
                isAvailable = isoExists,
                filePath = config.isoPath,
                priority = if (!isInstalledDisk || config.bootOrder == "CD_ROM") 1 else 3
            )
        )

        // 3. Direct ARM64 Kernel
        val kernelFile = if (config.kernelImagePath.isNotBlank()) File(config.kernelImagePath) else null
        val kernelExists = kernelFile != null && kernelFile.exists() && kernelFile.length() >= 512
        if (kernelExists || config.kernelImagePath.isNotBlank()) {
            entries.add(
                BootEntry(
                    type = BootDeviceType.DIRECT_KERNEL_IMAGE,
                    title = "Direct ARM64 Kernel Boot",
                    description = if (kernelExists) "Kernel: ${kernelFile?.name}" else "Kernel file not found",
                    isAvailable = kernelExists,
                    filePath = config.kernelImagePath,
                    priority = if (config.bootOrder == "DIRECT_KERNEL") 1 else 4
                )
            )
        }

        return entries.sortedBy { it.priority }
    }

    /**
     * Resolves the authoritative boot target for starting the VM.
     * Enforces that once an operating system is installed onto the virtual disk,
     * subsequent boots automatically launch the installed OS from disk without requiring the ISO.
     */
    fun resolveEffectiveBootTarget(context: Context, config: VMConfig): ResolvedBootTarget {
        val diskFile = if (config.diskImagePath.isNotBlank()) File(config.diskImagePath) else null
        val diskExists = diskFile != null && diskFile.exists() && diskFile.length() >= 512
        val isoFile = if (config.isoPath.isNotBlank()) File(config.isoPath) else null
        val isoExists = isoFile != null && isoFile.exists() && isoFile.length() > 2048

        val isWindows = config.guestOsType.contains("Windows", ignoreCase = true)

        // 1. Direct Kernel Boot ONLY if explicitly requested via bootOrder
        if (config.bootOrder == "DIRECT_KERNEL") {
            return ResolvedBootTarget(
                primaryDevice = BootDeviceType.DIRECT_KERNEL_IMAGE,
                targetPath = config.kernelImagePath,
                isUefiBoot = false,
                isIsoBoot = false,
                isDirectKernelBoot = true,
                description = "Direct ARM64 Kernel: ${File(config.kernelImagePath).name}"
            )
        }

        // 2. If OS is already installed on the virtual disk, and user did not explicitly force CD_ROM:
        if (config.osInstalled && diskExists && config.bootOrder != "CD_ROM") {
            return if (isWindows) {
                ResolvedBootTarget(
                    primaryDevice = BootDeviceType.WINDOWS_BOOT_MANAGER,
                    targetPath = config.diskImagePath,
                    isUefiBoot = true,
                    isIsoBoot = false,
                    isDirectKernelBoot = false,
                    description = "Booting installed Windows from virtual disk (${diskFile?.name})"
                )
            } else {
                ResolvedBootTarget(
                    primaryDevice = BootDeviceType.INSTALLED_VIRTUAL_DISK,
                    targetPath = config.diskImagePath,
                    isUefiBoot = true,
                    isIsoBoot = false,
                    isDirectKernelBoot = false,
                    description = "Booting installed Linux from virtual disk (${diskFile?.name})"
                )
            }
        }

        // 3. If Installer ISO is present and (OS not yet installed OR user chose CD_ROM boot):
        if (isoExists && (config.bootOrder == "CD_ROM" || !config.osInstalled)) {
            return ResolvedBootTarget(
                primaryDevice = BootDeviceType.INSTALLATION_ISO,
                targetPath = config.isoPath,
                isUefiBoot = true,
                isIsoBoot = true,
                isDirectKernelBoot = false,
                description = "Booting OS Installer from optical media (${isoFile?.name})"
            )
        }

        // 4. Default: Virtual disk if exists
        if (diskExists) {
            return ResolvedBootTarget(
                primaryDevice = if (isWindows) BootDeviceType.WINDOWS_BOOT_MANAGER else BootDeviceType.INSTALLED_VIRTUAL_DISK,
                targetPath = config.diskImagePath,
                isUefiBoot = true,
                isIsoBoot = false,
                isDirectKernelBoot = false,
                description = "Booting virtual disk (${diskFile?.name})"
            )
        }

        // 5. Fallback: Direct kernel if configured
        if (config.kernelImagePath.isNotBlank()) {
            return ResolvedBootTarget(
                primaryDevice = BootDeviceType.DIRECT_KERNEL_IMAGE,
                targetPath = config.kernelImagePath,
                isUefiBoot = false,
                isIsoBoot = false,
                isDirectKernelBoot = true,
                description = "Direct ARM64 Kernel: ${config.kernelImagePath}"
            )
        }

        // 6. Media missing
        return ResolvedBootTarget(
            primaryDevice = BootDeviceType.RECOVERY_DIAGNOSTICS,
            targetPath = "",
            isUefiBoot = false,
            isIsoBoot = false,
            isDirectKernelBoot = false,
            description = "No valid boot media configured"
        )
    }

    /**
     * Completes OS installation onto persistent virtual disk and persists boot order.
     */
    fun markOsInstallationComplete(context: Context, config: VMConfig): VMConfig {
        Log.i(TAG, "OS Installation completed for VM '${config.name}'. Updating boot order to prioritize virtual disk.")
        UefiFirmwareManager.getOrInitializeNvramStore(context, GuestArchitecture.ARM64)
        return config.copy(
            osInstalled = true,
            bootOrder = "VIRTUAL_DISK"
        )
    }

    /**
     * Ejects the installer ISO after installation.
     */
    fun ejectInstallerIso(config: VMConfig): VMConfig {
        Log.i(TAG, "Ejecting installer ISO for VM '${config.name}'.")
        return config.copy(
            isoPath = "",
            bootOrder = "VIRTUAL_DISK"
        )
    }
}
