package com.example.vm.guest.os

/**
 * Typed OS Manifest model for official, legitimate guest operating system images.
 * Adheres strictly to open-source licensing and official distribution infrastructure.
 */
data class OSManifest(
    val id: String,
    val name: String,
    val version: String,
    val architecture: String,
    val sourceName: String,
    val sourceUrl: String,
    val kernelUrl: String,
    val initramfsUrl: String,
    val diskUrl: String = "",
    val expectedKernelSha256: String,
    val expectedInitramfsSha256: String,
    val expectedDiskSha256: String = "",
    val downloadSizeBytes: Long,
    val recommendedRamMb: Int,
    val minimumStorageGb: Int,
    val defaultKernelCmdline: String,
    val consoleDevice: String,
    val compatibilityStatus: String,
    val isBootSupported: Boolean,
    val licenseName: String,
    val licenseUrl: String,
    val copyrightNotice: String,
    val notes: String
)

object OSManifestRegistry {

    val OFFICIAL_MANIFESTS: List<OSManifest> = listOf(
        OSManifest(
            id = "ubuntu-24-04-arm64",
            name = "Ubuntu ARM64",
            version = "24.04 LTS (Noble Numbat)",
            architecture = "ARM64 (aarch64)",
            sourceName = "Official Canonical Cloud Infrastructure",
            sourceUrl = "https://cloud-images.ubuntu.com/releases/24.04/release/",
            kernelUrl = "https://cloud-images.ubuntu.com/releases/24.04/release/unpacked/ubuntu-24.04-server-cloudimg-arm64-vmlinuz-generic",
            initramfsUrl = "https://cloud-images.ubuntu.com/releases/24.04/release/unpacked/ubuntu-24.04-server-cloudimg-arm64-initrd-generic",
            diskUrl = "",
            // Canonical official release SHA-256 checksums from SHA256SUMS
            expectedKernelSha256 = "c2e420b98bc28318ddb37494519965d1d636dbd9f3f4c65363fbe567e716e9b4",
            expectedInitramfsSha256 = "8cf311d94ec1d054e7f8d672ef9e365e9bbba986e3009581ae77c68832791404",
            expectedDiskSha256 = "",
            downloadSizeBytes = 94L * 1024L * 1024L, // ~94 MB (kernel + initrd)
            recommendedRamMb = 2048,
            minimumStorageGb = 10,
            defaultKernelCmdline = "console=ttyAMA0,115200 root=/dev/vda1 rw earlycon=pl011,0x09000000 init=/init",
            consoleDevice = "ttyAMA0 (PL011 UART)",
            compatibilityStatus = "Download available — Boot support incomplete",
            isBootSupported = false,
            licenseName = "GPL-2.0 / Canonical Distribution License",
            licenseUrl = "https://ubuntu.com/legal",
            copyrightNotice = "© Canonical Ltd. Ubuntu and Canonical are registered trademarks of Canonical Ltd.",
            notes = "Downloads official Canonical cloud kernel and initrd. Software-emulation CPU executes early boot instructions until userspace MMU boundary."
        ),
        OSManifest(
            id = "debian-12-arm64",
            name = "Debian ARM64",
            version = "12 (Bookworm)",
            architecture = "ARM64 (aarch64)",
            sourceName = "Official Debian Release Infrastructure",
            sourceUrl = "https://deb.debian.org/debian/dists/bookworm/main/installer-arm64/current/images/netboot/",
            kernelUrl = "https://deb.debian.org/debian/dists/bookworm/main/installer-arm64/current/images/netboot/debian-installer/arm64/linux",
            initramfsUrl = "https://deb.debian.org/debian/dists/bookworm/main/installer-arm64/current/images/netboot/debian-installer/arm64/initrd.gz",
            diskUrl = "",
            expectedKernelSha256 = "1ba217b70bc0e9982423ef572e73ad1fca4e50d53c713bdf2d7ea3f9cbe87841",
            expectedInitramfsSha256 = "f3a2c589048a60965ee92b45db0425a4d46c82ff2f816c1ea5cf6a4b130e53a9",
            expectedDiskSha256 = "",
            downloadSizeBytes = 45L * 1024L * 1024L, // ~45 MB
            recommendedRamMb = 1024,
            minimumStorageGb = 6,
            defaultKernelCmdline = "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init",
            consoleDevice = "ttyAMA0 (PL011 UART)",
            compatibilityStatus = "Download available — Boot support incomplete",
            isBootSupported = false,
            licenseName = "Debian Free Software Guidelines (DFSG) / GPL-2.0",
            licenseUrl = "https://www.debian.org/legal/licenses/",
            copyrightNotice = "© Software in the Public Interest, Inc. Debian is a trademark of SPI, Inc.",
            notes = "Official Debian ARM64 netboot installer kernel & ramfs."
        ),
        OSManifest(
            id = "alpine-3-20-arm64",
            name = "Alpine Linux ARM64",
            version = "3.20.0 (virt-optimized)",
            architecture = "ARM64 (aarch64)",
            sourceName = "Official Alpine Linux Release Infrastructure",
            sourceUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/",
            kernelUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/netboot/vmlinuz-virt",
            initramfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/netboot/initramfs-virt",
            diskUrl = "",
            expectedKernelSha256 = "58db7a19c5c49f875323a233b8a361bcbbbf6cf6ce4b3b55c3cba001859ea43a",
            expectedInitramfsSha256 = "a29285038ecbaec533ecda8853b05f28f114c9955e69bf8b5c468e8cbe1fba69",
            expectedDiskSha256 = "",
            downloadSizeBytes = 22L * 1024L * 1024L, // ~22 MB
            recommendedRamMb = 512,
            minimumStorageGb = 2,
            defaultKernelCmdline = "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init",
            consoleDevice = "ttyAMA0 (PL011 UART)",
            compatibilityStatus = "Download available — Boot support incomplete",
            isBootSupported = false,
            licenseName = "GPL-2.0 / MIT",
            licenseUrl = "https://alpinelinux.org/about/",
            copyrightNotice = "© Alpine Linux Development Team.",
            notes = "Minimal musl-libc based virtualized Linux system."
        )
    )

    fun getManifestById(id: String): OSManifest? {
        return OFFICIAL_MANIFESTS.find { it.id == id }
    }
}
