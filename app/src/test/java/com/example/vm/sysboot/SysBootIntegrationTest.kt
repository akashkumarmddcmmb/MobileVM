package com.example.vm.sysboot

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.vm.sysboot.data.BootEntryEntity
import com.example.vm.sysboot.data.CSourceModuleEntity
import com.example.vm.sysboot.viewmodel.SysBootViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SysBootIntegrationTest {

    private lateinit var application: Application
    private lateinit var viewModel: SysBootViewModel

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = SysBootViewModel(application)
    }

    @Test
    fun testSysBootInitializationData() = runBlocking {
        delay(1000)
        val entries = viewModel.bootEntries.first { it.isNotEmpty() }
        assertTrue("Boot entries should be initialized", entries.isNotEmpty())

        val winEntry = entries.find { it.osType == "WINDOWS" }
        assertNotNull("Windows boot entry should exist", winEntry)
        assertEquals("EFI/Microsoft/Boot/bootmgfw.efi", winEntry?.loaderPath)

        val linuxEntry = entries.find { it.osType == "LINUX" }
        assertNotNull("Linux boot entry should exist", linuxEntry)
        assertEquals("EFI/Linux/shimx64.efi", linuxEntry?.loaderPath)
    }

    @Test
    fun testSysBootSourceModules() = runBlocking {
        delay(1000)
        val modules = viewModel.cModules.first { it.isNotEmpty() }
        assertTrue("C source modules should be populated", modules.isNotEmpty())

        val mainC = modules.find { it.filename == "main.c" }
        assertNotNull("main.c module should exist", mainC)
        assertTrue(mainC!!.codeContent.contains("efi_main"))
    }

    @Test
    fun testSysBootSimulations() {
        viewModel.simulateFailureScenario("WINDOWS_MISSING")
        val recState = viewModel.recoveryState.value
        assertNotNull(recState)
    }
}
