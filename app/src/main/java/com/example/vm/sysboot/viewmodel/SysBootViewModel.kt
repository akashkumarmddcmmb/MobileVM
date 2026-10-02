package com.example.vm.sysboot.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vm.persistence.VMDatabase
import com.example.vm.sysboot.data.BootEntryEntity
import com.example.vm.sysboot.data.BootFailureSimulationEntity
import com.example.vm.sysboot.data.CSourceModuleEntity
import com.example.vm.sysboot.data.DiagnosticResultEntity
import com.example.vm.sysboot.data.SecurityAuditEntity
import com.example.vm.sysboot.model.AcpiTable
import com.example.vm.sysboot.model.ArchitectureDoc
import com.example.vm.sysboot.model.EspFileItem
import com.example.vm.sysboot.model.HardwareMetric
import com.example.vm.sysboot.model.PartitionInfo
import com.example.vm.sysboot.model.PcrRegister
import com.example.vm.sysboot.model.ProjectFileNode
import com.example.vm.sysboot.model.SecureBootKey
import com.example.vm.sysboot.repository.SysBootRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BootSimulationState(
    val isRunning: Boolean = false,
    val targetOsName: String = "",
    val currentStep: String = "",
    val progress: Float = 0f,
    val terminalLogs: List<String> = emptyList(),
    val isFinished: Boolean = false,
    val hasError: Boolean = false
)

data class DiagRunState(
    val isRunning: Boolean = false,
    val runningCategory: String = "",
    val progressPercent: Int = 0,
    val currentLog: String = "",
    val liveOutput: List<String> = emptyList()
)

data class RecoveryWizardState(
    val isRunning: Boolean = false,
    val taskTitle: String = "",
    val stepText: String = "",
    val progress: Float = 0f,
    val resultLog: List<String> = emptyList(),
    val isCompleted: Boolean = false
)

class SysBootViewModel(application: Application) : AndroidViewModel(application) {
    private val db = VMDatabase.getDatabase(application)
    private val repository = SysBootRepository(
        db.bootEntryDao(),
        db.diagnosticDao(),
        db.securityDao(),
        db.sourceModuleDao(),
        db.failureSimulationDao()
    )

    val bootEntries: StateFlow<List<BootEntryEntity>> = repository.bootEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val diagnosticResults: StateFlow<List<DiagnosticResultEntity>> = repository.diagnosticResults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val securityAudits: StateFlow<List<SecurityAuditEntity>> = repository.securityAudits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cModules: StateFlow<List<CSourceModuleEntity>> = repository.cModules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failureSimulations: StateFlow<List<BootFailureSimulationEntity>> = repository.failureSimulations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _hardwareMetrics = MutableStateFlow(repository.getSimulatedHardwareMetrics())
    val hardwareMetrics: StateFlow<HardwareMetric> = _hardwareMetrics.asStateFlow()

    private val _bootSimState = MutableStateFlow(BootSimulationState())
    val bootSimState: StateFlow<BootSimulationState> = _bootSimState.asStateFlow()

    private val _diagRunState = MutableStateFlow(DiagRunState())
    val diagRunState: StateFlow<DiagRunState> = _diagRunState.asStateFlow()

    private val _recoveryState = MutableStateFlow(RecoveryWizardState())
    val recoveryState: StateFlow<RecoveryWizardState> = _recoveryState.asStateFlow()

    val acpiTables: List<AcpiTable> = repository.getAcpiTables()
    val partitionList: List<PartitionInfo> = repository.getPartitions()
    val espFiles: List<EspFileItem> = repository.getEspFiles()
    val pcrRegisters: List<PcrRegister> = repository.getTpmPcrBank()
    val secureBootKeys: List<SecureBootKey> = repository.getSecureBootKeys()
    val architectureDocs: List<ArchitectureDoc> = repository.getArchitectureDocs()
    val projectTree: ProjectFileNode = repository.getProjectTree()

    init {
        viewModelScope.launch {
            repository.initializeDefaultDataIfEmpty()
        }
        startMetricsLoop()
    }

    private fun startMetricsLoop() {
        viewModelScope.launch {
            while (true) {
                delay(2500)
                _hardwareMetrics.value = repository.getSimulatedHardwareMetrics()
            }
        }
    }

    fun addBootEntry(name: String, loaderPath: String, osType: String, kernelParams: String, isDefault: Boolean) {
        viewModelScope.launch {
            val order = (bootEntries.value.maxOfOrNull { it.displayOrder } ?: 0) + 1
            val entry = BootEntryEntity(
                name = name,
                loaderPath = loaderPath,
                kernelParams = kernelParams,
                isDefault = isDefault,
                timeoutSeconds = 5,
                osType = osType,
                displayOrder = order,
                isEnabled = true,
                secureBootRequired = true
            )
            val newId = repository.addBootEntry(entry)
            if (isDefault) {
                repository.setDefaultBootEntry(newId.toInt())
            }
        }
    }

    fun updateBootEntry(entry: BootEntryEntity) {
        viewModelScope.launch {
            repository.updateBootEntry(entry)
        }
    }

    fun deleteBootEntry(entry: BootEntryEntity) {
        viewModelScope.launch {
            repository.deleteBootEntry(entry)
        }
    }

    fun setDefaultBootEntry(id: Int) {
        viewModelScope.launch {
            repository.setDefaultBootEntry(id)
        }
    }

    fun updateCModule(module: CSourceModuleEntity) {
        viewModelScope.launch {
            repository.updateCModule(module)
        }
    }

    fun simulateFailureScenario(failureType: String) {
        viewModelScope.launch {
            val logs = mutableListOf<String>()
            val cause: String
            val action: String
            when (failureType) {
                "WINDOWS_MISSING" -> {
                    cause = "EFI binary \\EFI\\Microsoft\\Boot\\bootmgfw.efi not found on ESP partition."
                    action = "Invoked boot_fallback.c -> Redirected to Automated BCD Rebuild Console."
                    logs.add("[BOOT_FAIL] WINDOWS_MISSING: Locate ESP -> File bootmgfw.efi NOT FOUND.")
                    logs.add("[FALLBACK] Executing boot_fallback.c: Triggering Recovery/Diagnostics.")
                }
                "LINUX_MISSING" -> {
                    cause = "Shim binary \\EFI\\Linux\\shimx64.efi absent."
                    action = "Invoked boot_fallback.c -> Re-created Linux GRUB/Initramfs boot config."
                    logs.add("[BOOT_FAIL] LINUX_MISSING: Shim path \\EFI\\Linux\\shimx64.efi absent.")
                    logs.add("[FALLBACK] Executing boot_fallback.c: Triggering Recovery/Diagnostics.")
                }
                "ESP_DAMAGED" -> {
                    cause = "FAT32 boot sector magic 0xAA55 corrupt on Partition 1."
                    action = "ESP repair wizard formatted FAT32 header and re-created /EFI/ directory."
                    logs.add("[BOOT_FAIL] ESP_DAMAGED: FAT32 Volume Header corrupt on NVMe P1.")
                    logs.add("[FALLBACK] Executing boot_fallback.c: Triggering Recovery/Diagnostics.")
                }
                "SECURE_BOOT_FAIL" -> {
                    cause = "Image hash failed PK/db signature verification."
                    action = "Secure Boot policy blocked execution -> Certificate Manager launched."
                    logs.add("[BOOT_FAIL] SECURE_BOOT_FAIL: Signature mismatch in db key table.")
                    logs.add("[FALLBACK] Executing boot_fallback.c: Triggering Recovery/Diagnostics.")
                }
                else -> {
                    cause = "NVMe PCIe Gen4 controller non-responsive on PCI bus 00:01.0."
                    action = "Hardware diagnostics suite initiated controller reset."
                    logs.add("[BOOT_FAIL] STORAGE_UNAVAILABLE: NVMe controller timeout.")
                    logs.add("[FALLBACK] Executing boot_fallback.c: Triggering Recovery/Diagnostics.")
                }
            }
            repository.recordFailureSimulation(
                BootFailureSimulationEntity(
                    timestamp = System.currentTimeMillis(),
                    failureType = failureType,
                    status = "DIVERTED_TO_RECOVERY",
                    rootCause = cause,
                    recoveryAction = action,
                    detailedLog = logs.joinToString("\n")
                )
            )
            runRecoveryRepair(failureType)
        }
    }

    fun simulateBoot(entry: BootEntryEntity) {
        viewModelScope.launch {
            val logs = mutableListOf<String>()
            _bootSimState.value = BootSimulationState(
                isRunning = true,
                targetOsName = entry.name,
                currentStep = "POWER ON - CPU Reset Vector 0xFFFFFFF0",
                progress = 0.05f,
                terminalLogs = listOf("[0.000000] POWER ON: CPU Core Reset Vector Executed.")
            )
            delay(500)
            logs.add("[0.045210] SECURE_BOOT: Verifying Key PK & KEK in TPM NVRAM...")
            logs.add("[0.082104] SECURE_BOOT: Verification PASSED. SecureBoot=ENABLED.")
            _bootSimState.value = _bootSimState.value.copy(
                currentStep = "UEFI Driver Initialization & PCIe Bus Scan",
                progress = 0.25f,
                terminalLogs = logs.toList()
            )
            delay(500)
            logs.add("[0.120340] NVME_DXE: Found 1 PCIe Gen4 NVMe Controller at 00:01.0.")
            logs.add("[0.184002] ACPI: DSDT, MADT, FADT, TPM2 tables parsed from 0x47002000.")
            _bootSimState.value = _bootSimState.value.copy(
                currentStep = "ESP Partition Mount & Loader Signature Check",
                progress = 0.50f,
                terminalLogs = logs.toList()
            )
            delay(500)
            logs.add("[0.312040] ESP_FAT32: Mounted ESP at Partition 1 (GUID C12A7328...).")
            logs.add("[0.450110] LOADER: Loading EFI binary '${entry.loaderPath}'...")
            logs.add("[0.512000] KERNEL_CMDLINE: ${entry.kernelParams}")
            _bootSimState.value = _bootSimState.value.copy(
                currentStep = "Handing Off Execution to OS Kernel",
                progress = 0.85f,
                terminalLogs = logs.toList()
            )
            delay(600)
            logs.add("[1.023100] SUCCESS: Handoff to ${entry.name} complete! System booted.")
            _bootSimState.value = _bootSimState.value.copy(
                isRunning = false,
                currentStep = "Boot Sequence Completed Successfully",
                progress = 1.0f,
                isFinished = true,
                terminalLogs = logs.toList()
            )
            repository.recordSecurityAudit(
                SecurityAuditEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "BOOT_HANDOFF",
                    severity = "SUCCESS",
                    summary = "Booted ${entry.name}",
                    details = "Loader: ${entry.loaderPath} | Params: ${entry.kernelParams}"
                )
            )
        }
    }

    fun dismissBootSim() {
        _bootSimState.value = BootSimulationState()
    }

    fun runDiagnosticsSuite(categoryName: String) {
        viewModelScope.launch {
            val outputLogs = mutableListOf<String>()
            _diagRunState.value = DiagRunState(
                isRunning = true,
                runningCategory = categoryName,
                progressPercent = 5,
                currentLog = "Initializing Hardware Diagnostic Test Suite...",
                liveOutput = listOf("INIT: Allocating memory buffers and querying PCIe device tree...")
            )
            delay(400)
            val tests = listOf("CPU Stress & AVX2/NEON", "RAM Pattern Check", "NVMe Health", "TPM Self-Test")
            tests.forEachIndexed { idx, testName ->
                outputLogs.add("RUNNING: $testName...")
                val pct = ((idx + 1) * 100) / tests.size
                _diagRunState.value = _diagRunState.value.copy(
                    progressPercent = pct,
                    currentLog = "Testing $testName",
                    liveOutput = outputLogs.toList()
                )
                delay(500)
                outputLogs.add("PASS: $testName executed with zero faults.")
                _diagRunState.value = _diagRunState.value.copy(
                    liveOutput = outputLogs.toList()
                )
                delay(200)
            }
            _diagRunState.value = _diagRunState.value.copy(
                isRunning = false,
                progressPercent = 100,
                currentLog = "Diagnostic Suite Completed Successfully!",
                liveOutput = outputLogs.toList()
            )
            repository.recordDiagnosticResult(
                DiagnosticResultEntity(
                    testName = "$categoryName Comprehensive Diagnostics",
                    category = categoryName,
                    timestamp = System.currentTimeMillis(),
                    status = "PASSED",
                    scoreOrMetric = "Passed ${tests.size}/${tests.size} Subtests",
                    detailsLog = outputLogs.joinToString("\n")
                )
            )
        }
    }

    fun runRecoveryRepair(taskType: String) {
        viewModelScope.launch {
            val logs = mutableListOf<String>()
            _recoveryState.value = RecoveryWizardState(
                isRunning = true,
                taskTitle = taskType,
                stepText = "Analyzing System Volume & Bootloader State...",
                progress = 0.1f,
                resultLog = listOf("RECOVERY: Starting $taskType wizard...")
            )
            delay(500)
            when (taskType) {
                "BCD_REPAIR", "WINDOWS_MISSING" -> {
                    logs.add("SEARCH: Scanning virtual disks for Windows installations...")
                    logs.add("FOUND: C:\\Windows [Build 26100.2000 ARM64]")
                    _recoveryState.value = _recoveryState.value.copy(
                        stepText = "Rebuilding Windows Boot Configuration Data (BCD)...",
                        progress = 0.5f,
                        resultLog = logs.toList()
                    )
                    delay(600)
                    logs.add("EXEC: bcdboot C:\\Windows /s S: /f UEFI")
                    logs.add("SUCCESS: BCD store rebuilt at /EFI/Microsoft/Boot/BCD.")
                }
                "EFI_REBUILD", "LINUX_MISSING", "ESP_DAMAGED" -> {
                    logs.add("ESP: Scanning FAT32 System Partition integrity...")
                    logs.add("RECREATE: Re-installing /EFI/BOOT/BOOTAA64.EFI fallback bootloader...")
                    _recoveryState.value = _recoveryState.value.copy(
                        stepText = "Copying clean EFI stage-2 boot manager files...",
                        progress = 0.6f,
                        resultLog = logs.toList()
                    )
                    delay(600)
                    logs.add("VERIFY: Signing EFI binary with Local Platform Key (PK)...")
                    logs.add("SUCCESS: EFI System Partition structure fully restored.")
                }
                else -> {
                    logs.add("SCAN: Checking GPT Primary & Backup Headers...")
                    logs.add("CHKDSK: Validating cluster bitmap and file attributes...")
                    _recoveryState.value = _recoveryState.value.copy(
                        stepText = "Fixing file system allocations & partition flags...",
                        progress = 0.7f,
                        resultLog = logs.toList()
                    )
                    delay(600)
                    logs.add("REPAIR: Corrupt flags cleared on Partition 1 (ESP).")
                    logs.add("SUCCESS: Startup Repair completed with zero critical errors.")
                }
            }
            _recoveryState.value = _recoveryState.value.copy(
                isRunning = false,
                stepText = "$taskType Completed Successfully!",
                progress = 1.0f,
                isCompleted = true,
                resultLog = logs.toList()
            )
            repository.recordSecurityAudit(
                SecurityAuditEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "RECOVERY_ACTION",
                    severity = "SUCCESS",
                    summary = "Executed $taskType",
                    details = logs.joinToString(" | ")
                )
            )
        }
    }

    fun dismissRecoveryState() {
        _recoveryState.value = RecoveryWizardState()
    }

    fun signEfiBinary(binaryName: String) {
        viewModelScope.launch {
            repository.recordSecurityAudit(
                SecurityAuditEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "EFI_SIGNATURE",
                    severity = "SUCCESS",
                    summary = "Signed $binaryName with Custom PK",
                    details = "Digest SHA256 created and added to db certificate table."
                )
            )
        }
    }
}
