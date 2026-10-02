package com.example.vm.sysboot.repository

import com.example.vm.sysboot.data.BootEntryDao
import com.example.vm.sysboot.data.BootEntryEntity
import com.example.vm.sysboot.data.BootFailureSimulationEntity
import com.example.vm.sysboot.data.CSourceModuleEntity
import com.example.vm.sysboot.data.DiagnosticDao
import com.example.vm.sysboot.data.DiagnosticResultEntity
import com.example.vm.sysboot.data.FailureSimulationDao
import com.example.vm.sysboot.data.SecurityAuditEntity
import com.example.vm.sysboot.data.SecurityDao
import com.example.vm.sysboot.data.SourceModuleDao
import com.example.vm.sysboot.model.AcpiTable
import com.example.vm.sysboot.model.ArchitectureDoc
import com.example.vm.sysboot.model.EspFileItem
import com.example.vm.sysboot.model.HardwareMetric
import com.example.vm.sysboot.model.PartitionInfo
import com.example.vm.sysboot.model.PcrRegister
import com.example.vm.sysboot.model.ProjectFileNode
import com.example.vm.sysboot.model.SecureBootKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

class SysBootRepository(
    private val bootEntryDao: BootEntryDao,
    private val diagnosticDao: DiagnosticDao,
    private val securityDao: SecurityDao,
    private val sourceModuleDao: SourceModuleDao,
    private val failureSimulationDao: FailureSimulationDao
) {
    val bootEntries: Flow<List<BootEntryEntity>> = bootEntryDao.getAllBootEntries()
    val diagnosticResults: Flow<List<DiagnosticResultEntity>> = diagnosticDao.getAllDiagnostics()
    val securityAudits: Flow<List<SecurityAuditEntity>> = securityDao.getAllSecurityAudits()
    val cModules: Flow<List<CSourceModuleEntity>> = sourceModuleDao.getAllModules()
    val failureSimulations: Flow<List<BootFailureSimulationEntity>> = failureSimulationDao.getAllSimulations()

    suspend fun initializeDefaultDataIfEmpty() {
        val currentEntries = bootEntries.first()
        if (currentEntries.isEmpty()) {
            val defaults = listOf(
                BootEntryEntity(
                    name = "Windows 11 Pro (ARM64 / x64)",
                    loaderPath = "EFI/Microsoft/Boot/bootmgfw.efi",
                    kernelParams = "BCD00000001 /fastboot=ON /nointegritychecks=OFF",
                    isDefault = true,
                    timeoutSeconds = 5,
                    osType = "WINDOWS",
                    displayOrder = 1,
                    isEnabled = true,
                    secureBootRequired = true
                ),
                BootEntryEntity(
                    name = "Ubuntu 24.04 LTS (ARM64 Kernel 6.8)",
                    loaderPath = "EFI/Linux/shimx64.efi",
                    kernelParams = "root=UUID=8f3c21a9-33b2-4d51-9e2a-1123456789ab ro quiet splash console=ttyAMA0,115200",
                    isDefault = false,
                    timeoutSeconds = 5,
                    osType = "LINUX",
                    displayOrder = 2,
                    isEnabled = true,
                    secureBootRequired = true
                ),
                BootEntryEntity(
                    name = "MobileVM Custom Recovery EFI",
                    loaderPath = "EFI/Recovery/Recovery.efi",
                    kernelParams = "mode=repair loglevel=7 target_partition=NVME_P1",
                    isDefault = false,
                    timeoutSeconds = 5,
                    osType = "RECOVERY",
                    displayOrder = 3,
                    isEnabled = true,
                    secureBootRequired = false
                ),
                BootEntryEntity(
                    name = "Hardware & Memory Diagnostics OS",
                    loaderPath = "EFI/MyBoot/Diagnostics.efi",
                    kernelParams = "test=full cpu_mem_nvme=1 verbose=1",
                    isDefault = false,
                    timeoutSeconds = 5,
                    osType = "DIAGNOSTICS",
                    displayOrder = 4,
                    isEnabled = true,
                    secureBootRequired = false
                )
            )
            bootEntryDao.insertBootEntries(defaults)
        }

        val currentDiagnostics = diagnosticResults.first()
        if (currentDiagnostics.isEmpty()) {
            val now = System.currentTimeMillis()
            val initialDiag = listOf(
                DiagnosticResultEntity(
                    testName = "CPU Core & ARM64 Vector Stress",
                    category = "CPU",
                    timestamp = now - 3600000,
                    status = "PASSED",
                    scoreOrMetric = "8 Cores / 16 Threads @ 4.8 GHz (62 C)",
                    detailsLog = "PASS: All floating-point & vector units initialized without ECC fault."
                ),
                DiagnosticResultEntity(
                    testName = "LPDDR5 Memory Pattern Test",
                    category = "RAM",
                    timestamp = now - 3200000,
                    status = "PASSED",
                    scoreOrMetric = "16 GB LPDDR5-5600 Dual-Channel",
                    detailsLog = "PASS: 4 passes complete, 0 single-bit or multi-bit errors detected."
                ),
                DiagnosticResultEntity(
                    testName = "NVMe PCIe Gen4 x4 Controller Check",
                    category = "NVME",
                    timestamp = now - 2800000,
                    status = "PASSED",
                    scoreOrMetric = "100% Health, 7,150 MB/s Read",
                    detailsLog = "PASS: SMART status healthy, 0 reallocated sectors, temp 38 C."
                ),
                DiagnosticResultEntity(
                    testName = "TPM 2.0 Cryptographic Self-Test",
                    category = "TPM",
                    timestamp = now - 2400000,
                    status = "PASSED",
                    scoreOrMetric = "SHA256 & ECC P-256 Validated",
                    detailsLog = "PASS: TPM2_SelfTest executed successfully. Endorsement key verified."
                )
            )
            initialDiag.forEach { diagnosticDao.insertDiagnostic(it) }
        }

        val currentAudits = securityAudits.first()
        if (currentAudits.isEmpty()) {
            val now = System.currentTimeMillis()
            val initialAudits = listOf(
                SecurityAuditEntity(
                    timestamp = now - 5000000,
                    eventType = "SECURE_BOOT_KEY",
                    severity = "SUCCESS",
                    summary = "Secure Boot Active with Custom PK & KEK",
                    details = "Platform Key (PK) validated. KEK and Authorized Database (db) active."
                ),
                SecurityAuditEntity(
                    timestamp = now - 4500000,
                    eventType = "MEASURED_BOOT",
                    severity = "INFO",
                    summary = "PCR 0-7 Extended Successfully",
                    details = "Firmware code, ACPI tables, and bootloader hashes extended into TPM 2.0 PCR registers."
                )
            )
            initialAudits.forEach { securityDao.insertAudit(it) }
        }

        val currentModules = cModules.first()
        if (currentModules.isEmpty()) {
            val modulesList = listOf(
                CSourceModuleEntity(
                    filename = "main.c",
                    category = "CORE",
                    description = "Consolidated EFI Bootloader Entry Point & Subsystem Initializer",
                    headerContent = "#include <efi.h>\n#include <efilib.h>\n#include \"boot_manager.h\"",
                    codeContent = """
                        #include <efi.h>
                        #include <efilib.h>
                        #include "boot_manager.h"
                        #include "security.h"
                        #include "logging.h"

                        EFI_STATUS EFIAPI efi_main(EFI_HANDLE ImageHandle, EFI_SYSTEM_TABLE *SystemTable) {
                            EFI_STATUS Status;
                            InitializeLib(ImageHandle, SystemTable);
                            Print(L"Initializing MobileVM BootManager v1.0...\n");

                            // Step 1: Initialize Logging & Security
                            Log_Init();
                            Log_Write(L"MAIN: System Table Handshake OK.");
                            Status = Security_VerifyPlatformState(ImageHandle);
                            if (EFI_ERROR(Status)) {
                                Log_Write(L"MAIN_ERROR: Secure Boot Verification Failed.");
                                return Boot_TriggerFallback(FALLBACK_SECURE_BOOT_FAIL);
                            }

                            // Step 2: Initialize Core Boot Manager
                            Status = BootManager_Initialize(ImageHandle);
                            if (EFI_ERROR(Status)) {
                                Log_Write(L"MAIN_ERROR: Boot Manager Initialization Error.");
                                return Boot_TriggerFallback(FALLBACK_ESP_DAMAGED);
                            }

                            // Step 3: Run Interactive Boot Menu
                            return BootMenu_Run();
                        }
                    """.trimIndent(),
                    dependencies = "boot_manager.h, security.h, logging.h"
                ),
                CSourceModuleEntity(
                    filename = "boot_manager.c",
                    category = "CORE",
                    description = "Boot Dispatcher & Storage Volume Enumerator",
                    headerContent = "#include \"boot_manager.h\"",
                    codeContent = """
                        #include "boot_manager.h"
                        #include "esp.h"
                        #include "boot_entries.h"

                        EFI_STATUS BootManager_Initialize(EFI_HANDLE ImageHandle) {
                            EFI_STATUS Status;
                            Status = ESP_MountSystemPartition(ImageHandle);
                            if (EFI_ERROR(Status)) return Status;
                            Status = BootEntries_LoadConfiguration();
                            if (EFI_ERROR(Status)) return Status;
                            return EFI_SUCCESS;
                        }

                        EFI_STATUS BootManager_LaunchEntry(BOOT_ENTRY *Entry) {
                            switch (Entry->Type) {
                                case OS_TYPE_WINDOWS:
                                    return Windows_Launch(Entry);
                                case OS_TYPE_LINUX:
                                    return Linux_Launch(Entry);
                                case OS_TYPE_RECOVERY:
                                    return Recovery_Launch(Entry);
                                case OS_TYPE_DIAGNOSTICS:
                                    return Diagnostics_Launch(Entry);
                                default:
                                    return EFI_UNSUPPORTED;
                            }
                        }
                    """.trimIndent(),
                    dependencies = "esp.h, boot_entries.h, windows.h, linux.h, recovery.h"
                ),
                CSourceModuleEntity(
                    filename = "windows.c",
                    category = "OS_LOADER",
                    description = "Microsoft Windows Boot Manager (bootmgfw.efi) Handoff Handler",
                    headerContent = "#include \"windows.h\"",
                    codeContent = """
                        #include "windows.h"
                        #include "efi_loader.h"
                        #include "security.h"

                        EFI_STATUS Windows_Launch(BOOT_ENTRY *Entry) {
                            EFI_STATUS Status;
                            const CHAR16 *WindowsPath = L"\\EFI\\Microsoft\\Boot\\bootmgfw.efi";
                            Print(L"Locating Windows EFI Loader at %s...\n", WindowsPath);
                            Status = EfiLoader_ValidateBinary(WindowsPath);
                            if (EFI_ERROR(Status)) {
                                Print(L"ERROR: bootmgfw.efi missing or corrupt!\n");
                                return Boot_TriggerFallback(FALLBACK_WINDOWS_MISSING);
                            }
                            Status = Security_VerifyBinarySignature(WindowsPath);
                            if (EFI_ERROR(Status)) {
                                Print(L"ERROR: Signature validation failed for bootmgfw.efi!\n");
                                return Boot_TriggerFallback(FALLBACK_SECURE_BOOT_FAIL);
                            }
                            Print(L"Handoff to Windows Boot Manager...\n");
                            return EfiLoader_ExecuteImage(WindowsPath, Entry->Params);
                        }
                    """.trimIndent(),
                    dependencies = "efi_loader.h, security.h, boot_fallback.h"
                ),
                CSourceModuleEntity(
                    filename = "linux.c",
                    category = "OS_LOADER",
                    description = "Linux Shim & GRUB EFI Loader Dispatcher with Initramfs Validation",
                    headerContent = "#include \"linux.h\"",
                    codeContent = """
                        #include "linux.h"
                        #include "efi_loader.h"
                        #include "security.h"

                        EFI_STATUS Linux_Launch(BOOT_ENTRY *Entry) {
                            EFI_STATUS Status;
                            const CHAR16 *ShimPath = L"\\EFI\\Linux\\shimx64.efi";
                            Print(L"Locating Linux Shim/GRUB at %s...\n", ShimPath);
                            Status = EfiLoader_ValidateBinary(ShimPath);
                            if (EFI_ERROR(Status)) {
                                Print(L"ERROR: Linux shimx64.efi missing!\n");
                                return Boot_TriggerFallback(FALLBACK_LINUX_MISSING);
                            }
                            Print(L"Executing Linux Kernel Handoff...\n");
                            return EfiLoader_ExecuteImage(ShimPath, Entry->Params);
                        }
                    """.trimIndent(),
                    dependencies = "efi_loader.h, security.h, boot_fallback.h"
                ),
                CSourceModuleEntity(
                    filename = "recovery.c",
                    category = "UTILITY",
                    description = "Automated EFI & BCD Repair Engine with Partition Scanner",
                    headerContent = "#include \"recovery.h\"",
                    codeContent = """
                        #include "recovery.h"
                        #include "esp.h"
                        #include "logging.h"

                        EFI_STATUS Recovery_ScanAndRepair(RECOVERY_REPORT *Report) {
                            Log_Write(L"RECOVERY: Starting automated EFI & BCD diagnostic repair...");
                            Report->EfiPartitionStatus = ESP_ValidateFileSystem();
                            Report->WindowsBcdStatus = Recovery_VerifyBcdHive();
                            Report->LinuxGrubStatus = Recovery_VerifyGrubConfig();
                            if (!Report->WindowsBcdStatus) {
                                Recovery_RebuildBcd();
                            }
                            return EFI_SUCCESS;
                        }
                    """.trimIndent(),
                    dependencies = "esp.h, logging.h"
                ),
                CSourceModuleEntity(
                    filename = "boot_fallback.c",
                    category = "CORE",
                    description = "Failure Handling Matrix & Diagnostic Recovery Diverter",
                    headerContent = "#include \"boot_fallback.h\"",
                    codeContent = """
                        #include "boot_fallback.h"
                        #include "logging.h"

                        EFI_STATUS Boot_TriggerFallback(FALLBACK_REASON Reason) {
                            switch (Reason) {
                                case FALLBACK_WINDOWS_MISSING:
                                    Log_Write(L"FALLBACK: Windows loader absent. Diverting to Recovery.");
                                    break;
                                case FALLBACK_LINUX_MISSING:
                                    Log_Write(L"FALLBACK: Linux loader absent. Diverting to Recovery.");
                                    break;
                                case FALLBACK_ESP_DAMAGED:
                                    Log_Write(L"FALLBACK: ESP FAT32 damaged. Launching ESP Repair Wizard.");
                                    break;
                                case FALLBACK_SECURE_BOOT_FAIL:
                                    Log_Write(L"FALLBACK: Secure Boot key mismatch. Diverting to Security Console.");
                                    break;
                                case FALLBACK_STORAGE_UNAVAILABLE:
                                    Log_Write(L"FALLBACK: NVMe controller non-responsive. Diverting to Diagnostics.");
                                    break;
                            }
                            return Recovery_LaunchInteractiveConsole(Reason);
                        }
                    """.trimIndent(),
                    dependencies = "logging.h, recovery.h"
                ),
                CSourceModuleEntity(
                    filename = "CMakeLists.txt",
                    category = "BUILD",
                    description = "GNU-EFI / EDK2 Cross-Compilation Build Configuration",
                    headerContent = "# CMake Configuration for MobileVMBootManager.efi",
                    codeContent = """
                        cmake_minimum_required(VERSION 3.20)
                        project(MobileVMBootManager C)

                        set(CMAKE_C_STANDARD 11)
                        find_package(EDK2 REQUIRED)

                        add_executable(MobileVMBootManager.efi
                            src/main.c
                            src/boot_manager.c
                            src/boot_menu.c
                            src/boot_entries.c
                            src/efi_loader.c
                            src/efi_variables.c
                            src/filesystem.c
                            src/gpt.c
                            src/esp.c
                            src/windows.c
                            src/linux.c
                            src/recovery.c
                            src/diagnostics.c
                            src/configuration.c
                            src/security.c
                            src/logging.c
                            src/boot_fallback.c
                        )

                        target_include_directories(MobileVMBootManager.efi PRIVATE include)
                        target_link_libraries(MobileVMBootManager.efi PRIVATE EDK2::EfiDriverLib)
                    """.trimIndent(),
                    dependencies = "EDK2 Toolchain"
                )
            )
            sourceModuleDao.insertModules(modulesList)
        }
    }

    suspend fun addBootEntry(entry: BootEntryEntity) = bootEntryDao.insertBootEntry(entry)
    suspend fun updateBootEntry(entry: BootEntryEntity) = bootEntryDao.updateBootEntry(entry)
    suspend fun deleteBootEntry(entry: BootEntryEntity) = bootEntryDao.deleteBootEntry(entry)
    suspend fun setDefaultBootEntry(id: Int) {
        bootEntryDao.clearDefaultFlags()
        bootEntryDao.setDefaultBootEntry(id)
    }

    suspend fun recordDiagnosticResult(result: DiagnosticResultEntity) = diagnosticDao.insertDiagnostic(result)
    suspend fun recordSecurityAudit(audit: SecurityAuditEntity) = securityDao.insertAudit(audit)
    suspend fun recordFailureSimulation(sim: BootFailureSimulationEntity) = failureSimulationDao.insertSimulation(sim)
    suspend fun updateCModule(module: CSourceModuleEntity) = sourceModuleDao.updateModule(module)

    fun getSimulatedHardwareMetrics(): HardwareMetric {
        val cpuUsage = (12f + Random.nextFloat() * 25f).coerceIn(10f, 95f)
        val cpuTemp = (42f + Random.nextFloat() * 15f).coerceIn(35f, 90f)
        val ramUsed = (4.2f + Random.nextFloat() * 1.5f).coerceIn(2f, 16f)
        return HardwareMetric(
            cpuUsagePercent = cpuUsage,
            cpuTempCelsius = cpuTemp,
            cpuFreqGhz = 2.8f,
            ramUsedGb = ramUsed,
            ramTotalGb = 16.0f,
            nvmeHealthPercent = 99,
            nvmeReadSpeedMb = 3200,
            nvmeWriteSpeedMb = 2800,
            tpmStatus = "Virtual TPM 2.0 Active",
            secureBootStatus = "ENABLED (Platform Key Valid)"
        )
    }

    fun getAcpiTables(): List<AcpiTable> {
        return listOf(
            AcpiTable(
                signature = "DSDT",
                description = "Differentiated System Description Table",
                oemId = "MOBVM",
                oemTableId = "ARM64SOC",
                revision = 2,
                physicalAddress = "0x47002000",
                lengthBytes = 142580,
                aslCodeSnippet = """
                    DefinitionBlock ("DSDT.aml", "DSDT", 2, "MOBVM", "ARM64SOC", 0x00010000)
                    {
                        Scope (\_SB)
                        {
                            Device (UART)
                            {
                                Name (_HID, "ARMH0011") // ARM PL011 UART
                                Name (_UID, 0)
                                Name (_CRS, ResourceTemplate () {
                                    Memory32Fixed (ReadWrite, 0x09000000, 0x1000)
                                    Interrupt (ResourceConsumer, Level, ActiveHigh, Exclusive) { 33 }
                                })
                            }
                        }
                    }
                """.trimIndent()
            ),
            AcpiTable(
                signature = "MADT",
                description = "Multiple APIC / GIC Description Table",
                oemId = "MOBVM",
                oemTableId = "GICv3",
                revision = 5,
                physicalAddress = "0x47008000",
                lengthBytes = 384,
                aslCodeSnippet = "[MADT Header]\nGIC Distributor Address: 0x08000000\nGIC Redistributor Address: 0x080A0000\nFlags: ARM64 Multiprocessor Enabled"
            )
        )
    }

    fun getPartitions(): List<PartitionInfo> {
        return listOf(
            PartitionInfo(
                index = 1,
                name = "EFI System Partition (ESP)",
                fileSystem = "FAT32",
                sizeGb = 1.0,
                usedGb = 0.24,
                guid = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B",
                flags = listOf("Boot", "ESP", "System"),
                mountPoint = "/boot/efi"
            ),
            PartitionInfo(
                index = 2,
                name = "Microsoft Reserved Partition (MSR)",
                fileSystem = "RAW",
                sizeGb = 0.016,
                usedGb = 0.016,
                guid = "E3C9E316-0B5C-4DB8-817D-F92DF00215AE",
                flags = listOf("Reserved"),
                mountPoint = "N/A"
            ),
            PartitionInfo(
                index = 3,
                name = "Windows System Volume (C:)",
                fileSystem = "NTFS",
                sizeGb = 64.0,
                usedGb = 22.4,
                guid = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7",
                flags = listOf("Primary", "BitLocker-Ready"),
                mountPoint = "C:\\"
            ),
            PartitionInfo(
                index = 4,
                name = "Linux Root Partition (/)",
                fileSystem = "EXT4",
                sizeGb = 40.0,
                usedGb = 12.5,
                guid = "0FC63DAF-8483-4772-8E79-3D69D8477DE4",
                flags = listOf("Primary", "LUKS-Encrypted"),
                mountPoint = "/"
            )
        )
    }

    fun getEspFiles(): List<EspFileItem> {
        return listOf(
            EspFileItem("EFI", "/EFI", 0, true, "DIRECTORY"),
            EspFileItem("BOOTAA64.EFI", "/EFI/BOOT/BOOTAA64.EFI", 1428500, false, "EFI", "ARM64 UEFI Fallback Boot Loader Binary v2.9"),
            EspFileItem("bootmgfw.efi", "/EFI/Microsoft/Boot/bootmgfw.efi", 2150400, false, "EFI", "Microsoft Windows Boot Manager ARM64 v10.0.26100"),
            EspFileItem("BCD", "/EFI/Microsoft/Boot/BCD", 262144, false, "BCD", "Windows Boot Configuration Data Hive"),
            EspFileItem("shimx64.efi", "/EFI/Linux/shimx64.efi", 985200, false, "EFI", "Linux Shim SB Signed Loader v15.8"),
            EspFileItem("grubaa64.efi", "/EFI/Linux/grubaa64.efi", 1850000, false, "EFI", "GNU GRUB Bootloader ARM64 v2.12"),
            EspFileItem("MobileVMBootManager.efi", "/EFI/MobileVM/MobileVMBootManager.efi", 3140000, false, "EFI", "MobileVM Custom Consolidated Boot Manager v1.0")
        )
    }

    fun getTpmPcrBank(): List<PcrRegister> {
        return listOf(
            PcrRegister(0, "PCR_00 (Core Executable Code)", "8A2B9F0E134C56789D0123456789ABCDEF0123456789ABCDEF0123456789ABCD", "Primary BIOS / Firmware Core Executable"),
            PcrRegister(1, "PCR_01 (Core Configuration Data)", "1E3F5A7B9C1234567890ABCDEF0123456789ABCDEF0123456789ABCDEF012345", "Platform Host & Motherboard Configuration"),
            PcrRegister(4, "PCR_04 (ESP Executables)", "A1B2C3D4E5F60718293A4B5C6D7E8F90123456789ABCDEF0123456789ABCDEF0", "Boot Manager Executables in ESP"),
            PcrRegister(7, "PCR_07 (Secure Boot Policy)", "7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C8D9E0F1A2B3C4D5E6F7A8B", "Secure Boot PK/KEK/db Policy Hash")
        )
    }

    fun getSecureBootKeys(): List<SecureBootKey> {
        return listOf(
            SecureBootKey("PK (Platform Key)", "{A982C3D4-5E6F-7A8B-9C0D-1E2F3A4B5C6D}", "MobileVM Firmware Auth Root CA", "MobileVM Platform Key 2026", "2026-01-01", "2036-01-01", "RSA-4096 / SHA-256"),
            SecureBootKey("KEK (Key Exchange)", "{1B2C3D4E-5F6A-7B8C-9D0E-1F2A3B4C5D6E}", "Microsoft Corporation KEK 2023", "Microsoft Corporation KEK 2023", "2023-05-10", "2038-05-10", "RSA-2048 / SHA-256"),
            SecureBootKey("db (Allowed DB)", "{7C8D9E0F-1A2B-3C4D-5E6F-7A8B9C0D1E2F}", "Microsoft Windows Production PCA 2011", "Microsoft Windows PCA 2011", "2011-10-19", "2031-10-19", "RSA-2048 / SHA-256")
        )
    }

    fun getArchitectureDocs(): List<ArchitectureDoc> {
        return listOf(
            ArchitectureDoc(
                id = "boot_chain",
                title = "Core Boot Chain Specification",
                category = "Core Architecture",
                contentMarkdown = """
                    # MobileVM Core Boot Chain Specification

                    ```
                    Android Host Power ON
                       └──> MobileVM Hypervisor Core (KVM / Interpreter)
                             └──> ARM64 EDK2 UEFI Firmware (QEMU_EFI.fd)
                                   └──> TPM 2.0 & Secure Boot Verification (PK/KEK/db)
                                         └──> Mount ESP FAT32 Volume
                                               └──> MobileVMBootManager.efi
                                                     ├──> Windows 11 ARM64 Boot Manager (bootmgfw.efi)
                                                     ├──> Linux ARM64 Kernel (vmlinuz + initramfs)
                                                     ├──> Recovery & BCD Repair Console
                                                     └──> ACPI & Hardware Diagnostics Suite
                    ```
                """.trimIndent()
            ),
            ArchitectureDoc(
                id = "failure_handling",
                title = "Failure Handling Matrix",
                category = "Recovery",
                contentMarkdown = """
                    # MobileVM Failure Handling Matrix

                    When an OS loader or storage component is missing or corrupt:

                    - **Windows missing**: `bootmgfw.efi` absent -> Redirect to Automated BCD & Repair Console.
                    - **Linux missing**: `shimx64.efi` absent -> Redirect to GRUB/Initramfs Recovery.
                    - **ESP damaged**: FAT32 cluster corruption -> Re-create `/EFI/BOOT/BOOTAA64.EFI`.
                    - **Boot file missing**: Kernel or BCD lost -> Boot Configuration Rebuilder.
                    - **Secure Boot failure**: Signature check mismatch -> Certificate & Key Manager Console.
                    - **Storage unavailable**: VirtIO block timeout -> Hardware Diagnostics Suite.
                """.trimIndent()
            )
        )
    }

    fun getProjectTree(): ProjectFileNode {
        return ProjectFileNode(
            path = "MobileVM",
            name = "MobileVM",
            isDirectory = true,
            children = listOf(
                ProjectFileNode("MobileVM/boot-manager", "boot-manager", true, children = listOf(
                    ProjectFileNode("MobileVM/boot-manager/src", "src", true, children = listOf(
                        ProjectFileNode("MobileVM/boot-manager/src/main.c", "main.c", false, description = "EFI Entry Point"),
                        ProjectFileNode("MobileVM/boot-manager/src/boot_manager.c", "boot_manager.c", false, description = "Boot Dispatcher"),
                        ProjectFileNode("MobileVM/boot-manager/src/windows.c", "windows.c", false, description = "bootmgfw.efi Handoff"),
                        ProjectFileNode("MobileVM/boot-manager/src/linux.c", "linux.c", false, description = "shimx64.efi Handoff"),
                        ProjectFileNode("MobileVM/boot-manager/src/boot_fallback.c", "boot_fallback.c", false, description = "Failure Handler Matrix")
                    ))
                ))
            )
        )
    }
}
