package com.example.vm.core

import android.content.Context
import com.example.vm.console.ConsoleBackend
import com.example.vm.console.UartPL011ConsoleBackend
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.display.DisplayBackend
import com.example.vm.display.VirtioGPUBitmapDisplayBackend
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.kernel.GuestKernelManager
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

    // Real CPU utilization: null indicates unmeasured/unavailable (never fake)
    private val _cpuUsage = MutableStateFlow<Float?>(null)
    val cpuUsage: StateFlow<Float?> = _cpuUsage.asStateFlow()

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

        // 1. Genuine ARM64 Linux Kernel validation & sandbox enforcement
        if (config.kernelImagePath.isEmpty()) {
            val err = VMError.kernelMissing(
                path = "(none)",
                details = "NOT IMPLEMENTED: An authentic ARM64 Linux Kernel Image must be imported before booting."
            )
            _state.value = VMState.NOT_VERIFIED
            _lastError.value = err
            return err
        }

        // 2. Host Physical Memory Safety validation
        val hostStats = memoryManager.getHostMemoryStats()
        val safety = memoryManager.getMemorySafetyRecommendation(config.ramSizeMb)
        if (safety is MemoryManager.SafetyResult.Danger) {
            val err = VMError.insufficientRam(config.ramSizeMb, hostStats.availableMb)
            _state.value = VMState.ERROR
            _lastError.value = err
            return err
        }

        // 3. Virtual Disk existence, sandbox containment & readability validation
        if (config.diskImagePath.isNotEmpty()) {
            if (!diskBackend.isGuestImagePathAuthorized(config.diskImagePath) && !diskBackend.isPathAuthorized(config.diskImagePath)) {
                val err = VMError.diskInvalid(config.diskImagePath, "Security Violation: Disk image path is outside authorized application storage.")
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
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

        if (!diskBackend.isGuestImagePathAuthorized(config.kernelImagePath)) {
            val err = VMError.kernelMissing(
                config.kernelImagePath,
                "Security Violation: Kernel path is outside authorized application storage."
            )
            _state.value = VMState.ERROR
            _lastError.value = err
            return err
        }

        val kernelInfo = GuestKernelManager.inspectKernel(config.kernelImagePath)
        if (!kernelInfo.exists) {
            val err = VMError.kernelMissing(config.kernelImagePath, "Kernel file does not exist on storage.")
            _state.value = VMState.ERROR
            _lastError.value = err
            return err
        }
        if (!kernelInfo.isArm64Valid) {
            val err = VMError.kernelMissing(
                config.kernelImagePath,
                "File format error: Not a valid ARM64 Linux Kernel binary (${kernelInfo.formatDescription})."
            )
            _state.value = VMState.ERROR
            _lastError.value = err
            return err
        }

        // 4. Custom Initramfs validation & sandbox enforcement (if configured)
        if (config.initramfsPath.isNotEmpty()) {
            if (!diskBackend.isGuestImagePathAuthorized(config.initramfsPath)) {
                val err = VMError.initramfsMissing(config.initramfsPath)
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
            val initrdInfo = GuestInitramfsManager.inspectInitramfs(config.initramfsPath)
            if (!initrdInfo.exists || initrdInfo.sizeBytes == 0L) {
                val err = VMError.initramfsMissing(config.initramfsPath)
                _state.value = VMState.ERROR
                _lastError.value = err
                return err
            }
            if (!initrdInfo.hasUsableInit) {
                val err = VMError.initramfsMissingInit(config.initramfsPath)
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

        // 6. Configure guest physical RAM, DTB, registers & load kernel
        _state.value = VMState.CONFIGURED
        val configured = vmInst.configure()
        if (!configured) {
            vmInst.destroy()
            instance = null
            _state.value = VMState.NOT_VERIFIED
            val err = VMError.guestBootFailed("Kernel setup could not execute on this host environment.")
            _lastError.value = err
            return err
        }

        // 7. Start real CPU execution thread
        _state.value = VMState.STARTING
        val started = vmInst.start()
        if (!started) {
            vmInst.destroy()
            instance = null
            _state.value = VMState.ERROR
            val err = VMError.guestBootFailed("Failed to launch VM execution thread.")
            _lastError.value = err
            return err
        }
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
                if (currentState == VMState.RUNNING || currentState == VMState.STARTING) {
                    val regsMap = mutableMapOf<String, Long>()
                    vm.cpu.registers.forEachIndexed { index, value ->
                        regsMap["X$index"] = value
                    }
                    regsMap["PC"] = vm.cpu.pc
                    regsMap["SP"] = vm.cpu.sp
                    _cpuRegisters.value = regsMap

                    // Real CPU usage: reported as null (unavailable) rather than fake 0.25f
                    _cpuUsage.value = null
                    _ramUsage.value = realRamFraction
                } else if (currentState.isTerminal()) {
                    _cpuUsage.value = null
                    _ramUsage.value = 0f
                    _cpuRegisters.value = emptyMap()
                    break
                }
                delay(250)
            }
        }
    }

    fun stop(): String? {
        monitorJob?.cancel()
        _state.value = VMState.STOPPING
        val vm = instance
        if (vm != null) {
            vm.stop()
        }
        _state.value = VMState.STOPPED
        _cpuUsage.value = null
        _ramUsage.value = 0f
        _cpuRegisters.value = emptyMap()
        return null
    }

    fun pause(): String? {
        val vm = instance ?: return "VM Instance is not active."
        val ok = vm.pause()
        if (ok) {
            _state.value = VMState.PAUSED
            _cpuUsage.value = null
            return null
        }
        return "Cannot pause VM in state ${_state.value}."
    }

    fun resume(): String? {
        val vm = instance ?: return "VM Instance is not active."
        val ok = vm.resume()
        if (ok) {
            _state.value = vm.state.value
            return null
        }
        return "Cannot resume VM in state ${_state.value}."
    }

    fun reset(): String? {
        val vm = instance ?: return "VM Instance is not active."
        val ok = vm.reset()
        if (ok) {
            _state.value = vm.state.value
            return null
        }
        return "Failed to reset VM instance."
    }

    fun clearError() {
        _lastError.value = null
    }

    fun destroy() {
        monitorJob?.cancel()
        instance?.destroy()
        instance = null
        _state.value = VMState.STOPPED
        _cpuUsage.value = 0f
        _ramUsage.value = 0f
        _cpuRegisters.value = emptyMap()
    }
}
