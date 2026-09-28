package com.example.vm.guest.os

import android.content.Context
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelManager
import java.io.File

sealed class AutoConfigResult {
    data class Success(val config: VMConfig, val isReadyToBoot: Boolean, val message: String) : AutoConfigResult()
    data class Failure(val reason: String) : AutoConfigResult()
}

object OSAutoConfigurator {

    /**
     * Constructs a verified VMConfig matching the downloaded official OS artifacts.
     */
    fun createConfigurationForManifest(context: Context, manifest: OSManifest): AutoConfigResult {
        val files = OSStorageManager.getInstalledFiles(context, manifest)
            ?: return AutoConfigResult.Failure("Prerequisite OS artifacts not found. Please download the OS first.")

        val kernelFile = files.first
        val initrdFile = files.second

        // 1. Verify kernel binary format
        val kernelInfo = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
        if (!kernelInfo.isArm64Valid) {
            return AutoConfigResult.Failure("Kernel verification failed: Not a legitimate ARM64 kernel (${kernelInfo.formatDescription}).")
        }

        // 2. Verify initramfs format
        val initrdInfo = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
        if (!initrdInfo.exists || initrdInfo.sizeBytes == 0L) {
            return AutoConfigResult.Failure("Initramfs verification failed: File missing or empty.")
        }

        // 3. Create or resolve isolated virtual disk if manifest specifies disk
        val diskPath = if (manifest.diskUrl.isNotBlank()) {
            File(OSStorageManager.getOsPrivateDirectory(context, manifest.id), "disk.raw").absolutePath
        } else {
            ""
        }

        val config = VMConfig(
            id = 0, // Auto-generated ID in database
            name = "${manifest.name} ${manifest.version}",
            guestOsType = manifest.name,
            guestArchCode = GuestArchitecture.ARM64.code,
            cpuCores = 2,
            ramSizeMb = manifest.recommendedRamMb,
            diskSizeGb = manifest.minimumStorageGb,
            diskImagePath = diskPath,
            useHardwareVirtualization = false, // Software emulation backend for devices without /dev/kvm
            networkEnabled = false, // Virtual network explicitly reported as NOT IMPLEMENTED
            serialConsoleEnabled = true,
            kernelImagePath = kernelFile.absolutePath,
            initramfsPath = initrdFile.absolutePath,
            kernelCmdline = manifest.defaultKernelCmdline,
            consoleDevice = manifest.consoleDevice
        )

        val isReadyToBoot = kernelInfo.isArm64Valid && initrdInfo.exists

        return AutoConfigResult.Success(
            config = config,
            isReadyToBoot = isReadyToBoot,
            message = "VM automatically configured for ${manifest.name}. Verified ARM64 kernel and initramfs assigned."
        )
    }
}
