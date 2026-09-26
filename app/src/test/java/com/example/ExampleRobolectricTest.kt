package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vm.storage.AndroidStorageDiskBackend
import com.example.vm.memory.MemoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("MobileVM", appName)
  }

  @Test
  fun `disk backend strictly authorizes scoped sandbox paths and rejects arbitrary host paths`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val diskBackend = AndroidStorageDiskBackend(context)
    val authorizedDir = diskBackend.getAuthorizedDisksDirectory()

    // Valid path inside authorized sandbox
    val validDisk = File(authorizedDir, "ubuntu_system.img").absolutePath
    assertTrue(diskBackend.isPathAuthorized(validDisk))

    // Unauthorized arbitrary host paths
    assertFalse(diskBackend.isPathAuthorized("/system/bin/sh"))
    assertFalse(diskBackend.isPathAuthorized("/data/system/packages.xml"))
    assertFalse(diskBackend.isPathAuthorized("/etc/passwd"))
    assertFalse(diskBackend.isPathAuthorized(File(authorizedDir, "../../../system/etc").absolutePath))
  }

  @Test
  fun `guest image path validation blocks arbitrary host files`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val diskBackend = AndroidStorageDiskBackend(context)

    // Blank path is valid (uses built-in guest vectors)
    assertTrue(diskBackend.isGuestImagePathAuthorized(""))

    // Arbitrary sensitive host files must be rejected
    assertFalse(diskBackend.isGuestImagePathAuthorized("/system/framework/framework.jar"))
    assertFalse(diskBackend.isGuestImagePathAuthorized("/data/data/com.android.settings/settings.db"))
  }

  @Test
  fun `memory manager provides safety checks without crashing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val memMgr = MemoryManager(context)
    val rec = memMgr.getMemorySafetyRecommendation(1024)
    assertTrue(rec is MemoryManager.SafetyResult.Safe || rec is MemoryManager.SafetyResult.Warning || rec is MemoryManager.SafetyResult.Danger)
  }

  @Test
  fun `disk write and delete reject unauthorized host system files`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val diskBackend = AndroidStorageDiskBackend(context)

    // Attempting to write sectors to host files must fail and return false
    val writeDenied = diskBackend.writeSectors("/system/build.prop", 0, ByteArray(512))
    assertFalse(writeDenied)

    // Attempting to delete arbitrary host files must fail and return false
    val deleteDenied = diskBackend.deleteDiskFile("/system/etc/hosts")
    assertFalse(deleteDenied)
  }

  @Test
  fun `backend selector prefers hardware virtualization when ARM64 host capabilities exist`() {
    val resolution = com.example.vm.cpu.CPUBackendSelector.resolve(
        guestArch = com.example.vm.cpu.GuestArchitecture.ARM64,
        requestHardwareVirt = true,
        hostArch = com.example.vm.cpu.HostArchitecture.ARM64,
        isKvmSupported = true
    )

    assertEquals(com.example.vm.cpu.CPUBackendType.ARM64_HARDWARE_VIRTUALIZATION, resolution.backendType)
    assertTrue(resolution.isHardwareAccelerated)
    assertFalse(resolution.isFallbackEmulation)
  }

  @Test
  fun `backend selector falls back to supported software emulation when capabilities unavailable`() {
    val resolution = com.example.vm.cpu.CPUBackendSelector.resolve(
        guestArch = com.example.vm.cpu.GuestArchitecture.ARM64,
        requestHardwareVirt = true,
        hostArch = com.example.vm.cpu.HostArchitecture.ARM64,
        isKvmSupported = false,
        kvmReason = "Permission denied on /dev/kvm"
    )

    // Must resolve to supported emulation backend, NOT claim acceleration
    assertEquals(com.example.vm.cpu.CPUBackendType.ARM64_EMULATION, resolution.backendType)
    assertFalse(resolution.isHardwareAccelerated)
    assertTrue(resolution.isFallbackEmulation)
    assertTrue(resolution.statusMessage.contains("Hardware acceleration unavailable"))
  }

  @Test
  fun `backend diagnostics correctly aggregates system host and vm specifications`() {
    val application = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.vm.ui.VMViewModel(application)
    val diag = viewModel.computeBackendDiagnostics()

    // Host architecture and Android OS must be real
    assertNotNull(diag.hostArch)
    assertTrue(diag.osVersion.contains("Android"))
    assertTrue(diag.hostCpuCores > 0)
    assertTrue(diag.totalRamMb > 0)
    assertTrue(diag.availableRamMb > 0)

    // Peripherals and console
    assertNotNull(diag.usbDeviceCount)
    assertNotNull(diag.consoleConnected)
    assertNotNull(diag.diskImagePath)
    assertNotNull(diag.kernelImagePath)
    assertNotNull(diag.initramfsPath)
    assertNotNull(diag.selectedCpuBackend)
    assertNotNull(diag.guestArchitecture)
    assertEquals(com.example.vm.core.VMState.STOPPED, diag.activeVmState)
  }
}

