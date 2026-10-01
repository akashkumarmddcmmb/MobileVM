package com.example.vm.core

import android.content.Context
import android.util.Log
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

class VMEngine(
    val context: Context,
    val config: VMConfig
) {
    companion object {
        private const val TAG = "VMEngine"
    }

    private val lifecycleLock = Any()
    private var instance: VMInstance? = null

    val hostArchitecture: HostArchitecture = HostArchitecture.detect()
    val guestArchitecture: GuestArchitecture = config.getGuestArch()

    var isFallbackEmulation: Boolean = false
        private set
    var backendStatusMessage: String = ""
        private set

    val isActuallyHardwareAccelerated: Boolean
        get() = synchronized(lifecycleLock) { instance?.isActuallyHardwareAccelerated ?: false }

    val actualBackendName: String
        get() = synchronized(lifecycleLock) {
            instance?.actualBackendName ?: if (config.getGuestArch() == GuestArchitecture.ARM64) {
                "ARM64 Software Emulation (Pending Start)"
            } else {
                "Unsupported Guest Target"
            }
        }

    val nativeHandle: Long
        get() = synchronized(lifecycleLock) { instance?.nativeHandle ?: 0L }

    fun getActiveInstance(): VMInstance? = synchronized(lifecycleLock) { instance }

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

    fun start(): VMError? = synchronized(lifecycleLock) {
        val currentState = _state.value
        Log.i(TAG, "Start requested for VM '${config.name}' (current state: $currentState)")

        if (!currentState.canStart()) {
            val err = VMError.lifecycleError(
                summary = "VM is in state $currentState and cannot be started.",
                details = "Current state machine value is $currentState (canStart() returned false).",
                remedy = "Reset or recreate the VM instance before restarting."
            )
            _lastError.value = err
            return err
        }

        // Clean up any stale leftover instance before starting fresh
        if (instance != null) {
            Log.w(TAG, "Found existing instance during start; cleaning up stale handles")
            cleanupInternal()
        }

        // 1. Comprehensive Pre-Flight Validation (Config, RAM, Storage, Kernel, Initramfs, Disk, KVM)
        val validation = VMStartValidator.validate(context, config)
        if (validation is VMStartValidator.ValidationResult.Invalid) {
            val err = validation.error
            _state.value = if (err.category == VMErrorCategory.KERNEL_MISSING && config.kernelImagePath.isEmpty()) {
                VMState.NOT_VERIFIED
            } else {
                VMState.ERROR
            }
            _lastError.value = err
            Log.e(TAG, "Pre-flight validation failed: ${err.summary}")
            return err
        }

        // 2. Construct VMInstance
        var createdInst: VMInstance? = null
        val vmInst = VMInstance(context, config) { newState ->
            if (newState == VMState.ERROR || newState == VMState.FAILED) {
                val pc = createdInst?.cpu?.pc ?: 0L
                _lastError.value = VMError.unexpectedShutdown(exitCode = 1, lastPc = pc)
                cleanupInternal()
                _state.value = VMState.STOPPED
            } else {
                _state.value = newState
            }
        }
        createdInst = vmInst
        instance = vmInst
        isFallbackEmulation = vmInst.isFallbackEmulation
        backendStatusMessage = vmInst.backendStatusMessage
        displayDevice = vmInst.displayBackend
        serialConsole = vmInst.consoleBackend
        inputBackend = vmInst.inputBackend

        // 3. Configure guest physical RAM, DTB, registers & load kernel/firmware
        _state.value = VMState.CONFIGURED
        val configured = vmInst.configure()
        if (!configured) {
            Log.e(TAG, "VM configure failed. Triggering complete partial-start resource cleanup.")
            cleanupInternal()
            _state.value = VMState.ERROR
            val err = VMError.guestBootFailed("Kernel/Firmware setup could not execute on this host environment.")
            _lastError.value = err
            return err
        }

        // 4. Start real CPU execution thread
        _state.value = VMState.STARTING
        val started = vmInst.start()
        if (!started) {
            Log.e(TAG, "VM start execution failed. Cleaning up all acquired resources.")
            cleanupInternal()
            _state.value = VMState.ERROR
            val err = VMError.guestBootFailed("Failed to launch VM execution thread.")
            _lastError.value = err
            return err
        }

        _lastError.value = null
        startTelemetryMonitor(vmInst)
        Log.i(TAG, "VM '${config.name}' started successfully")
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
                if (currentState == VMState.RUNNING || currentState == VMState.STARTING || currentState == VMState.BOOTING) {
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

    fun stop(): String? = synchronized(lifecycleLock) {
        val currentState = _state.value
        Log.i(TAG, "Stop requested for VM '${config.name}' (current state: $currentState)")

        if (currentState == VMState.STOPPED || currentState == VMState.NOT_VERIFIED) {
            cleanupInternal()
            return null
        }

        _state.value = VMState.STOPPING
        cleanupInternal()
        _state.value = VMState.STOPPED
        _cpuUsage.value = null
        _ramUsage.value = 0f
        _cpuRegisters.value = emptyMap()
        Log.i(TAG, "VM '${config.name}' stopped and cleaned up cleanly")
        return null
    }

    private fun cleanupInternal() {
        monitorJob?.cancel()
        monitorJob = null
        val vm = instance
        if (vm != null) {
            try {
                vm.stop()
                vm.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error during instance destruction: ${e.message}", e)
            }
            instance = null
        }
    }

    fun pause(): String? = synchronized(lifecycleLock) {
        val vm = instance ?: return "VM Instance is not active."
        val ok = vm.pause()
        if (ok) {
            _state.value = VMState.PAUSED
            _cpuUsage.value = null
            return null
        }
        return "Cannot pause VM in state ${_state.value}."
    }

    fun resume(): String? = synchronized(lifecycleLock) {
        val vm = instance ?: return "VM Instance is not active."
        val ok = vm.resume()
        if (ok) {
            _state.value = vm.state.value
            return null
        }
        return "Cannot resume VM in state ${_state.value}."
    }

    fun reset(): String? = synchronized(lifecycleLock) {
        val vm = instance ?: return "VM Instance is not active."
        val ok = vm.reset()
        if (ok) {
            _state.value = vm.state.value
            return null
        }
        return "Failed to reset VM instance."
    }

    fun restart(): VMError? = synchronized(lifecycleLock) {
        Log.i(TAG, "Restart requested for VM '${config.name}'. Executing sequential stop-cleanup-start.")
        stop()
        return start()
    }

    fun clearError() {
        _lastError.value = null
    }

    fun destroy() = synchronized(lifecycleLock) {
        cleanupInternal()
        _state.value = VMState.STOPPED
        _cpuUsage.value = 0f
        _ramUsage.value = 0f
        _cpuRegisters.value = emptyMap()
    }
}
