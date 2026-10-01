package com.example.vm.guest.linux

import android.content.Context
import android.util.Log
import com.example.vm.core.VMConfig
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelManager
import com.example.vm.guest.os.OSManifestRegistry
import com.example.vm.guest.os.OSStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Linux Image Provisioning and Management System.
 * Replaces fake kernel and empty sparse filesystem generation with a real image provisioning architecture.
 * Manages verified authentic ARM64 Linux kernels, CPIO initramfs archives, and root filesystems.
 */
object LinuxImageProvisioner {

    private const val TAG = "LinuxImageProvisioner"
    const val DEFAULT_KERNEL_FILENAME = "vmlinuz"
    const val DEFAULT_INITRAMFS_FILENAME = "initrd"
    const val DEFAULT_DISK_FILENAME = "rootfs.img"

    enum class AssetProvisionState {
        NOT_CONFIGURED,
        DOWNLOADING,
        VERIFYING,
        READY,
        INVALID,
        FAILED
    }

    data class AssetStatus(
        val state: AssetProvisionState,
        val path: String = "",
        val sizeBytes: Long = 0L,
        val format: String = "",
        val message: String = ""
    )

    data class LinuxProvisionStatus(
        val kernel: AssetStatus,
        val initramfs: AssetStatus,
        val rootfs: AssetStatus,
        val isBootable: Boolean
    )

    data class LinuxArtifactMetadata(
        val distribution: String = "Ubuntu",
        val version: String = "24.04 LTS",
        val architecture: String = "aarch64",
        val kernelVersion: String = "",
        val initramfsVersion: String = "",
        val sha256: String = "",
        val source: String = "User / Official Repository"
    )

    sealed class ProvisionResult {
        data class Success(
            val config: VMConfig,
            val kernelPath: String,
            val initramfsPath: String,
            val diskPath: String,
            val message: String
        ) : ProvisionResult()

        data class Failure(
            val reason: String,
            val status: LinuxProvisionStatus? = null
        ) : ProvisionResult()
    }

    fun inspectEnvironment(context: Context): LinuxProvisionStatus {
        val kernelFile = getKernelFile(context)
        val initrdFile = getInitramfsFile(context)
        val diskFile = getDefaultDiskFile(context)

        // 1. Kernel Status
        val kernelStatus = if (!kernelFile.exists()) {
            AssetStatus(
                state = AssetProvisionState.NOT_CONFIGURED,
                message = "No Linux kernel configured. Please import or download a verified ARM64 Linux kernel."
            )
        } else {
            val kInfo = GuestKernelManager.inspectKernel(kernelFile.absolutePath)
            if (kInfo.isArm64Valid) {
                AssetStatus(
                    state = AssetProvisionState.READY,
                    path = kernelFile.absolutePath,
                    sizeBytes = kernelFile.length(),
                    format = kInfo.formatDescription,
                    message = "Authentic ARM64 Linux kernel verified."
                )
            } else {
                AssetStatus(
                    state = AssetProvisionState.INVALID,
                    path = kernelFile.absolutePath,
                    sizeBytes = kernelFile.length(),
                    format = kInfo.formatDescription,
                    message = "Kernel invalid: ${kInfo.formatDescription}"
                )
            }
        }

        // 2. Initramfs Status
        val initrdStatus = if (!initrdFile.exists()) {
            AssetStatus(
                state = AssetProvisionState.NOT_CONFIGURED,
                message = "No initramfs archive configured."
            )
        } else {
            val iInfo = GuestInitramfsManager.inspectInitramfs(initrdFile.absolutePath)
            if (iInfo.hasUsableInit) {
                AssetStatus(
                    state = AssetProvisionState.READY,
                    path = initrdFile.absolutePath,
                    sizeBytes = initrdFile.length(),
                    format = iInfo.format,
                    message = "Valid CPIO initramfs with executable /init."
                )
            } else {
                AssetStatus(
                    state = AssetProvisionState.INVALID,
                    path = initrdFile.absolutePath,
                    sizeBytes = initrdFile.length(),
                    format = iInfo.format,
                    message = "Initramfs invalid: ${iInfo.statusMessage}"
                )
            }
        }

        // 3. Rootfs Status
        val rootfsStatus = if (!diskFile.exists()) {
            AssetStatus(
                state = AssetProvisionState.NOT_CONFIGURED,
                message = "No root filesystem disk image configured."
            )
        } else {
            val rInfo = LinuxRootfsManager.inspectRootfs(diskFile.absolutePath)
            if (rInfo.isVerified) {
                AssetStatus(
                    state = AssetProvisionState.READY,
                    path = diskFile.absolutePath,
                    sizeBytes = diskFile.length(),
                    format = rInfo.format,
                    message = rInfo.statusMessage
                )
            } else {
                AssetStatus(
                    state = AssetProvisionState.INVALID,
                    path = diskFile.absolutePath,
                    sizeBytes = diskFile.length(),
                    format = rInfo.format,
                    message = rInfo.statusMessage
                )
            }
        }

        val isBootable = kernelStatus.state == AssetProvisionState.READY &&
                         (initrdStatus.state == AssetProvisionState.READY || rootfsStatus.state == AssetProvisionState.READY)

        return LinuxProvisionStatus(
            kernel = kernelStatus,
            initramfs = initrdStatus,
            rootfs = rootfsStatus,
            isBootable = isBootable
        )
    }

    /**
     * Checks whether the default Linux ARM64 environment has real, verified assets provisioned.
     */
    fun isDefaultEnvironmentProvisioned(context: Context): Boolean {
        val status = inspectEnvironment(context)
        return status.isBootable
    }

    fun getKernelFile(context: Context): File {
        val manifest = OSManifestRegistry.getManifestById("ubuntu-24-04-cloud-arm64")
        if (manifest != null) {
            val installed = OSStorageManager.getInstalledFiles(context, manifest)
            if (installed?.kernelFile != null && installed.kernelFile.exists()) {
                return installed.kernelFile
            }
        }
        val dir = File(context.filesDir, "guest_os/ubuntu-24-04-cloud-arm64").apply { if (!exists()) mkdirs() }
        return File(dir, DEFAULT_KERNEL_FILENAME)
    }

    fun getInitramfsFile(context: Context): File {
        val manifest = OSManifestRegistry.getManifestById("ubuntu-24-04-cloud-arm64")
        if (manifest != null) {
            val installed = OSStorageManager.getInstalledFiles(context, manifest)
            if (installed?.initrdFile != null && installed.initrdFile.exists()) {
                return installed.initrdFile
            }
        }
        val dir = File(context.filesDir, "guest_os/ubuntu-24-04-cloud-arm64").apply { if (!exists()) mkdirs() }
        return File(dir, DEFAULT_INITRAMFS_FILENAME)
    }

    fun getDefaultDiskFile(context: Context, vmName: String = "ubuntu_arm64"): File {
        val manifest = OSManifestRegistry.getManifestById("ubuntu-24-04-cloud-arm64")
        if (manifest != null) {
            val installed = OSStorageManager.getInstalledFiles(context, manifest)
            if (installed?.diskFile != null && installed.diskFile.exists()) {
                return installed.diskFile
            }
        }
        val dir = File(context.filesDir, "guest_os/ubuntu-24-04-cloud-arm64").apply { if (!exists()) mkdirs() }
        return File(dir, DEFAULT_DISK_FILENAME)
    }

    /**
     * Provisions the default Linux environment using verified real assets.
     * Never generates a fake kernel, fake initramfs, or empty sparse rootfs.
     */
    suspend fun provisionDefaultLinuxEnvironment(
        context: Context,
        vmName: String = "Ubuntu 24.04 ARM64",
        forceRecreate: Boolean = false
    ): ProvisionResult = withContext(Dispatchers.IO) {
        try {
            val status = inspectEnvironment(context)

            if (status.kernel.state != AssetProvisionState.READY) {
                return@withContext ProvisionResult.Failure(
                    "No Linux kernel configured. Please import or download a verified ARM64 Linux kernel.",
                    status
                )
            }

            if (status.initramfs.state != AssetProvisionState.READY && status.rootfs.state != AssetProvisionState.READY) {
                return@withContext ProvisionResult.Failure(
                    "No bootable initramfs or root filesystem configured. Please provide an authentic initramfs or rootfs disk image.",
                    status
                )
            }

            val kernelFile = File(status.kernel.path)
            val initrdFile = if (status.initramfs.state == AssetProvisionState.READY) File(status.initramfs.path) else null
            val diskFile = if (status.rootfs.state == AssetProvisionState.READY) File(status.rootfs.path) else null

            val cmdline = if (initrdFile != null) {
                "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init"
            } else {
                "console=ttyAMA0,115200 root=/dev/vda1 rw earlycon=pl011,0x09000000 init=/sbin/init"
            }

            val config = VMConfig(
                name = vmName,
                guestOsType = "Ubuntu",
                osVersion = "24.04 LTS",
                installationMode = "MODE_A_PREINSTALLED",
                cpuBackendPreference = "AUTO",
                bootOrder = if (initrdFile != null) "KERNEL" else "VIRTUAL_DISK",
                guestArchCode = GuestArchitecture.ARM64.code,
                cpuCores = 2,
                ramSizeMb = 2048,
                diskSizeGb = if (diskFile != null) maxOf(1, (diskFile.length() / (1024 * 1024 * 1024)).toInt()) else 16,
                diskImagePath = diskFile?.absolutePath ?: "",
                useHardwareVirtualization = true,
                networkEnabled = true,
                networkMode = "NAT",
                serialConsoleEnabled = true,
                kernelImagePath = kernelFile.absolutePath,
                initramfsPath = initrdFile?.absolutePath ?: "",
                kernelCmdline = cmdline,
                consoleDevice = "ttyAMA0 (PL011 UART)",
                hostname = "ubuntu-arm64",
                username = "ubuntu",
                password = "ubuntu"
            )

            Log.i(TAG, "Default Linux environment confirmed with verified assets: kernel=${kernelFile.absolutePath}")

            ProvisionResult.Success(
                config = config,
                kernelPath = kernelFile.absolutePath,
                initramfsPath = initrdFile?.absolutePath ?: "",
                diskPath = diskFile?.absolutePath ?: "",
                message = "Default Linux ARM64 environment verified with authentic assets."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning check failed", e)
            ProvisionResult.Failure("Linux environment provisioning failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    data class BootFileMetadata(
        val absolutePath: String,
        val fileName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val sha256Hex: String
    )

    fun importBootFile(
        context: Context,
        sourceFile: File,
        targetSubdir: String,
        destFilename: String
    ): BootFileMetadata {
        val targetDir = File(context.filesDir, targetSubdir).apply { if (!exists()) mkdirs() }
        val tempFile = File(targetDir, "$destFilename.tmp")
        val destFile = File(targetDir, destFilename)

        sourceFile.inputStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        tempFile.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

        if (destFile.exists()) {
            destFile.delete()
        }
        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }

        return BootFileMetadata(
            absolutePath = destFile.absolutePath,
            fileName = destFile.name,
            sizeBytes = destFile.length(),
            lastModified = destFile.lastModified(),
            sha256Hex = sha256
        )
    }
}
