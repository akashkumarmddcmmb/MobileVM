package com.example.vm.core

import android.content.Context
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelManager
import com.example.vm.memory.MemoryManager
import com.example.vm.nativebridge.NativeVMBinding
import com.example.vm.storage.AndroidStorageDiskBackend
import java.io.File

/**
 * Pre-Flight Start Validator for MobileVM.
 * Performs deep, systematic validation of all prerequisites before launching the VM engine:
 * 1. Configuration validity & guest architecture
 * 2. Host physical memory capacity & safety margins
 * 3. Sandboxed storage isolation & traversal prevention
 * 4. ARM64 Linux Kernel binary validation (existence, readability, magic bytes)
 * 5. Initramfs archive validation (existence, readability, gzip/cpio, /init entry)
 * 6. Virtual disk validation (existence, size, permissions)
 * 7. /dev/kvm availability & backend fallback determination
 */
object VMStartValidator {

    sealed class ValidationResult {
        data class Valid(
            val isHardwareAccelerated: Boolean,
            val cpuBackendName: String,
            val statusMessage: String
        ) : ValidationResult()

        data class Invalid(val error: VMError) : ValidationResult()
    }

    fun validate(context: Context, config: VMConfig): ValidationResult {
        val diskBackend = AndroidStorageDiskBackend(context)
        val memoryManager = MemoryManager(context)
        val hostArch = HostArchitecture.detect()

        // 1. Architecture Check
        if (config.getGuestArch() != GuestArchitecture.ARM64) {
            return ValidationResult.Invalid(
                VMError(
                    category = VMErrorCategory.UNSUPPORTED_ARCHITECTURE,
                    summary = "Unsupported Guest Architecture: ${config.getGuestArchName()}",
                    technicalDetails = "Current engine build exclusively supports ARM64 (AArch64) virtual machines.",
                    suggestedRemedy = "Select an ARM64 guest profile (e.g., Ubuntu ARM64, Debian ARM64, Alpine ARM64)."
                )
            )
        }

        // 2. Host Physical Memory Safety
        val hostStats = memoryManager.getHostMemoryStats()
        val isWindows = config.guestOsType.contains("Windows", ignoreCase = true)
        val isKvmMode = config.useHardwareVirtualization && (config.cpuBackendPreference != "ARM64_SOFTWARE_EMULATOR") && NativeVMBinding.isLoaded() && NativeVMBinding.nativeIsKvmSupported()

        if (isWindows && config.ramSizeMb < 2048) {
            return ValidationResult.Invalid(
                VMError(
                    category = VMErrorCategory.INSUFFICIENT_RAM,
                    summary = "Insufficient RAM for Windows ARM64 (${config.ramSizeMb} MB).",
                    technicalDetails = "Windows 11/10 on ARM requires at least 2048 MB RAM (recommended: 4096 MB).",
                    suggestedRemedy = "Increase the VM memory allocation in configuration to at least 2048 MB."
                )
            )
        }

        val plan = com.example.vm.memory.HostMemoryPlanner.calculateMemoryPlan(
            context = context,
            requestedRamMb = config.ramSizeMb,
            isKvmMode = isKvmMode,
            isWindowsGuest = isWindows
        )

        if (plan.isLowMemoryPressure) {
            return ValidationResult.Invalid(
                VMError(
                    category = VMErrorCategory.INSUFFICIENT_RAM,
                    summary = "HOST_MEMORY_PRESSURE: Cannot allocate ${config.ramSizeMb} MB guest RAM.",
                    technicalDetails = "Android Low Memory Killer (LMK) active. Available host memory: ${plan.availableHostMemMb} MB.\nReason: ${plan.reason}",
                    suggestedRemedy = "Close background apps or select a lower guest RAM allocation (e.g. ${plan.recommendedRamMb} MB)."
                )
            )
        }

        if (!plan.isSafe) {
            val summaryTitle = if (config.ramSizeMb >= 4096) {
                "${config.ramSizeMb / 1024} GB guest RAM is not currently available."
            } else {
                "Insufficient Host RAM for ${config.ramSizeMb} MB guest allocation."
            }
            return ValidationResult.Invalid(
                VMError(
                    category = VMErrorCategory.INSUFFICIENT_RAM,
                    summary = summaryTitle,
                    technicalDetails = "Requested: ${config.ramSizeMb} MB\nSafe maximum: ${plan.maximumSafeRamMb} MB\nAvailable host memory: ${plan.availableHostMemMb} MB\n\n${plan.reason}",
                    suggestedRemedy = "Select a safe RAM value equal to or less than ${plan.maximumSafeRamMb} MB (Recommended: ${plan.recommendedRamMb} MB)."
                )
            )
        }

        // 3. Guest-Specific Validation (Windows vs Linux)
        if (isWindows) {
            // Windows Boot Image & Media Validation
            val hasIso = config.isoPath.isNotBlank()
            val hasDisk = config.diskImagePath.isNotBlank()

            if (!hasIso && !hasDisk) {
                return ValidationResult.Invalid(
                    VMError.windowsImageNotConfigured("No user-supplied Windows ARM64 installation ISO or virtual disk image specified.")
                )
            }

            if (hasIso) {
                if (!diskBackend.isGuestImagePathAuthorized(config.isoPath) && !diskBackend.isPathAuthorized(config.isoPath)) {
                    return ValidationResult.Invalid(
                        VMError.windowsImageInvalid(config.isoPath, "Security Violation: Windows ISO path is outside authorized application storage.")
                    )
                }
                val isoFile = File(config.isoPath)
                if (!isoFile.exists()) {
                    return ValidationResult.Invalid(
                        VMError.windowsImageNotFound(config.isoPath)
                    )
                }
                if (!isoFile.canRead()) {
                    return ValidationResult.Invalid(
                        VMError.windowsImageUnreadable(config.isoPath)
                    )
                }
                if (isoFile.length() < 1024 * 1024) {
                    return ValidationResult.Invalid(
                        VMError.windowsImageInvalid(config.isoPath, "Windows ISO file is too small (${isoFile.length()} bytes) to contain valid Windows installation media.")
                    )
                }
            }

            if (hasDisk) {
                if (!diskBackend.isPathAuthorized(config.diskImagePath) && !diskBackend.isGuestImagePathAuthorized(config.diskImagePath)) {
                    return ValidationResult.Invalid(
                        VMError.windowsImageInvalid(config.diskImagePath, "Security Violation: Disk path is outside authorized application storage.")
                    )
                }
                val diskFile = File(config.diskImagePath)
                if (!diskFile.exists()) {
                    if (hasIso) {
                        val created = diskBackend.createDiskImage(config.diskImagePath, config.diskSizeGb, sparse = true)
                        if (!created) {
                            return ValidationResult.Invalid(
                                VMError.windowsImageInvalid(config.diskImagePath, "Failed to initialize virtual disk file for Windows installation.")
                            )
                        }
                    } else {
                        return ValidationResult.Invalid(
                            VMError.windowsImageNotFound(config.diskImagePath)
                        )
                    }
                } else {
                    if (diskFile.length() < 512) {
                        return ValidationResult.Invalid(
                            VMError.windowsImageInvalid(config.diskImagePath, "Disk file size (${diskFile.length()} bytes) is too small to contain valid GPT/MBR sectors.")
                        )
                    }
                    if (!diskFile.canRead() || !diskFile.canWrite()) {
                        return ValidationResult.Invalid(
                            VMError.windowsImageUnreadable(config.diskImagePath)
                        )
                    }
                }
            }

            // UEFI Firmware & ACPI validation
            val firmware = com.example.vm.firmware.UefiFirmwareManager.getFirmwareForArch(context, GuestArchitecture.ARM64)
            if (firmware.path.isBlank()) {
                return ValidationResult.Invalid(
                    VMError.firmwareUnavailable("ARM64", "Firmware path is unresolvable.")
                )
            }
        } else {
            // Linux Kernel Validation & Sandbox Enforcement
            val kernelPath = config.kernelImagePath
            val initramfsPath = config.initramfsPath
            val diskPath = config.diskImagePath

            if (kernelPath.isBlank()) {
                return ValidationResult.Invalid(
                    VMError.kernelMissing(
                        path = "(none)",
                        details = "No ARM64 Linux kernel image configured. Please provision the default Linux kernel or import an authentic ARM64 vmlinuz binary."
                    )
                )
            }

            if (!diskBackend.isGuestImagePathAuthorized(kernelPath) && !diskBackend.isPathAuthorized(kernelPath)) {
                return ValidationResult.Invalid(
                    VMError.kernelMissing(
                        path = kernelPath,
                        details = "Security Violation: Kernel path is outside authorized application storage sandbox."
                    )
                )
            }

            val kernelFile = File(kernelPath)
            if (!kernelFile.exists()) {
                return ValidationResult.Invalid(
                    VMError.kernelMissing(
                        path = kernelPath,
                        details = "Kernel file does not exist on storage. Path: $kernelPath"
                    )
                )
            }

            if (!kernelFile.canRead()) {
                return ValidationResult.Invalid(
                    VMError.kernelMissing(
                        path = kernelPath,
                        details = "Permission Denied: Kernel file is not readable by the application process."
                    )
                )
            }

            if (kernelFile.length() < 64) {
                return ValidationResult.Invalid(
                    VMError.kernelMissing(
                        path = kernelPath,
                        details = "Kernel file is too small (${kernelFile.length()} bytes) to contain a valid ARM64 header."
                    )
                )
            }

            val kernelInfo = GuestKernelManager.inspectKernel(kernelPath)
            if (!kernelInfo.isArm64Valid) {
                return ValidationResult.Invalid(
                    VMError.kernelMissing(
                        path = kernelPath,
                        details = "Invalid Kernel Binary: ${kernelInfo.formatDescription}"
                    )
                )
            }

            // 4. Initramfs Validation (if specified)
            if (initramfsPath.isNotBlank()) {
                if (!diskBackend.isGuestImagePathAuthorized(initramfsPath) && !diskBackend.isPathAuthorized(initramfsPath)) {
                    return ValidationResult.Invalid(
                        VMError.initramfsMissing(initramfsPath)
                    )
                }

                val initrdFile = File(initramfsPath)
                if (!initrdFile.exists()) {
                    return ValidationResult.Invalid(
                        VMError.initramfsMissing(initramfsPath)
                    )
                }

                if (!initrdFile.canRead()) {
                    return ValidationResult.Invalid(
                        VMError(
                            category = VMErrorCategory.INITRAMFS_MISSING,
                            summary = "Initramfs Not Readable",
                            technicalDetails = "Permission Denied: Initramfs archive is not readable by app process.",
                            suggestedRemedy = "Re-provision or re-import the initramfs into private storage."
                        )
                    )
                }

                val initrdInfo = GuestInitramfsManager.inspectInitramfs(initramfsPath)
                if (!initrdInfo.exists || initrdInfo.sizeBytes == 0L) {
                    return ValidationResult.Invalid(
                        VMError.initramfsMissing(initramfsPath)
                    )
                }

                if (!initrdInfo.hasUsableInit) {
                    return ValidationResult.Invalid(
                        VMError.initramfsMissingInit(initramfsPath)
                    )
                }
            }

            // 5. Virtual Disk Validation (if specified)
            if (diskPath.isNotBlank()) {
                if (!diskBackend.isPathAuthorized(diskPath) && !diskBackend.isGuestImagePathAuthorized(diskPath)) {
                    return ValidationResult.Invalid(
                        VMError.diskInvalid(
                            path = diskPath,
                            details = "Security Violation: Disk image path is outside authorized application storage."
                        )
                    )
                }

                val diskFile = File(diskPath)
                if (!diskFile.exists()) {
                    val created = diskBackend.createDiskImage(diskPath, config.diskSizeGb, sparse = true)
                    if (!created) {
                        return ValidationResult.Invalid(
                            VMError.diskInvalid(
                                path = diskPath,
                                details = "Virtual disk file does not exist and could not be created."
                            )
                        )
                    }
                } else {
                    if (diskFile.length() < 512) {
                        return ValidationResult.Invalid(
                            VMError.diskInvalid(
                                path = diskPath,
                                details = "Disk file size (${diskFile.length()} bytes) is too small to contain valid MBR/GPT sectors."
                            )
                        )
                    }
                    if (!diskFile.canRead() || !diskFile.canWrite()) {
                        return ValidationResult.Invalid(
                            VMError.diskInvalid(
                                path = diskPath,
                                details = "Virtual disk permissions error (read=${diskFile.canRead()}, write=${diskFile.canWrite()})."
                            )
                        )
                    }
                }
            }
        }

        // 6. KVM Virtualization & Fallback Determination
        val isKvmAvail = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeIsKvmSupported() else false
        val kvmReason = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeGetKvmReason() else "Native bridge inactive"
        val wantsKvm = config.useHardwareVirtualization && config.cpuBackendPreference != "ARM64_SOFTWARE_EMULATOR"

        val isAccelerated: Boolean
        val backendName: String
        val statusMsg: String

        if (wantsKvm && isKvmAvail && hostArch == HostArchitecture.ARM64) {
            isAccelerated = true
            backendName = "Hardware Virtualization (ARM64 KVM Hypervisor)"
            statusMsg = "Direct hardware virtualization active via /dev/kvm."
        } else {
            isAccelerated = false
            backendName = "ARM64 Software Emulation"
            statusMsg = if (wantsKvm && !isKvmAvail) {
                "KVM unavailable ($kvmReason). Fallback ARM64 software emulation active."
            } else {
                "ARM64 software emulation active (User configured)."
            }
        }

        return ValidationResult.Valid(
            isHardwareAccelerated = isAccelerated,
            cpuBackendName = backendName,
            statusMessage = statusMsg
        )
    }
}
