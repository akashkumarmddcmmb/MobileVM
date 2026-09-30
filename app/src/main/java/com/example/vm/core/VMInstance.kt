package com.example.vm.core

import android.content.Context
import com.example.vm.audio.VMAudioEngine
import com.example.vm.audio.VirtualAudioDevice
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

    val audioEngine = VMAudioEngine(context)
    val virtualAudioDevice = VirtualAudioDevice(audioEngine)

    val displayBackend = VirtioGPUBitmapDisplayBackend()
    val consoleBackend = UartPL011ConsoleBackend()
    val networkDevice = VirtualEthernetDevice()
    val inputBackend: InputBackend = AndroidInputBackend()
    val inputDevice: VirtualInputDevice get() = inputBackend.virtualInputDevice
    val deviceManager = DeviceManager(displayBackend, consoleBackend, networkDevice, inputDevice)

    private val instanceScope = CoroutineScope(Dispatchers.Default + Job())
    private var executionJob: Job? = null

    init {
        deviceManager.registerDevice("audio_0", virtualAudioDevice)
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
                0x01 -> {
                    cpu.isPaused = true
                    setVMState(VMState.PAUSED)
                }
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

    fun configure(): Boolean {
        if (nativeHandle != 0L) {
            val configured = NativeVMBinding.nativeConfigure(nativeHandle)
            if (!configured) {
                setVMState(VMState.ERROR)
                return false
            } else {
                setVMState(VMState.CONFIGURED)
                return true
            }
        } else {
            memory.reset()
            cpu.reset()
            deviceManager.resetAll()
            setVMState(VMState.CONFIGURED)

            val binaryPayload = assembleBootloaderBinary()
            memory.loadBinary(0L, binaryPayload)
            return true
        }
    }

    fun start(): Boolean {
        if (_state.value != VMState.CONFIGURED && _state.value != VMState.STOPPED && _state.value != VMState.CREATED) return false

        audioEngine.start()

        if (nativeHandle != 0L) {
            setVMState(VMState.STARTING)
            val started = NativeVMBinding.nativeStart(nativeHandle)
            if (!started) {
                setVMState(VMState.ERROR)
                audioEngine.stop()
                return false
            }

            executionJob?.cancel()
            executionJob = instanceScope.launch {
                var firstCycles = false
                while (_state.value == VMState.RUNNING || _state.value == VMState.STARTING) {
                    val cycles = NativeVMBinding.nativeStepCycles(nativeHandle, 500)
                    if (cycles > 0 && !firstCycles) {
                        firstCycles = true
                        setVMState(VMState.RUNNING)
                    }

                    val nativeStateCode = NativeVMBinding.nativeGetState(nativeHandle)
                    val currentNativeState = mapNativeState(nativeStateCode)
                    if (currentNativeState != _state.value) {
                        setVMState(currentNativeState)
                        if (currentNativeState.isTerminal()) {
                            break
                        }
                    }

                    val txBytes = NativeVMBinding.nativeFetchSerialTx(nativeHandle)
                    if (txBytes != null && txBytes.isNotEmpty()) {
                        consoleBackend.writeTxBytes(txBytes)
                    }

                    val pcmBytes = NativeVMBinding.nativeFetchAudioPcm(nativeHandle)
                    if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                        audioEngine.writePcmData(pcmBytes)
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
            return true
        } else {
            setVMState(VMState.STARTING)
            cpu.isHalted = false
            cpu.isPaused = false

            executionJob?.cancel()
            executionJob = instanceScope.launch {
                var firstExecuted = false
                while (_state.value == VMState.RUNNING || _state.value == VMState.STARTING) {
                    if (cpu.isHalted) {
                        setVMState(VMState.STOPPED)
                        break
                    }

                    val stepDisassembly = cpu.stepInstruction(memory, deviceManager)
                    if (!firstExecuted) {
                        firstExecuted = true
                        setVMState(VMState.RUNNING)
                    }

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
            return true
        }
    }

    fun pause(): Boolean {
        if (_state.value == VMState.RUNNING || _state.value == VMState.STARTING) {
            audioEngine.pause()
            if (nativeHandle != 0L) {
                NativeVMBinding.nativePause(nativeHandle)
            } else {
                cpu.isPaused = true
            }
            setVMState(VMState.PAUSED)
            return true
        }
        return false
    }

    fun resume(): Boolean {
        if (_state.value == VMState.PAUSED) {
            audioEngine.resume()
            if (nativeHandle != 0L) {
                val ok = NativeVMBinding.nativeResume(nativeHandle)
                if (!ok) {
                    setVMState(VMState.ERROR)
                    return false
                }
                setVMState(VMState.RUNNING)
                return true
            } else {
                cpu.isPaused = false
                setVMState(VMState.RUNNING)
                return true
            }
        }
        return false
    }

    fun stop(): Boolean {
        setVMState(VMState.STOPPING)
        audioEngine.stop()
        if (nativeHandle != 0L) {
            NativeVMBinding.nativeStop(nativeHandle)
        } else {
            cpu.isHalted = true
        }
        executionJob?.cancel()
        executionJob = null
        consoleBackend.notifyVmShutdown()
        setVMState(VMState.STOPPED)
        return true
    }

    fun reset(): Boolean {
        stop()
        if (nativeHandle != 0L) {
            val ok = NativeVMBinding.nativeReset(nativeHandle)
            if (!ok) {
                setVMState(VMState.ERROR)
                return false
            }
            return start()
        } else {
            val configured = configure()
            if (!configured) return false
            return start()
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
            1 -> VMState.CONFIGURED
            2 -> VMState.STARTING
            3 -> VMState.RUNNING
            4 -> VMState.PAUSED
            5 -> VMState.STOPPING
            6 -> VMState.STOPPED
            8 -> VMState.NOT_VERIFIED
            9 -> VMState.BOOTING
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

        // Inform the user on the UART that a custom ARM64 kernel is required
        emit32(0xD2800000L or (0x0900L shl 5) or 1L)
        val notice = "NOT IMPLEMENTED: No ARM64 Linux kernel image configured.\n" +
                     "Please import or specify an authentic ARM64 Linux Kernel Image to boot.\n"
        for (char in notice) {
            emit32(0xD2800000L or ((char.code.toLong() and 0xFFFFL) shl 5) or 3L)
            emit32(0x39000000L or (1L shl 5) or 3L)
        }
        emit32(0xD4400000L) // HLT #0
        return ops.toByteArray()
    }
}
