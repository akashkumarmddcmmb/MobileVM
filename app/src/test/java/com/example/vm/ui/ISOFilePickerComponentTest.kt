package com.example.vm.ui

import android.net.Uri
import com.example.vm.ui.components.IsoInspectionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ISOFilePickerComponentTest {

    @Test
    fun testIsoInspectionResultStructure() {
        val uri = Uri.parse("content://media/external/file/1024")
        val result = IsoInspectionResult(
            uri = uri,
            fileName = "Win11_24H2_English_Arm64.iso",
            sizeBytes = 6442450944L, // 6 GB
            isIso9660 = true,
            volumeLabel = "WIN11_ARM64_INSTALLER",
            detectedOs = "Windows 11 ARM64",
            isArm64Compatible = true,
            details = "UEFI Bootloader: bootmgfw.efi detected"
        )

        assertEquals("Win11_24H2_English_Arm64.iso", result.fileName)
        assertTrue(result.sizeBytes > 0)
        assertTrue(result.isIso9660)
        assertTrue(result.isArm64Compatible)
        assertEquals("Windows 11 ARM64", result.detectedOs)
    }

    @Test
    fun testLinuxIsoInspectionResult() {
        val uri = Uri.parse("content://media/external/file/2048")
        val result = IsoInspectionResult(
            uri = uri,
            fileName = "ubuntu-24.04-live-server-arm64.iso",
            sizeBytes = 2147483648L, // 2 GB
            isIso9660 = true,
            volumeLabel = "Ubuntu-Server 24.04 ARM64",
            detectedOs = "Ubuntu Linux ARM64",
            isArm64Compatible = true,
            details = "GRUB UEFI Bootloader (BOOTAA64.EFI)"
        )

        assertEquals("ubuntu-24.04-live-server-arm64.iso", result.fileName)
        assertTrue(result.isArm64Compatible)
        assertTrue(result.detectedOs.contains("Ubuntu"))
    }
}
