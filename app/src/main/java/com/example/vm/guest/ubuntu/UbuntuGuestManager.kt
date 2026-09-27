package com.example.vm.guest.ubuntu

import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelManager
import java.io.File

/**
 * Ubuntu ARM64 Guest Module.
 * Manages Ubuntu root filesystem specifications, package ecosystem prerequisites,
 * dynamic linker requirements, and official Canonical ARM64 artifacts.
 */
data class UbuntuSystemProfile(
    val releaseName: String = "Ubuntu 24.04 LTS (Noble Numbat)",
    val architecture: String = "aarch64",
    val dynamicLinkerPath: String = "/lib/ld-linux-aarch64.so.1",
    val defaultShell: String = "/bin/bash",
    val corePackages: List<String> = listOf("apt", "bash", "python3", "gcc", "g++", "git", "curl", "sudo"),
    val recommendedDesktop: String = "XFCE4 (Lightweight X11 Desktop)",
    val rootfsMinimumSizeGb: Int = 8,
    val recommendedRamMb: Int = 2048,
    val defaultKernelCmdline: String = "console=ttyAMA0,115200 root=/dev/vda1 rw earlycon=pl011,0x09000000 init=/init",
    val virtioRootDevice: String = "/dev/vda1"
)

sealed class UbuntuArtifactValidation {
    data class Ready(
        val kernelPath: String,
        val initramfsPath: String,
        val diskPath: String,
        val message: String
    ) : UbuntuArtifactValidation()

    data class MissingPrerequisite(
        val missingItem: String,
        val description: String,
        val remedy: String
    ) : UbuntuArtifactValidation()
}

object UbuntuGuestManager {
    fun getProfile(): UbuntuSystemProfile = UbuntuSystemProfile()

    fun verifyRootfsLayout(hasValidLinker: Boolean, hasEtcOsRelease: Boolean): Boolean {
        return hasValidLinker && hasEtcOsRelease
    }

    /**
     * Validates that all three mandatory Canonical Ubuntu ARM64 artifacts are present
     * and strictly meet architecture, format, and sandbox requirements.
     */
    fun validateUbuntuPrerequisites(
        kernelPath: String,
        initramfsPath: String,
        diskPath: String
    ): UbuntuArtifactValidation {
        // 1. Kernel validation
        if (kernelPath.isBlank()) {
            return UbuntuArtifactValidation.MissingPrerequisite(
                missingItem = "Ubuntu ARM64 Kernel (vmlinuz-generic)",
                description = "No ARM64 Linux kernel configured.",
                remedy = "Import an authentic Ubuntu ARM64 'vmlinuz-generic' binary into app storage. Do not use an ISO file."
            )
        }

        val kernelInfo = GuestKernelManager.inspectKernel(kernelPath)
        if (!kernelInfo.exists) {
            return UbuntuArtifactValidation.MissingPrerequisite(
                missingItem = "Kernel file missing on storage",
                description = "Kernel file not found at '$kernelPath'.",
                remedy = "Verify the kernel file is located in the application private storage directory."
            )
        }

        if (kernelInfo.isIsoImage) {
            return UbuntuArtifactValidation.MissingPrerequisite(
                missingItem = "Unsupported ISO in Kernel Slot",
                description = kernelInfo.formatDescription,
                remedy = "Extract 'vmlinuz-generic' and 'initrd-generic' from the Ubuntu ISO or download Ubuntu cloud kernel/initrd artifacts directly."
            )
        }

        if (!kernelInfo.isArm64Valid) {
            return UbuntuArtifactValidation.MissingPrerequisite(
                missingItem = "Invalid Kernel Binary Format",
                description = kernelInfo.formatDescription,
                remedy = "Provide a valid 64-bit ARM Linux kernel image with 0x644D5241 magic or ELF64 header."
            )
        }

        // 2. Initramfs validation
        if (initramfsPath.isNotBlank()) {
            val initrdInfo = GuestInitramfsManager.inspectInitramfs(initramfsPath)
            if (!initrdInfo.exists) {
                return UbuntuArtifactValidation.MissingPrerequisite(
                    missingItem = "Initramfs file missing",
                    description = "Configured initramfs not found at '$initramfsPath'.",
                    remedy = "Import a valid matching Ubuntu 'initrd-generic' CPIO gzip archive."
                )
            }
            if (initrdInfo.sizeBytes == 0L) {
                return UbuntuArtifactValidation.MissingPrerequisite(
                    missingItem = "Empty Initramfs file",
                    description = "Configured initramfs file is 0 bytes.",
                    remedy = "Provide a non-empty CPIO gzip archive containing the Ubuntu init scripts."
                )
            }
        }

        // 3. Virtual Disk validation
        if (diskPath.isNotBlank()) {
            val diskFile = File(diskPath)
            if (!diskFile.exists() || diskFile.length() < 512) {
                return UbuntuArtifactValidation.MissingPrerequisite(
                    missingItem = "Ubuntu Disk Image Missing or Corrupt",
                    description = "Virtual disk at '$diskPath' does not exist or is smaller than one sector (512 bytes).",
                    remedy = "Allocate a virtual disk or import an Ubuntu raw disk image (.img) via VM settings."
                )
            }
        }

        return UbuntuArtifactValidation.Ready(
            kernelPath = kernelPath,
            initramfsPath = initramfsPath,
            diskPath = diskPath,
            message = "All Ubuntu ARM64 boot prerequisites are satisfied."
        )
    }

    fun explainIsoRestriction(): String {
        return "An Ubuntu Desktop/Server ISO is an optical disc installation medium (ISO 9660) " +
               "designed for UEFI bootloaders (like GRUB/EDK2) on physical PCs or hypervisors with virtual CD-ROM drives. " +
               "MobileVM ARM64 direct Linux boot boots the kernel directly into memory according to the Linux ARM64 boot protocol, " +
               "which requires: 1) ARM64 vmlinuz-generic kernel, 2) matching initrd-generic, and 3) raw VirtIO block disk image."
    }
}
