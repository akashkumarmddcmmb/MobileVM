package com.example.vm.core

import android.content.Context
import com.example.vm.console.UartPL011ConsoleBackend
import com.example.vm.cpu.CPUBackend
import com.example.vm.cpu.CPUBackendSelector
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.cpu.InterpreterArm64CPUBackend
import com.example.vm.devices.DeviceManager
import com.example.vm.display.VirtioGPUBitmapDisplayBackend
import com.example.vm.input.AndroidInputBackend
import com.example.vm.input.InputBackend
import com.example.vm.input.VirtualInputDevice
import com.example.vm.memory.HostByteBufferMemoryBackend
import com.example.vm.memory.MemoryBackend
import com.example.vm.nativebridge.NativeVMBinding
import com.example.vm.network.VirtualEthernetDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VMInstance(
    val context: Context,
    val config: VMConfig,
    val onStateChanged: (VMState) -> Unit
) {
    private val _state = MutableStateFlow(VMState.CREATED)
    val state: StateFlow<VMState> = _state.asStateFlow()

    var nativeHandle: Long = 0L
        private set

    val hostArchitecture = HostArchitecture.detect()
    val guestArchitecture = config.getGuestArch()

    var isFallbackEmulation = false
        private set
    var backendStatusMessage = ""
        private set

    /**
     * Strict verification: NEVER returns true unless hardware virtualization (KVM/pKVM)
     * is genuinely initialized and executing on the native hypervisor core.
     */
    val isActuallyHardwareAccelerated: Boolean
        get() {
            if (!config.useHardwareVirtualization) return false
            if (guestArchitecture != GuestArchitecture.ARM64) return false
            if (hostArchitecture != HostArchitecture.ARM64) return false
            if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
                return NativeVMBinding.nativeIsHardwareAccelerated(nativeHandle)
            }
            return false
        }

    val actualBackendName: String
        get() {
            if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
                return NativeVMBinding.nativeGetBackendDescription(nativeHandle)
            }
            return cpu.getBackendName()
        }

    val cpu: CPUBackend = InterpreterArm64CPUBackend()
    val memory: MemoryBackend = HostByteBufferMemoryBackend(config.ramSizeMb)

    val displayBackend = VirtioGPUBitmapDisplayBackend()
    val consoleBackend = UartPL011ConsoleBackend()
    val networkDevice = VirtualEthernetDevice()
    val inputBackend: InputBackend = AndroidInputBackend()
    val inputDevice: VirtualInputDevice get() = inputBackend.virtualInputDevice
    val deviceManager = DeviceManager(displayBackend, consoleBackend, networkDevice, inputDevice)

    private val instanceScope = CoroutineScope(Dispatchers.Default + Job())
    private var executionJob: Job? = null

    init {
        val resolution = CPUBackendSelector.resolve(
            guestArch = guestArchitecture,
            requestHardwareVirt = config.useHardwareVirtualization,
            hostArch = hostArchitecture,
            isKvmSupported = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeIsKvmSupported() else false,
            kvmReason = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeGetKvmReason() else "Native library not loaded"
        )
        isFallbackEmulation = resolution.isFallbackEmulation
        backendStatusMessage = resolution.statusMessage

        if (NativeVMBinding.isLoaded()) {
            nativeHandle = NativeVMBinding.nativeCreateVM(
                ramMb = config.ramSizeMb,
                diskPath = config.diskImagePath,
                numCores = config.cpuCores,
                guestArchCode = guestArchitecture.code,
                useHardwareVirt = config.useHardwareVirtualization,
                kernelPath = config.kernelImagePath,
                initramfsPath = config.initramfsPath,
                cmdline = config.kernelCmdline,
                consoleDev = config.consoleDevice
            )
            if (nativeHandle != 0L) {
                isFallbackEmulation = NativeVMBinding.nativeIsFallbackEmulation(nativeHandle)
                backendStatusMessage = NativeVMBinding.nativeGetBackendStatus(nativeHandle)
                inputDevice.bindNativeHandle(nativeHandle)
            }
        }

        consoleBackend.setSendCallback { byte ->
            if (nativeHandle != 0L) {
                NativeVMBinding.nativeWriteSerialRx(nativeHandle, byte)
            }
        }

        deviceManager.onPowerAction = { powerCode ->
            when (powerCode) {
                0x01 -> setVMState(VMState.PAUSED)
                0x02 -> {
                    consoleBackend.notifyVmShutdown()
                    setVMState(VMState.STOPPED)
                }
                0x03 -> {
                    consoleBackend.notifyVmShutdown()
                    setVMState(VMState.ERROR)
                }
            }
        }
    }

    private fun setVMState(newState: VMState) {
        if (_state.value != newState) {
            _state.value = newState
            onStateChanged(newState)
        }
    }

    fun configure() {
        if (nativeHandle != 0L) {
            NativeVMBinding.nativeConfigure(nativeHandle)
            setVMState(VMState.CREATED)
        } else {
            memory.reset()
            cpu.reset()
            deviceManager.resetAll()
            setVMState(VMState.CREATED)

            val binaryPayload = assembleBootloaderBinary()
            memory.loadBinary(0L, binaryPayload)
        }
    }

    fun start() {
        if (_state.value != VMState.CREATED && _state.value != VMState.STOPPED) return

        if (nativeHandle != 0L) {
            NativeVMBinding.nativeStart(nativeHandle)
            setVMState(VMState.RUNNING)

            executionJob?.cancel()
            executionJob = instanceScope.launch {
                while (_state.value == VMState.RUNNING) {
                    NativeVMBinding.nativeStepCycles(nativeHandle, 500)

                    val nativeStateCode = NativeVMBinding.nativeGetState(nativeHandle)
                    val currentNativeState = mapNativeState(nativeStateCode)
                    if (currentNativeState != _state.value) {
                        setVMState(currentNativeState)
                        if (currentNativeState.isTerminal() || currentNativeState == VMState.PAUSED) {
                            break
                        }
                    }

                    val txBytes = NativeVMBinding.nativeFetchSerialTx(nativeHandle)
                    if (txBytes != null && txBytes.isNotEmpty()) {
                        consoleBackend.writeTxBytes(txBytes)
                    }

                    val rawRegs = NativeVMBinding.nativeGetRegisters(nativeHandle)
                    if (rawRegs != null && rawRegs.size >= 34) {
                        for (i in 0 until 32) {
                            cpu.registers[i] = rawRegs[i]
                        }
                        cpu.pc = rawRegs[32]
                        cpu.sp = rawRegs[33]
                    }

                    delay(10)
                }
            }
        } else {
            setVMState(VMState.RUNNING)
            cpu.isHalted = false
            cpu.isPaused = false

            executionJob?.cancel()
            executionJob = instanceScope.launch {
                while (_state.value == VMState.RUNNING) {
                    if (cpu.isHalted) {
                        setVMState(VMState.STOPPED)
                        break
                    }
                    if (cpu.isPaused) {
                        setVMState(VMState.PAUSED)
                        break
                    }

                    val stepDisassembly = cpu.stepInstruction(memory, deviceManager)
                    if (stepDisassembly.startsWith("TRAP")) {
                        consoleBackend.writeTxChar('\n')
                        stepDisassembly.forEach { consoleBackend.writeTxChar(it) }
                        consoleBackend.writeTxChar('\n')
                        setVMState(VMState.ERROR)
                        break
                    }

                    displayBackend.syncScanout()
                    delay(1)
                }
            }
        }
    }

    fun pause() {
        if (_state.value == VMState.RUNNING) {
            if (nativeHandle != 0L) {
                NativeVMBinding.nativePause(nativeHandle)
            } else {
                cpu.isPaused = true
            }
            setVMState(VMState.PAUSED)
        }
    }

    fun resume() {
        if (_state.value == VMState.PAUSED) {
            if (nativeHandle != 0L) {
                NativeVMBinding.nativeResume(nativeHandle)
                setVMState(VMState.RUNNING)
                start()
            } else {
                cpu.isPaused = false
                setVMState(VMState.RUNNING)
                start()
            }
        }
    }

    fun stop(): String? {
        if (nativeHandle != 0L) {
            NativeVMBinding.nativeStop(nativeHandle)
        } else {
            cpu.isHalted = true
        }
        executionJob?.cancel()
        consoleBackend.notifyVmShutdown()
        setVMState(VMState.STOPPED)
        return null
    }

    fun reset() {
        stop()
        if (nativeHandle != 0L) {
            NativeVMBinding.nativeReset(nativeHandle)
            setVMState(VMState.RUNNING)
            start()
        } else {
            configure()
            start()
        }
    }

    fun destroy() {
        stop()
        if (nativeHandle != 0L) {
            NativeVMBinding.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        deviceManager.resetAll()
        memory.reset()
    }

    private fun mapNativeState(code: Int): VMState {
        return when (code) {
            0 -> VMState.CREATED
            1 -> VMState.STARTING
            2 -> VMState.RUNNING
            3 -> VMState.PAUSED
            4 -> VMState.STOPPING
            5 -> VMState.STOPPED
            else -> VMState.ERROR
        }
    }

    private fun assembleBootloaderBinary(): ByteArray {
        val ops = mutableListOf<Byte>()
        fun emit32(value: Long) {
            ops.add((value and 0xFF).toByte())
            ops.add(((value shr 8) and 0xFF).toByte())
            ops.add(((value shr 16) and 0xFF).toByte())
            ops.add(((value shr 24) and 0xFF).toByte())
        }

        emit32(0xD2800000L or (0x0900L shl 5) or 1L)
        val banner = "=== MobileVM ARM64 Microkernel Initialized ===\n" +
                     "[Init] Booting interpreter vCPU\n" +
                     "[Init] Mounted rootfs (4KB memory blocks)\n" +
                     "guest@mobilevm:~$ "
        for (char in banner) {
            emit32(0xD2800000L or ((char.code.toLong() and 0xFFFFL) shl 5) or 3L)
            emit32(0x39000000L or (1L shl 5) or 3L)
        }
        emit32(0xD4400000L)
        return ops.toByteArray()
    }
}
