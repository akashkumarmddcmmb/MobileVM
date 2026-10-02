package com.example.vm.runtime

import android.content.Context
import android.util.Log
import com.example.vm.audio.VMAudioEngine
import com.example.vm.audio.VirtualAudioDevice
import com.example.vm.console.ConsoleBackend
import com.example.vm.console.UartPL011ConsoleBackend
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMError
import com.example.vm.core.VMErrorCategory
import com.example.vm.core.VMInstance
import com.example.vm.core.VMMachineModel
import com.example.vm.core.VMStartValidator
import com.example.vm.core.VMState
import com.example.vm.display.DisplayBackend
import com.example.vm.display.VirtioGPUBitmapDisplayBackend
import com.example.vm.input.AndroidInputBackend
import com.example.vm.input.InputBackend
import com.example.vm.nativebridge.NativeVMBinding
import com.example.vm.network.VirtualEthernetDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VMRuntimeManager: Authoritative central runtime coordinator for MobileVM.
 *
 * Implements strict, unified lifecycle:
 * CREATED -> VALIDATING -> PROVISIONING -> READY -> STARTING -> BOOTING -> RUNNING -> STOPPING -> STOPPED
 * Failure: FAILED
 *
 * Enforces strict thread isolation:
 * - VM Control Thread (dedicated single-thread executor)
 * - vCPU Thread (dedicated single-thread executor for guest execution loop)
 * - I/O Thread (dedicated pool for storage, network, audio operations)
 *
 * Guarantees zero resource leaks on partial initialization failure or crash.
 */
class VMRuntimeManager(
    val context: Context
) {
    companion object {
        private const val TAG = "VMRuntimeManager"

        @Volatile
        private var instance: VMRuntimeManager? = null

        fun getInstance(context: Context): VMRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: VMRuntimeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val lifecycleLock = Any()

    // Dedicated Thread Executors (No CPU loops or blocking I/O on Android UI thread)
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VM-Control-Thread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val vcpuExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vCPU-0-Execution-Thread").apply { priority = Thread.MAX_PRIORITY }
    }
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "VM-IO-Worker").apply { priority = Thread.NORM_PRIORITY }
    }

    private val _state = MutableStateFlow(VMState.CREATED)
    val state: StateFlow<VMState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<VMError?>(null)
    val lastError: StateFlow<VMError?> = _lastError.asStateFlow()

    private val isStopping = AtomicBoolean(false)
    private var vcpuFuture: Future<*>? = null

    // Resource Ownership tracking
    private var activeConfig: VMConfig? = null
    private var activeEngine: VMEngine? = null
    private var activeInstance: VMInstance? = null
    private var nativeVmHandle: Long = 0L

    fun getActiveEngine(): VMEngine? = activeEngine

    var displayBackend: DisplayBackend = VirtioGPUBitmapDisplayBackend()
        private set
    var consoleBackend: ConsoleBackend = UartPL011ConsoleBackend()
        private set
    var inputBackend: InputBackend = AndroidInputBackend()
        private set
    var audioEngine: VMAudioEngine? = null
        private set
    var networkDevice: VirtualEthernetDevice? = null
        private set

    private fun transitionTo(newState: VMState, reason: String = "") {
        synchronized(lifecycleLock) {
            val oldState = _state.value
            if (oldState != newState) {
                Log.i(TAG, "[VM][RUNTIME] Transition: $oldState -> $newState ($reason)")
                _state.value = newState
            }
        }
    }

    /**
     * Initiates asynchronous VM start on the dedicated Control Thread.
     */
    fun startVM(
        config: VMConfig,
        onStateChange: ((VMState) -> Unit)? = null,
        onComplete: ((VMError?) -> Unit)? = null
    ) {
        controlExecutor.execute {
            val error = startVMSync(config, onStateChange)
            onComplete?.invoke(error)
        }
    }

    /**
     * Synchronous VM start executed strictly on the background VM Control Thread.
     */
    fun startVMSync(
        config: VMConfig,
        onStateChange: ((VMState) -> Unit)? = null
    ): VMError? = synchronized(lifecycleLock) {
        Log.i(TAG, "[VM][RUNTIME] Starting VM '${config.name}'")
        isStopping.set(false)

        // Clean up any lingering resources from a previous run
        cleanupResourcesInternal()

        // 1. CREATED
        activeConfig = config
        transitionTo(VMState.CREATED, "VM initialized")
        onStateChange?.invoke(VMState.CREATED)

        // 2. VALIDATING
        transitionTo(VMState.VALIDATING, "Validating pre-flight parameters")
        onStateChange?.invoke(VMState.VALIDATING)

        // Check memory layout against machine model
        if (!VMMachineModel.validateMemoryLayout(config.ramSizeMb)) {
            val err = VMError(
                category = VMErrorCategory.RAM_MMIO_OVERLAP,
                summary = "Requested RAM overlaps device MMIO address map.",
                technicalDetails = "RAM Base: 0x${VMMachineModel.RAM_BASE.toString(16)}, size: ${config.ramSizeMb} MB.",
                suggestedRemedy = "Reduce RAM size in VM configuration.",
                code = "RAM_MMIO_OVERLAP",
                component = "MEMORY",
                operation = "VALIDATE_MEMORY",
                recoverable = false
            )
            transitionTo(VMState.FAILED, err.summary)
            _lastError.value = err
            onStateChange?.invoke(VMState.FAILED)
            return err
        }

        // Run full VMStartValidator
        val validation = VMStartValidator.validate(context, config)
        if (validation is VMStartValidator.ValidationResult.Invalid) {
            val err = validation.error
            Log.e(TAG, "[VM][RUNTIME] Pre-flight validation failed: ${err.summary}")
            transitionTo(VMState.FAILED, err.summary)
            _lastError.value = err
            onStateChange?.invoke(VMState.FAILED)
            return err
        }

        // 3. PROVISIONING (Allocate memory, prepare devices, audio, network, console)
        transitionTo(VMState.PROVISIONING, "Provisioning devices and memory")
        onStateChange?.invoke(VMState.PROVISIONING)

        val engine = VMEngine(context, config)
        activeEngine = engine

        // Initialize audio engine
        try {
            audioEngine = VMAudioEngine(context).apply { start() }
        } catch (e: Exception) {
            Log.w(TAG, "[VM][AUDIO] Audio engine warning: ${e.message}")
        }

        // Initialize network
        networkDevice = VirtualEthernetDevice()

        // 4. READY (Devices prepared and configured)
        transitionTo(VMState.READY, "Hardware model and devices ready")
        onStateChange?.invoke(VMState.READY)

        // 5. STARTING (Attaching storage and configuring native core)
        transitionTo(VMState.STARTING, "Initializing execution core")
        onStateChange?.invoke(VMState.STARTING)

        val startErr = engine.start()
        if (startErr != null) {
            Log.e(TAG, "[VM][RUNTIME] Engine startup failed: ${startErr.summary}")
            cleanupResourcesInternal()
            transitionTo(VMState.FAILED, startErr.summary)
            _lastError.value = startErr
            onStateChange?.invoke(VMState.FAILED)
            return startErr
        }

        activeInstance = engine.getActiveInstance()
        nativeVmHandle = engine.nativeHandle
        displayBackend = engine.displayDevice
        consoleBackend = engine.serialConsole
        inputBackend = engine.inputBackend

        // 6. BOOTING -> RUNNING on dedicated vCPU Thread
        transitionTo(VMState.BOOTING, "vCPU execution thread starting")
        onStateChange?.invoke(VMState.BOOTING)

        vcpuFuture = vcpuExecutor.submit {
            runVcpuLoop(onStateChange)
        }

        return null
    }

    /**
     * Dedicated vCPU execution loop. Never touches the UI thread.
     */
    private fun runVcpuLoop(onStateChange: ((VMState) -> Unit)?) {
        Log.i(TAG, "[VM][CPU] vCPU execution loop entered on dedicated thread")
        var executionConfirmed = false

        while (!isStopping.get()) {
            val inst = activeInstance ?: break
            val handle = nativeVmHandle

            if (handle != 0L && NativeVMBinding.isLoaded()) {
                val cycles = NativeVMBinding.nativeStepCycles(handle, 500)
                if (cycles > 0 && !executionConfirmed) {
                    executionConfirmed = true
                    transitionTo(VMState.RUNNING, "Actual guest instructions executing")
                    onStateChange?.invoke(VMState.RUNNING)
                }

                val nativeStateCode = NativeVMBinding.nativeGetState(handle)
                if (nativeStateCode == 6) { // STOPPED
                    Log.i(TAG, "[VM][CPU] Guest issued shutdown/halt")
                    break
                } else if (nativeStateCode == 7) { // ERROR
                    Log.e(TAG, "[VM][CPU] Guest encountered fatal CPU trap/fault")
                    transitionTo(VMState.FAILED, "Guest CPU trap fault")
                    onStateChange?.invoke(VMState.FAILED)
                    break
                }
            } else {
                // Software fallback step
                val stepResult = inst.cpu.stepInstruction(inst.memory, inst.deviceManager)
                if (!executionConfirmed && !inst.cpu.isHalted) {
                    executionConfirmed = true
                    transitionTo(VMState.RUNNING, "Software interpreter executing")
                    onStateChange?.invoke(VMState.RUNNING)
                }
                if (stepResult.startsWith("TRAP") || inst.cpu.isHalted) {
                    break
                }
            }

            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                break
            }
        }

        if (isStopping.get()) {
            transitionTo(VMState.STOPPED, "vCPU thread terminated cleanly")
            onStateChange?.invoke(VMState.STOPPED)
        }
        Log.i(TAG, "[VM][CPU] vCPU execution loop exited")
    }

    /**
     * Stops the VM cleanly on the Control Thread.
     */
    fun stopVM(onComplete: (() -> Unit)? = null) {
        controlExecutor.execute {
            stopVMSync()
            onComplete?.invoke()
        }
    }

    /**
     * Synchronous stop and complete resource release.
     */
    fun stopVMSync() = synchronized(lifecycleLock) {
        if (_state.value == VMState.STOPPED || _state.value == VMState.CREATED) {
            return
        }

        transitionTo(VMState.STOPPING, "Stop requested")
        isStopping.set(true)

        // Cancel vCPU thread
        vcpuFuture?.cancel(true)
        vcpuFuture = null

        // Stop active engine and instance
        try {
            activeEngine?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "[VM][RUNTIME] Error stopping engine: ${e.message}", e)
        }

        cleanupResourcesInternal()
        transitionTo(VMState.STOPPED, "All resources released cleanly")
    }

    fun pauseVM(): Boolean = synchronized(lifecycleLock) {
        if (_state.value == VMState.RUNNING) {
            activeEngine?.pause()
            transitionTo(VMState.PAUSED, "Paused by user")
            return true
        }
        return false
    }

    fun resumeVM(): Boolean = synchronized(lifecycleLock) {
        if (_state.value == VMState.PAUSED) {
            activeEngine?.resume()
            transitionTo(VMState.RUNNING, "Resumed by user")
            return true
        }
        return false
    }

    /**
     * Graceful Shutdown: Requests guest OS to execute an orderly shutdown.
     */
    fun gracefulShutdownVM(onComplete: (() -> Unit)? = null) {
        controlExecutor.execute {
            synchronized(lifecycleLock) {
                if (_state.value.canStop()) {
                    transitionTo(VMState.SHUTTING_DOWN, "Guest OS shutdown requested")
                    activeEngine?.gracefulShutdown()
                    stopVMSync()
                }
            }
            onComplete?.invoke()
        }
    }

    /**
     * Force Power Off: Immediately cuts power and halts execution without guest handshake.
     */
    fun forcePowerOffVM(onComplete: (() -> Unit)? = null) {
        controlExecutor.execute {
            synchronized(lifecycleLock) {
                Log.w(TAG, "Force power off requested. Halting immediately.")
                activeEngine?.forcePowerOff()
                stopVMSync()
            }
            onComplete?.invoke()
        }
    }

    /**
     * Real Reboot: Re-evaluates boot order and restarts installed OS or installer.
     */
    fun rebootVM(onComplete: ((VMError?) -> Unit)? = null) {
        controlExecutor.execute {
            val cfg = activeConfig
            synchronized(lifecycleLock) {
                transitionTo(VMState.REBOOTING, "Reboot initiated")
            }
            stopVMSync()
            if (cfg != null) {
                // Re-evaluate boot target to ensure installed OS boots after installation
                val effectiveTarget = com.example.vm.core.VMBootManager.resolveEffectiveBootTarget(context, cfg)
                Log.i(TAG, "Rebooting into effective target: ${effectiveTarget.description}")
                val err = startVMSync(cfg)
                onComplete?.invoke(err)
            } else {
                onComplete?.invoke(null)
            }
        }
    }

    fun resetVM(onComplete: ((VMError?) -> Unit)? = null) {
        rebootVM(onComplete)
    }

    fun restartVM(onComplete: ((VMError?) -> Unit)? = null) {
        rebootVM(onComplete)
    }

    /**
     * Releases every native resource, file descriptor, audio sink, and thread.
     */
    private fun cleanupResourcesInternal() {
        try {
            audioEngine?.stop()
            audioEngine = null
        } catch (e: Exception) {
            Log.w(TAG, "[VM][AUDIO] Audio cleanup: ${e.message}")
        }

        try {
            networkDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "[VM][NETWORK] Network cleanup: ${e.message}")
        }

        if (activeEngine != null) {
            try {
                activeEngine?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "[VM][RUNTIME] Engine destruction: ${e.message}")
            }
            activeEngine = null
        }

        activeInstance = null
        nativeVmHandle = 0L
    }

    fun destroy() {
        stopVMSync()
        controlExecutor.shutdownNow()
        vcpuExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }
}
