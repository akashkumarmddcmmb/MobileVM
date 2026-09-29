package com.example.vm.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vm.core.VMConfig
import com.example.vm.core.VMError
import com.example.vm.core.VMErrorCategory
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.nativebridge.NativeVMBinding
import com.example.vm.persistence.VMDatabase
import com.example.vm.persistence.VMRepository
import com.example.vm.storage.AndroidStorageDiskBackend
import com.example.vm.usb.UsbDeviceInfo
import com.example.vm.usb.UsbDeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class VMViewModel(application: Application) : AndroidViewModel(application) {

    val repository: VMRepository
    val vmConfigurations: StateFlow<List<VMConfig>>

    val diskBackend = AndroidStorageDiskBackend(application)
    val memoryManager = com.example.vm.memory.MemoryManager(application)
    val downloadManager = com.example.vm.guest.os.OSDownloadManager(application)

    val hostArchitecture: HostArchitecture = HostArchitecture.detect()
    val isKvmSupported: Boolean = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeIsKvmSupported() else false
    val kvmReason: String = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeGetKvmReason() else "Native bridge inactive"

    private val _activeVM = MutableStateFlow<VMEngine?>(null)
    val activeVM: StateFlow<VMEngine?> = _activeVM.asStateFlow()

    private val _selectedConfig = MutableStateFlow<VMConfig?>(null)
    val selectedConfig: StateFlow<VMConfig?> = _selectedConfig.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _activeError = MutableStateFlow<VMError?>(null)
    val activeError: StateFlow<VMError?> = _activeError.asStateFlow()

    // USB manager for OTG components
    val usbDeviceManager = UsbDeviceManager(application)
    val usbDevices: StateFlow<List<UsbDeviceInfo>> = usbDeviceManager.devices
    val usbStorageDevices: StateFlow<List<com.example.vm.usb.UsbStorageDeviceInfo>> = usbDeviceManager.storageManager.storageDevices
    val routedDevices = usbDeviceManager.deviceRouter.routedDevices
    val usbIdentifications: StateFlow<Map<String, com.example.vm.usb.UsbDeviceIdentification>> = usbDeviceManager.identifications
    val usbErrors: StateFlow<Map<String, com.example.vm.usb.UsbDeviceError>> = usbDeviceManager.errors

    val sharedFolderManager = com.example.vm.sharing.SharedFolderManager(application)
    val clipboardManager = com.example.vm.sharing.VMClipboardManager(application)

    lateinit var storageManager: com.example.vm.storage.VMStorageManager
    lateinit var allDisks: StateFlow<List<com.example.vm.storage.VmDisk>>
    lateinit var allSnapshots: StateFlow<List<com.example.vm.storage.VmSnapshot>>
    lateinit var allBackups: StateFlow<List<com.example.vm.storage.VmBackup>>
    lateinit var storageLogs: StateFlow<List<com.example.vm.storage.StorageOperationLog>>

    init {
        val database = VMDatabase.getDatabase(application)
        repository = VMRepository(
            database.vmConfigDao(),
            database.vmDiskDao(),
            database.vmSnapshotDao(),
            database.vmBackupDao(),
            database.storageOperationLogDao()
        )
        storageManager = com.example.vm.storage.VMStorageManager(application, repository, diskBackend)

        vmConfigurations = repository.allConfigs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        allDisks = repository.allDisks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        allSnapshots = repository.allSnapshots.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        allBackups = repository.allBackups.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        storageLogs = repository.recentStorageLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        usbDeviceManager.onDeviceAttached = { refreshUsbDevices() }
        usbDeviceManager.onDeviceDetached = { refreshUsbDevices() }

        // Keyboard Handler routing
        usbDeviceManager.keyboardHandler.onKeyboardInputEvent = { targetVmId, keyEvent ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.inputBackend.postKeyboardEvent(keyEvent)
            }
        }
        usbDeviceManager.keyboardHandler.onKeyboardTerminalBytes = { targetVmId, bytes ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.serialConsole.sendRawBytes(bytes)
            }
        }

        // Mouse Handler routing
        usbDeviceManager.mouseHandler.onMouseInputEvent = { targetVmId, mouseEvent ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.inputBackend.postMouseEvent(mouseEvent)
            }
        }

        // Serial Handler routing (USB to UART -> VM serial console)
        usbDeviceManager.serialHandler.onSerialDataReceived = { targetVmId, data ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.serialConsole.sendRawBytes(data)
            }
        }

        // Legacy router callbacks for backward compatibility
        usbDeviceManager.deviceRouter.onKeyboardInputEvent = { targetVmId, keyEvent ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.inputBackend.postKeyboardEvent(keyEvent)
            }
        }
        usbDeviceManager.deviceRouter.onKeyboardTerminalBytes = { targetVmId, bytes ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.serialConsole.sendRawBytes(bytes)
            }
        }
        usbDeviceManager.deviceRouter.onMouseInputEvent = { targetVmId, mouseEvent ->
            val vm = _activeVM.value
            if (vm != null && (vm.config.id == targetVmId || targetVmId == 0L)) {
                vm.inputBackend.postMouseEvent(mouseEvent)
            }
        }

        refreshUsbDevices()
    }

    fun selectConfig(config: VMConfig) {
        _selectedConfig.value = config
    }

    fun clearSelectedConfig() {
        _selectedConfig.value = null
    }

    fun refreshUsbDevices() {
        usbDeviceManager.enumerateDevices()
    }

    fun requestUsbPermission(deviceInfo: UsbDeviceInfo) {
        usbDeviceManager.requestPermission(deviceInfo) { granted ->
            if (!granted) {
                val err = com.example.vm.core.VMError.usbPermissionDenied(
                    deviceName = deviceInfo.displayName,
                    vid = deviceInfo.vendorHex,
                    pid = deviceInfo.productHex
                )
                reportError(err)
            }
        }
    }

    fun requestStoragePermission(storageInfo: com.example.vm.usb.UsbStorageDeviceInfo) {
        usbDeviceManager.storageManager.requestStoragePermission(storageInfo) { granted ->
            if (!granted) {
                val err = com.example.vm.core.VMError.usbPermissionDenied(
                    deviceName = storageInfo.displayName,
                    vid = storageInfo.vendorHex,
                    pid = storageInfo.productHex
                )
                reportError(err)
            }
        }
    }

    fun configureStorageAccessMode(
        storageInfo: com.example.vm.usb.UsbStorageDeviceInfo,
        mode: com.example.vm.usb.UsbStorageAccessMode,
        targetVmId: Long
    ) {
        val result = usbDeviceManager.storageManager.configureAccessMode(storageInfo, mode, targetVmId)
        result.onFailure { err ->
            val vmErr = com.example.vm.core.VMError(
                category = com.example.vm.core.VMErrorCategory.USB_UNSUPPORTED,
                summary = "Failed to configure storage mode: ${err.message}",
                technicalDetails = "Storage device: ${storageInfo.displayName} (${storageInfo.vendorHex}:${storageInfo.productHex}), Target VM: $targetVmId",
                suggestedRemedy = "Check Android OEM kernel USB host policies or switch to Host-Mediated Storage Mode."
            )
            reportError(vmErr)
        }
    }

    fun unmountStorageDevice(storageInfo: com.example.vm.usb.UsbStorageDeviceInfo) {
        usbDeviceManager.storageManager.unmountOrRelease(storageInfo)
    }

    fun toggleRouteUsbDevice(deviceInfo: UsbDeviceInfo, targetVmId: Long) {
        if (deviceInfo.isRoutedToVM) {
            usbDeviceManager.releaseDeviceFromVM(deviceInfo)
        } else {
            val result = usbDeviceManager.routeDeviceToVM(deviceInfo, targetVmId)
            result.onFailure { err ->
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.USB_UNSUPPORTED,
                    summary = "Failed to route USB device: ${err.message}",
                    technicalDetails = "Device: ${deviceInfo.displayName} (Class: 0x${deviceInfo.deviceClass.toString(16)})",
                    suggestedRemedy = "Ensure device has permission granted and is not claimed by another host service."
                )
                reportError(vmErr)
            }
        }
    }

    fun saveFullConfig(config: VMConfig) {
        viewModelScope.launch {
            // Security verification: Guest asset paths must not access arbitrary host files
            if (config.kernelImagePath.isNotBlank() && !diskBackend.isGuestImagePathAuthorized(config.kernelImagePath)) {
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.KERNEL_MISSING,
                    summary = "Security: Unauthorized Kernel Path",
                    technicalDetails = "Path traversal or arbitrary host file access blocked: ${config.kernelImagePath}",
                    suggestedRemedy = "Place kernel image inside app private storage (${diskBackend.getAuthorizedAssetsDirectory().absolutePath}) or leave blank for built-in Linux 6.6.0."
                )
                reportError(vmErr)
                return@launch
            }

            if (config.initramfsPath.isNotBlank() && !diskBackend.isGuestImagePathAuthorized(config.initramfsPath)) {
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.INITRAMFS_MISSING,
                    summary = "Security: Unauthorized Initramfs Path",
                    technicalDetails = "Path traversal or arbitrary host file access blocked: ${config.initramfsPath}",
                    suggestedRemedy = "Place initramfs inside app private storage (${diskBackend.getAuthorizedAssetsDirectory().absolutePath}) or leave blank."
                )
                reportError(vmErr)
                return@launch
            }

            if (config.diskImagePath.isNotBlank() && !diskBackend.isPathAuthorized(config.diskImagePath) && !diskBackend.isGuestImagePathAuthorized(config.diskImagePath)) {
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.DISK_INVALID,
                    summary = "Security: Unauthorized Disk Path",
                    technicalDetails = "Path traversal or arbitrary host file access blocked: ${config.diskImagePath}",
                    suggestedRemedy = "Place disk image inside app private storage (${diskBackend.getAuthorizedDisksDirectory().absolutePath})."
                )
                reportError(vmErr)
                return@launch
            }

            val disksDir = diskBackend.getAuthorizedDisksDirectory()
            val finalDiskImagePath = if (config.diskImagePath.isNotBlank()) {
                config.diskImagePath
            } else if (config.id != 0L) {
                val existing = vmConfigurations.value.find { it.id == config.id }
                existing?.diskImagePath ?: File(disksDir, "${config.name.replace("\\s+".toRegex(), "_").lowercase()}_system.img").absolutePath
            } else {
                val baseDiskName = "${config.name.replace("\\s+".toRegex(), "_").lowercase()}_system"
                var targetFile = File(disksDir, "${baseDiskName}.img")
                if (targetFile.exists()) {
                    targetFile = File(disksDir, "${baseDiskName}_${System.currentTimeMillis() % 10000}.img")
                }
                targetFile.absolutePath
            }

            val finalConfig = config.copy(diskImagePath = finalDiskImagePath)

            if (finalConfig.id == 0L) {
                if (!File(finalDiskImagePath).exists()) {
                    diskBackend.createDiskImage(finalDiskImagePath, finalConfig.diskSizeGb, sparse = true)
                }
                val insertedId = repository.insertConfig(finalConfig)
                _selectedConfig.value = finalConfig.copy(id = insertedId)
            } else {
                repository.updateConfig(finalConfig)
                _selectedConfig.value = finalConfig
            }
            clearErrorMessage()
        }
    }

    fun saveConfiguration(
        id: Long = 0,
        name: String,
        guestOsType: String,
        guestArch: GuestArchitecture = GuestArchitecture.ARM64,
        cpuCores: Int,
        ramSizeMb: Int,
        diskSizeGb: Int,
        useHardwareVirtualization: Boolean,
        networkEnabled: Boolean,
        serialConsoleEnabled: Boolean,
        kernelImagePath: String = "",
        initramfsPath: String = "",
        kernelCmdline: String = "console=ttyAMA0,115200 root=/dev/vda1 rw init=/init earlycon=pl011,0x09000000",
        consoleDevice: String = "ttyAMA0 (PL011 UART)",
        diskImagePath: String = ""
    ) {
        viewModelScope.launch {
            // Security verification: Guest asset paths must not access arbitrary host files
            if (kernelImagePath.isNotBlank() && !diskBackend.isGuestImagePathAuthorized(kernelImagePath)) {
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.KERNEL_MISSING,
                    summary = "Security: Unauthorized Kernel Path",
                    technicalDetails = "Path traversal or arbitrary host file access blocked: $kernelImagePath",
                    suggestedRemedy = "Place kernel image inside app private storage (${diskBackend.getAuthorizedAssetsDirectory().absolutePath}) or leave blank for built-in Linux 6.6.0."
                )
                reportError(vmErr)
                return@launch
            }

            if (initramfsPath.isNotBlank() && !diskBackend.isGuestImagePathAuthorized(initramfsPath)) {
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.INITRAMFS_MISSING,
                    summary = "Security: Unauthorized Initramfs Path",
                    technicalDetails = "Path traversal or arbitrary host file access blocked: $initramfsPath",
                    suggestedRemedy = "Place initramfs inside app private storage (${diskBackend.getAuthorizedAssetsDirectory().absolutePath}) or leave blank."
                )
                reportError(vmErr)
                return@launch
            }

            if (diskImagePath.isNotBlank() && !diskBackend.isPathAuthorized(diskImagePath) && !diskBackend.isGuestImagePathAuthorized(diskImagePath)) {
                val vmErr = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.DISK_INVALID,
                    summary = "Security: Unauthorized Disk Path",
                    technicalDetails = "Path traversal or arbitrary host file access blocked: $diskImagePath",
                    suggestedRemedy = "Place disk image inside app private storage (${diskBackend.getAuthorizedDisksDirectory().absolutePath})."
                )
                reportError(vmErr)
                return@launch
            }

            val disksDir = diskBackend.getAuthorizedDisksDirectory()
            val finalDiskImagePath = if (diskImagePath.isNotBlank()) {
                diskImagePath
            } else if (id != 0L) {
                // Preserve existing disk image path when updating config
                val existing = vmConfigurations.value.find { it.id == id }
                existing?.diskImagePath ?: File(disksDir, "${name.replace("\\s+".toRegex(), "_").lowercase()}_system.img").absolutePath
            } else {
                val baseDiskName = "${name.replace("\\s+".toRegex(), "_").lowercase()}_system"
                var targetFile = File(disksDir, "${baseDiskName}.img")
                // Protect existing disk files from accidental overwrite
                if (targetFile.exists()) {
                    targetFile = File(disksDir, "${baseDiskName}_${System.currentTimeMillis() % 10000}.img")
                }
                targetFile.absolutePath
            }

            val config = VMConfig(
                id = id,
                name = name,
                guestOsType = guestOsType,
                guestArchCode = guestArch.code,
                cpuCores = cpuCores,
                ramSizeMb = ramSizeMb,
                diskSizeGb = diskSizeGb,
                diskImagePath = finalDiskImagePath,
                useHardwareVirtualization = useHardwareVirtualization,
                networkEnabled = networkEnabled,
                serialConsoleEnabled = serialConsoleEnabled,
                kernelImagePath = kernelImagePath,
                initramfsPath = initramfsPath,
                kernelCmdline = kernelCmdline,
                consoleDevice = consoleDevice
            )

            if (id == 0L) {
                // Only generate a blank disk if the specified image does not exist yet
                if (!File(finalDiskImagePath).exists()) {
                    diskBackend.createDiskImage(finalDiskImagePath, diskSizeGb, sparse = true)
                }
                repository.insertConfig(config)
            } else {
                repository.updateConfig(config)
            }
            clearErrorMessage()
        }
    }

    fun createDiskFileExplicitly(config: VMConfig) {
        viewModelScope.launch {
            val success = diskBackend.createDiskImage(config.diskImagePath, config.diskSizeGb, sparse = true)
            if (!success) {
                _errorMessage.value = "Failed to create physical raw disk image in sandboxed filesystem."
            } else {
                _errorMessage.value = "Raw disk image created with partition table (${config.diskSizeGb} GB)!"
            }
        }
    }

    fun inspectDiskMBR(diskPath: String): AndroidStorageDiskBackend.MBRInfo? {
        return diskBackend.inspectMBR(diskPath)
    }

    fun readSectorBytes(diskPath: String, lba: Long, count: Int): ByteArray? {
        return diskBackend.readSectors(diskPath, lba, count)
    }

    fun deleteConfiguration(config: VMConfig) {
        viewModelScope.launch {
            val active = _activeVM.value
            if (active != null && active.config.id == config.id) {
                active.stop()
                _activeVM.value = null
            }
            
            diskBackend.deleteDiskFile(config.diskImagePath)

            repository.deleteConfig(config)
            if (_selectedConfig.value?.id == config.id) {
                _selectedConfig.value = null
            }
        }
    }

    fun startVM(config: VMConfig) {
        viewModelScope.launch {
            // Check License Tier Entitlements
            val licenseManager = com.example.vm.licensing.LicenseManager.getInstance(getApplication())
            val gateResult = com.example.vm.licensing.FeatureGate.verifyVmConfig(config, licenseManager.getCurrentEntitlements())
            if (gateResult is com.example.vm.licensing.FeatureGate.GateResult.Restricted) {
                val err = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.EMULATOR_INIT_FAILED,
                    summary = "License Tier Limit: ${gateResult.reason}",
                    technicalDetails = "Current tier: ${gateResult.currentTier.displayName}. Required tier: ${gateResult.requiredTier.displayName}.",
                    suggestedRemedy = "Activate a Pro or Premium license key in 'Licenses & Protection' or adjust VM CPU/RAM settings."
                )
                reportError(err)
                return@launch
            }

            val active = _activeVM.value
            if (active != null) {
                val err = com.example.vm.core.VMError(
                    category = com.example.vm.core.VMErrorCategory.EMULATOR_INIT_FAILED,
                    summary = "A Virtual Machine (${active.config.name}) is already active.",
                    technicalDetails = "Concurrent VM execution is restricted in this phase. Active VM State: ${active.state.value.name}",
                    suggestedRemedy = "Shut down '${active.config.name}' before starting '${config.name}'."
                )
                reportError(err)
                return@launch
            }

            val newEngine = VMEngine(getApplication(), config)
            val err = newEngine.start()
            if (err != null) {
                reportError(err)
                _activeVM.value = newEngine
            } else {
                _activeVM.value = newEngine
                clearErrorMessage()
            }
        }
    }

    fun reportError(error: com.example.vm.core.VMError) {
        _activeError.value = error
        _errorMessage.value = error.summary
    }

    fun dismissError() {
        _activeError.value = null
        _errorMessage.value = null
        _activeVM.value?.clearError()
    }

    fun stopVM() {
        val active = _activeVM.value
        if (active != null) {
            val err = active.stop()
            if (err != null) {
                _errorMessage.value = err
            } else {
                clearErrorMessage()
            }
        }
    }

    fun pauseVM() {
        val active = _activeVM.value
        if (active != null) {
            active.pause()
        }
    }

    fun resumeVM() {
        val active = _activeVM.value
        if (active != null) {
            active.resume()
        }
    }

    fun resetVM() {
        val active = _activeVM.value
        if (active != null) {
            active.reset()
        }
    }

    fun executeConsoleCommand(command: String) {
        val active = _activeVM.value
        if (active != null && active.state.value == VMState.RUNNING) {
            active.serialConsole.sendInputLine(command)
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    private val _backendDiagnostics = MutableStateFlow(computeBackendDiagnostics())
    val backendDiagnostics: StateFlow<BackendDiagnostics> = _backendDiagnostics.asStateFlow()

    fun refreshBackendDiagnostics() {
        _backendDiagnostics.value = computeBackendDiagnostics()
    }

    fun computeBackendDiagnostics(): BackendDiagnostics {
        val kvmFile = File("/dev/kvm")
        val active = _activeVM.value
        val cfg = active?.config ?: _selectedConfig.value
        val nativeHandle = active?.nativeHandle ?: 0L
        val diskReads = if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
            NativeVMBinding.nativeGetDiskReadSectors(nativeHandle)
        } else 0L
        val diskWrites = if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
            NativeVMBinding.nativeGetDiskWrittenSectors(nativeHandle)
        } else 0L
        val totalSectors = if (nativeHandle != 0L && NativeVMBinding.isLoaded()) {
            NativeVMBinding.nativeGetDiskSectorCount(nativeHandle)
        } else 0L

        val kernelRelease = try {
            val f = File("/proc/version")
            if (f.exists() && f.canRead()) {
                f.readText().lines().firstOrNull() ?: (System.getProperty("os.version") ?: "Linux")
            } else {
                System.getProperty("os.version") ?: "Linux"
            }
        } catch (e: Exception) {
            System.getProperty("os.version") ?: "Linux"
        }

        val kvmSupp = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeIsKvmSupported() else false
        val kvmRes = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeGetKvmReason() else "Native bridge library not loaded"

        val memStats = memoryManager.getHostMemoryStats()
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val connectedUsbList = usbDevices.value
        val consoleConnected = active?.serialConsole?.isConnected?.value ?: false
        val vmState = active?.state?.value ?: VMState.STOPPED

        return BackendDiagnostics(
            hostArch = hostArchitecture,
            isKvmSupported = kvmSupp,
            kvmReason = kvmRes,
            kvmNodeExists = kvmFile.exists(),
            kvmNodeReadable = kvmFile.canRead(),
            kvmNodeWritable = kvmFile.canWrite(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            kernelRelease = kernelRelease,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            nativeBridgeLoaded = NativeVMBinding.isLoaded(),
            // System hardware
            hostCpuCores = cpuCount,
            availableRamMb = memStats.availableMb,
            totalRamMb = memStats.totalMb,
            // Active VM / Selected VM specs
            activeVmRunning = active != null && active.state.value == VMState.RUNNING,
            activeVmName = cfg?.name ?: "None configured",
            activeVmState = vmState,
            vmAllocatedRamMb = cfg?.ramSizeMb ?: 0,
            vmCpuCores = cfg?.cpuCores ?: 0,
            guestArchitecture = cfg?.getGuestArchName() ?: "ARM64 (aarch64)",
            selectedCpuBackend = if (active?.isActuallyHardwareAccelerated == true) {
                "ARM64 KVM Hardware Virtualization"
            } else {
                "ARM64 Software Emulation"
            },
            activeVmBackendName = active?.actualBackendName ?: "No active VM",
            activeVmIsHardwareAccelerated = active?.isActuallyHardwareAccelerated ?: false,
            activeVmIsFallbackEmulation = active?.isFallbackEmulation ?: false,
            activeVmStatusMessage = active?.backendStatusMessage ?: "Engine idle",
            nativeHandle = nativeHandle,
            // VM Storage & Images
            diskImagePath = cfg?.diskImagePath?.ifEmpty { "Default internal disk image" } ?: "Not configured",
            kernelImagePath = cfg?.kernelImagePath?.ifEmpty { "Built-in ARM64 Linux 6.6.0" } ?: "Built-in ARM64 Linux 6.6.0",
            initramfsPath = cfg?.initramfsPath?.ifEmpty { "Built-in minimal initramfs (BusyBox rootfs)" } ?: "Built-in minimal initramfs (BusyBox rootfs)",
            // Peripherals
            usbDeviceCount = connectedUsbList.size,
            usbDeviceSummaries = connectedUsbList.map { "${it.displayName} (${it.deviceClassName})" },
            consoleConnected = consoleConnected,
            consoleDeviceName = cfg?.consoleDevice ?: "ttyAMA0 (PL011 UART)",
            totalSectors = totalSectors,
            readSectors = diskReads,
            writtenSectors = diskWrites
        )
    }

    override fun onCleared() {
        super.onCleared()
        _activeVM.value?.destroy()
        usbDeviceManager.cleanup()
    }
}

data class BackendDiagnostics(
    val hostArch: HostArchitecture,
    val isKvmSupported: Boolean,
    val kvmReason: String,
    val kvmNodeExists: Boolean,
    val kvmNodeReadable: Boolean,
    val kvmNodeWritable: Boolean,
    val osVersion: String,
    val kernelRelease: String,
    val deviceModel: String,
    val nativeBridgeLoaded: Boolean,
    // System hardware
    val hostCpuCores: Int,
    val availableRamMb: Long,
    val totalRamMb: Long,
    // Active VM / Selected VM specs
    val activeVmRunning: Boolean,
    val activeVmName: String,
    val activeVmState: VMState,
    val vmAllocatedRamMb: Int,
    val vmCpuCores: Int,
    val guestArchitecture: String,
    val selectedCpuBackend: String,
    val activeVmBackendName: String,
    val activeVmIsHardwareAccelerated: Boolean,
    val activeVmIsFallbackEmulation: Boolean,
    val activeVmStatusMessage: String,
    val nativeHandle: Long,
    // Storage & images
    val diskImagePath: String,
    val kernelImagePath: String,
    val initramfsPath: String,
    // Peripherals
    val usbDeviceCount: Int,
    val usbDeviceSummaries: List<String>,
    val consoleConnected: Boolean,
    val consoleDeviceName: String,
    // Disk stats
    val totalSectors: Long,
    val readSectors: Long,
    val writtenSectors: Long,
    val timestamp: Long = System.currentTimeMillis()
)
