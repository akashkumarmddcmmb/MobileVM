package com.example.vm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vm.core.VMConfig
import com.example.vm.core.VMError
import com.example.vm.core.VMState
import com.example.vm.cpu.HostArchitecture
import com.example.vm.memory.MemoryManager
import com.example.vm.runtime.VMRuntimeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Memory Safety Classification for Guest RAM allocation vs Host Capacity.
 */
enum class MemorySafetyStatus {
    SAFE,
    WARNING_HIGH_USAGE,
    DANGER_LMK_PRESSURE
}

/**
 * State representing CPU hardware resources.
 */
data class CpuResourceState(
    val allocatedCores: Int = 2,
    val activeCores: Int = 0,
    val usagePercent: Float = 0f,
    val hostPhysicalCores: Int = Runtime.getRuntime().availableProcessors(),
    val frequencyMhz: Long = 2400L,
    val isThrottled: Boolean = false
)

/**
 * State representing RAM memory resources.
 */
data class RamResourceState(
    val allocatedMb: Int = 2048,
    val usedGuestMb: Int = 0,
    val hostTotalMb: Long = 0L,
    val hostAvailableMb: Long = 0L,
    val safetyStatus: MemorySafetyStatus = MemorySafetyStatus.SAFE,
    val lowMemoryKillerAlert: Boolean = false
)

/**
 * State representing Virtual Disk and Storage resources.
 */
data class StorageResourceState(
    val diskImagePath: String = "",
    val allocatedGb: Int = 20,
    val actualPhysicalSizeBytes: Long = 0L,
    val isSparse: Boolean = true,
    val readOpsCount: Long = 0L,
    val writeOpsCount: Long = 0L,
    val ioThroughputKbps: Float = 0f
)

/**
 * Consolidated Hardware Resource Snapshot.
 */
data class HardwareResourceState(
    val cpu: CpuResourceState = CpuResourceState(),
    val ram: RamResourceState = RamResourceState(),
    val storage: StorageResourceState = StorageResourceState(),
    val hypervisorBackend: String = "ARM64 KVM / Software Emulation",
    val isHardwareVirtualizationActive: Boolean = false
)

/**
 * ViewModel that tracks the complete lifecycle status of the Virtual Machine
 * (e.g. STOPPED, CREATED, VALIDATING, PROVISIONING, READY, STARTING, BOOTING, RUNNING, PAUSED, STOPPING, FAILED)
 * and manages the real-time hardware resource state for CPU, RAM, and Storage.
 */
class VMLifecycleHardwareViewModel(
    application: Application,
    private val runtimeManager: VMRuntimeManager = VMRuntimeManager.getInstance(application)
) : AndroidViewModel(application) {

    private val memoryManager = MemoryManager(application)

    // 1. VM Lifecycle State Tracking
    private val _vmLifecycleState = MutableStateFlow<VMState>(VMState.STOPPED)
    val vmLifecycleState: StateFlow<VMState> = _vmLifecycleState.asStateFlow()

    private val _activeConfig = MutableStateFlow<VMConfig?>(null)
    val activeConfig: StateFlow<VMConfig?> = _activeConfig.asStateFlow()

    private val _lastError = MutableStateFlow<VMError?>(null)
    val lastError: StateFlow<VMError?> = _lastError.asStateFlow()

    // 2. Hardware Resource State Management
    private val _hardwareState = MutableStateFlow(HardwareResourceState())
    val hardwareState: StateFlow<HardwareResourceState> = _hardwareState.asStateFlow()

    // 3. Telemetry Polling Job
    private var telemetryJob: Job? = null

    init {
        // Observe lifecycle transitions from runtime manager
        viewModelScope.launch {
            runtimeManager.state.collect { state ->
                _vmLifecycleState.value = state
                updateHardwareStateForLifecycle(state)
            }
        }

        // Initialize host baseline telemetry
        refreshHardwareTelemetry()
        startTelemetryMonitoring()
    }

    /**
     * Start the Virtual Machine with the specified hardware configuration.
     */
    fun startVM(config: VMConfig) {
        _activeConfig.value = config
        _lastError.value = null

        // Update allocated resources immediately
        _hardwareState.update { current ->
            current.copy(
                cpu = current.cpu.copy(allocatedCores = config.cpuCores),
                ram = current.ram.copy(allocatedMb = config.ramSizeMb),
                storage = current.storage.copy(
                    diskImagePath = config.diskImagePath,
                    allocatedGb = config.diskSizeGb
                ),
                isHardwareVirtualizationActive = config.useHardwareVirtualization
            )
        }

        runtimeManager.startVM(
            config = config,
            onStateChange = { state ->
                _vmLifecycleState.value = state
            },
            onComplete = { error ->
                _lastError.value = error
            }
        )
    }

    /**
     * Gracefully initiate guest shutdown and stop the VM.
     */
    fun stopVM() {
        runtimeManager.stopVM()
    }

    /**
     * Pause execution of all virtual CPU cores.
     */
    fun pauseVM() {
        runtimeManager.pauseVM()
    }

    /**
     * Resume execution of virtual CPU cores.
     */
    fun resumeVM() {
        runtimeManager.resumeVM()
    }

    /**
     * Reset the virtual hardware machine.
     */
    fun resetVM() {
        runtimeManager.resetVM()
    }

    /**
     * Allocate CPU core count for new or editable VM instances.
     */
    fun allocateCpuCores(cores: Int) {
        val clampedCores = cores.coerceIn(1, 16)
        _hardwareState.update { current ->
            current.copy(cpu = current.cpu.copy(allocatedCores = clampedCores))
        }
    }

    /**
     * Allocate RAM in megabytes with real-time host safety evaluation.
     */
    fun allocateRam(ramMb: Int) {
        val stats = memoryManager.getHostMemoryStats()
        val safety = memoryManager.getMemorySafetyRecommendation(ramMb)
        val safetyStatus = when (safety) {
            is MemoryManager.SafetyResult.Danger -> MemorySafetyStatus.DANGER_LMK_PRESSURE
            is MemoryManager.SafetyResult.Warning -> MemorySafetyStatus.WARNING_HIGH_USAGE
            is MemoryManager.SafetyResult.Safe -> MemorySafetyStatus.SAFE
        }

        _hardwareState.update { current ->
            current.copy(
                ram = current.ram.copy(
                    allocatedMb = ramMb,
                    hostTotalMb = stats.totalMb,
                    hostAvailableMb = stats.availableMb,
                    safetyStatus = safetyStatus,
                    lowMemoryKillerAlert = safetyStatus == MemorySafetyStatus.DANGER_LMK_PRESSURE
                )
            )
        }
    }

    /**
     * Allocate Storage size in Gigabytes and configure sparse allocation mode.
     */
    fun allocateStorage(sizeGb: Int, isSparse: Boolean = true, imagePath: String = "") {
        val clampedGb = sizeGb.coerceIn(2, 256)
        val file = if (imagePath.isNotBlank()) File(imagePath) else null
        val actualBytes = file?.takeIf { it.exists() }?.length() ?: 0L

        _hardwareState.update { current ->
            current.copy(
                storage = current.storage.copy(
                    allocatedGb = clampedGb,
                    isSparse = isSparse,
                    diskImagePath = imagePath,
                    actualPhysicalSizeBytes = actualBytes
                )
            )
        }
    }

    /**
     * Refreshes host physical hardware stats (RAM, CPU cores, Disk).
     */
    fun refreshHardwareTelemetry() {
        val stats = memoryManager.getHostMemoryStats()
        val safety = memoryManager.getMemorySafetyRecommendation(_hardwareState.value.ram.allocatedMb)
        val safetyStatus = when (safety) {
            is MemoryManager.SafetyResult.Danger -> MemorySafetyStatus.DANGER_LMK_PRESSURE
            is MemoryManager.SafetyResult.Warning -> MemorySafetyStatus.WARNING_HIGH_USAGE
            is MemoryManager.SafetyResult.Safe -> MemorySafetyStatus.SAFE
        }

        val engine = runtimeManager.getActiveEngine()
        val cpuUsage = engine?.cpuUsage?.value ?: 0f
        val ramUsageMb = (engine?.ramUsage?.value?.times(_hardwareState.value.ram.allocatedMb) ?: 0f).toInt()

        _hardwareState.update { current ->
            current.copy(
                cpu = current.cpu.copy(
                    usagePercent = if (_vmLifecycleState.value == VMState.RUNNING) cpuUsage else 0f,
                    activeCores = if (_vmLifecycleState.value == VMState.RUNNING) current.cpu.allocatedCores else 0
                ),
                ram = current.ram.copy(
                    hostTotalMb = stats.totalMb,
                    hostAvailableMb = stats.availableMb,
                    usedGuestMb = ramUsageMb,
                    safetyStatus = safetyStatus,
                    lowMemoryKillerAlert = safetyStatus == MemorySafetyStatus.DANGER_LMK_PRESSURE
                )
            )
        }
    }

    private fun updateHardwareStateForLifecycle(state: VMState) {
        when (state) {
            VMState.RUNNING -> {
                _hardwareState.update { current ->
                    current.copy(
                        cpu = current.cpu.copy(activeCores = current.cpu.allocatedCores)
                    )
                }
            }
            VMState.STOPPED, VMState.FAILED, VMState.STOPPING -> {
                _hardwareState.update { current ->
                    current.copy(
                        cpu = current.cpu.copy(activeCores = 0, usagePercent = 0f),
                        ram = current.ram.copy(usedGuestMb = 0)
                    )
                }
            }
            else -> {
                // Keep provisioning / intermediate state
            }
        }
    }

    private fun startTelemetryMonitoring() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                refreshHardwareTelemetry()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryJob?.cancel()
    }
}
