package com.example.vm.core

import android.content.Context
import com.example.vm.console.ConsoleBackend
import com.example.vm.console.UartPL011ConsoleBackend
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.display.DisplayBackend
import com.example.vm.display.VirtioGPUBitmapDisplayBackend
import com.example.vm.memory.MemoryManager
import com.example.vm.storage.AndroidStorageDiskBackend
import com.example.vm.input.InputBackend
import com.example.vm.input.AndroidInputBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class VMEngine(
    val context: Context,
    val config: VMConfig
) {
    private var instance: VMInstance? = null

    val hostArchitecture: HostArchitecture = HostArchitecture.detect()
    val guestArchitecture: GuestArchitecture = config.getGuestArch()

    var isFallbackEmulation: Boolean = false
        private set
    var backendStatusMessage: String = ""
        private set

    val isActuallyHardwareAccelerated: Boolean
        get() = instance?.isActuallyHardwareAccelerated ?: false

    val actualBackendName: String
        get() = instance?.actualBackendName ?: if (config.getGuestArch() == GuestArchitecture.ARM64) {
            "ARM64 Software Emulation (Pending Start)"
        } else {
            "Unsupported Guest Target"
        }

    val nativeHandle: Long
        get() = instance?.nativeHandle ?: 0L

    fun getActiveInstance(): VMInstance? = instance

    private val _state = MutableStateFlow(VMState.CREATED)
    val state: StateFlow<VMState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<VMError?>(null)
    val lastError: StateFlow<VMError?> = _lastError.asStateFlow()

    private val _cpuUsage = MutableStateFlow(0f)
    val cpuUsage: StateFlow<Float> = _cpuUsage.asStateFlow()

    private val _ramUsage = MutableStateFlow(0f)
    val ramUsage: StateFlow<Float> = _ramUsage.asStateFlow()

    private val _cpuRegisters = MutableStateFlow<Map<String, Long>>(emptyMap())
    val cpuRegisters: StateFlow<Map<String, Long>> = _cpuRegisters.asStateFlow()

    val diskBackend = AndroidStorageDiskBackend(context)
    val memoryManager = MemoryManager(context)

    var displayDevice: DisplayBackend = VirtioGPUBitmapDisplayBackend()
        private set
    var serialConsole: ConsoleBackend = UartPL011ConsoleBackend()
        private set
    var inputBackend: InputBackend = AndroidInputBackend()
        private set

    private val monitorScope = CoroutineScope(Dispatchers.Default + Job())
    private var monitorJob: Job? = null

    fun start(): VMError? {
        val currentState = _state.value
        if (!currentState.canStart()) {
            val err = VMError(
                category = VMErrorCategory.EMULATOR_INIT_FAILED,
                summary = "VM is in state $currentState and cannot be started.",
                technicalDetails = "Current state machine value is $currentState (canStart() returned false).",
                suggestedRemedy = "Reset or recreate the VM instance before restarting."
            )
            _lastError.value = err
            return err
        }

        // 1. Host Physical Memory Safety validation
        val hostStats = memoryManager.getHostMemoryStats()
        val safety = memoryManager.getMemorySafetyRecommendation(config.ramSizeMb)
        if (safety is MemoryManager.SafetyResult.Danger) {
            val err = VMError.insufficientRam(config.ramSizeMb, hostStats.availableMb)
            _state.value = VMState.ERROR
            _lastError.value = err
            return err
        }

        // 2. Virtual Disk existence & readability validation
        if (config.diskImagePath.isNotEmpty()) {
            val file = File(config.diskImagePath)
            if (!file.exists()) {
                val err = VMError.diskInvalid(config.diskImagePath, "Virtual disk file does not exist on disk storage.")
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
            if (file.length() < 512) {
                val err = VMError.diskInvalid(config.diskImagePath, "Disk file size (${file.length()} bytes) is too small to contain valid MBR/GPT sectors.")
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
        }

        // 3. Custom Linux Kernel image validation (if configured)
        if (config.kernelImagePath.isNotEmpty()) {
            val kFile = File(config.kernelImagePath)
            if (!kFile.exists()) {
                val err = VMError.kernelMissing(config.kernelImagePath, "File does not exist on Android filesystem.")
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
            if (!kFile.canRead()) {
                val err = VMError.kernelMissing(config.kernelImagePath, "Read permission denied for kernel file.")
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
        }

        // 4. Custom Initramfs validation (if configured)
        if (config.initramfsPath.isNotEmpty()) {
            val initrdFile = File(config.initramfsPath)
            if (!initrdFile.exists() || !initrdFile.canRead()) {
                val err = VMError.initramfsMissing(config.initramfsPath)
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
        }

        // 5. Construct VMInstance
        var createdInst: VMInstance? = null
        val vmInst = VMInstance(context, config) { newState ->
            _state.value = newState
            if (newState == VMState.ERROR) {
                val pc = createdInst?.cpu?.pc ?: 0L
                _lastError.value = VMError.unexpectedShutdown(exitCode = 1, lastPc = pc)
            }
        }
        createdInst = vmInst
        instance = vmInst
        isFallbackEmulation = vmInst.isFallbackEmulation
        backendStatusMessage = vmInst.backendStatusMessage
        displayDevice = vmInst.displayBackend
        serialConsole = vmInst.consoleBackend
        inputBackend = vmInst.inputBackend

        // 6. Configure & load assembly vectors / guest kernel
        vmInst.configure()

        // 7. Start real instruction thread
        vmInst.start()
        _lastError.value = null

        startTelemetryMonitor(vmInst)
        return null
    }

    private fun startTelemetryMonitor(vm: VMInstance) {
        monitorJob?.cancel()
        monitorJob = monitorScope.launch {
            val hostStats = memoryManager.getHostMemoryStats()
            val totalRamMb = maxOf(hostStats.totalMb.toFloat(), 1024f)
            val realRamFraction = (config.ramSizeMb.toFloat() / totalRamMb).coerceIn(0.01f, 1.0f)

            while (true) {
                val currentState = vm.state.value
                if (currentState == VMState.RUNNING) {
                    val regsMap = mutableMapOf<String, Long>()
                    vm.cpu.registers.forEachIndexed { index, value ->
                        regsMap["X$index"] = value
                    }
                    regsMap["PC"] = vm.cpu.pc
                    regsMap["SP"] = vm.cpu.sp
                    _cpuRegisters.value = regsMap

                    // Real CPU thread active execution indicator (not random noise)
                    _cpuUsage.value = if (vm.cpu.isHalted || vm.cpu.isPaused) 0f else 0.25f
                    _ramUsage.value = realRamFraction
                } else if (currentState == VMState.PAUSED) {
                    _cpuUsage.value = 0f
                } else if (currentState.isTerminal()) {
                    _cpuUsage.value = 0f
                    _ramUsage.value = 0f
                    _cpuRegisters.value = emptyMap()
                    break
                }
                delay(250)
            }
        }
    }

    fun stop(): String? {
        val vm = instance ?: return "VM Instance is not active."
        vm.stop()
        _state.value = VMState.STOPPED
        return null
    }

    fun pause(): String? {
        val vm = instance ?: return "VM Instance is not active."
        vm.pause()
        _state.value = VMState.PAUSED
        return null
    }

    fun resume(): String? {
        val vm = instance ?: return "VM Instance is not active."
        vm.resume()
        _state.value = VMState.RUNNING
        return null
    }

    fun reset(): String? {
        val vm = instance ?: return "VM Instance is not active."
        vm.reset()
        _state.value = VMState.RUNNING
        return null
    }

    fun clearError() {
        _lastError.value = null
    }

    fun destroy() {
        monitorJob?.cancel()
        instance?.destroy()
        instance = null
    }
}
