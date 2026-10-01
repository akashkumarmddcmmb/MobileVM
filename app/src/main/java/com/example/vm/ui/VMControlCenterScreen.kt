package com.example.vm.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMControlCenterScreen(
    config: VMConfig,
    viewModel: VMViewModel,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeVM by viewModel.activeVM.collectAsStateWithLifecycle()
    val isThisVmActive = activeVM != null && activeVM?.config?.id == config.id
    val vmState = if (isThisVmActive) (activeVM?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED) else VMState.STOPPED

    var activeTab by remember { mutableIntStateOf(0) } // 0: Overview/Control, 1: General, 2: CPU, 3: Memory, 4: Storage, 5: Boot, 6: Network, 7: Serial, 8: Display, 9: Input, 10: USB, 11: Advanced, 12: Logs
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Mutable settings fields for configuration editing
    var nameInput by remember(config) { mutableStateOf(config.name) }
    var backendPrefInput by remember(config) { mutableStateOf(config.cpuBackendPreference) }
    var coresInput by remember(config) { mutableIntStateOf(config.cpuCores) }
    var ramInput by remember(config) { mutableIntStateOf(config.ramSizeMb) }
    var storageGbInput by remember(config) { mutableIntStateOf(config.diskSizeGb) }
    var bootOrderInput by remember(config) { mutableStateOf(config.bootOrder) }
    var networkModeInput by remember(config) { mutableStateOf(config.networkMode) }
    var serialEnabledInput by remember(config) { mutableStateOf(config.serialConsoleEnabled) }
    var baudRateInput by remember(config) { mutableIntStateOf(config.baudRate) }
    var displayModeInput by remember(config) { mutableStateOf(config.displayMode) }
    var resolutionInput by remember(config) { mutableStateOf(config.displayResolution) }
    var debugLoggingInput by remember(config) { mutableStateOf(config.debugLogging) }
    var cpuTracingInput by remember(config) { mutableStateOf(config.cpuTracing) }

    val isRunning = vmState == VMState.RUNNING

    if (showDeleteDialog) {
        DeleteVmConfirmationDialog(
            vmName = config.name,
            diskPath = config.diskImagePath,
            onDismiss = { showDeleteDialog = false },
            onDeleteVmOnly = {
                viewModel.deleteConfiguration(config)
                showDeleteDialog = false
                onBack()
            },
            onDeleteVmAndDisk = {
                viewModel.deleteConfiguration(config)
                showDeleteDialog = false
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = config.name.uppercase(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${config.guestOsType} • ${config.osVersion} • ${vmState.name}",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("control_center_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.testTag("btn_delete_vm_top")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete VM", tint = Color(0xFFFF5252))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F141C))
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Runtime Status Bar
            VmRuntimeHeaderBar(
                config = config,
                vmState = vmState,
                engine = if (isThisVmActive) activeVM else null
            )

            // Primary Control Action Toolbar
            VmControlActionToolbar(
                vmState = vmState,
                isThisVmActive = isThisVmActive,
                onStart = { viewModel.startVM(config) },
                onPause = { viewModel.pauseVM() },
                onResume = { viewModel.resumeVM() },
                onStop = { viewModel.stopVM() },
                onForceStop = {
                    activeVM?.stop()
                    Toast.makeText(context, "VM Forced Stopped", Toast.LENGTH_SHORT).show()
                },
                onRestart = {
                    viewModel.stopVM()
                    viewModel.startVM(config)
                },
                onOpenTerminal = onOpenTerminal,
                onSelectTab = { tabIndex -> activeTab = tabIndex }
            )

            // Settings Navigation Tabs
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = Color(0xFF0F141C),
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth().testTag("control_center_tab_row")
            ) {
                listOf(
                    "Overview", "General", "CPU", "Memory", "Storage", "Boot",
                    "Network", "Serial", "Display", "Input", "USB", "Advanced", "Logs"
                ).forEachIndexed { index, label ->
                    Tab(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        text = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("tab_setting_$label")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Content Body
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (activeTab) {
                    0 -> OverviewTab(config = config, engine = if (isThisVmActive) activeVM else null, vmState = vmState)
                    1 -> GeneralSettingsTab(
                        config = config,
                        vmState = vmState,
                        nameInput = nameInput,
                        onNameChange = { nameInput = it },
                        onSaveName = {
                            viewModel.saveFullConfig(config.copy(name = nameInput))
                            Toast.makeText(context, "VM Name Updated!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    2 -> CpuSettingsTab(
                        viewModel = viewModel,
                        vmState = vmState,
                        backendPref = backendPrefInput,
                        onBackendPrefChange = { backendPrefInput = it },
                        cpuCores = coresInput,
                        onCpuCoresChange = { coresInput = it },
                        onApply = {
                            if (backendPrefInput == "KVM" && !viewModel.isKvmSupported) {
                                Toast.makeText(context, "Error: KVM unavailable on host hardware/kernel!", Toast.LENGTH_LONG).show()
                            } else {
                                viewModel.saveFullConfig(
                                    config.copy(
                                        cpuBackendPreference = backendPrefInput,
                                        cpuCores = coresInput,
                                        useHardwareVirtualization = (backendPrefInput != "ARM64_SOFTWARE_EMULATOR")
                                    )
                                )
                                Toast.makeText(context, "CPU Settings Applied!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    3 -> MemorySettingsTab(
                        viewModel = viewModel,
                        vmState = vmState,
                        ramMb = ramInput,
                        onRamMbChange = { ramInput = it },
                        onApply = {
                            val availableRam = viewModel.memoryManager.getHostMemoryStats().availableMb
                            if (ramInput > availableRam - 256 && availableRam > 0) {
                                Toast.makeText(
                                    context,
                                    "Unsafe Allocation: $ramInput MB requested, but host only has $availableRam MB free!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                viewModel.saveFullConfig(config.copy(ramSizeMb = ramInput))
                                Toast.makeText(context, "RAM Settings Applied!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    4 -> StorageSettingsTab(
                        config = config,
                        vmState = vmState,
                        diskGb = storageGbInput,
                        onDiskGbChange = { storageGbInput = it },
                        onResize = {
                            viewModel.saveFullConfig(config.copy(diskSizeGb = storageGbInput))
                            viewModel.createDiskFileExplicitly(config.copy(diskSizeGb = storageGbInput))
                            Toast.makeText(context, "Disk Size Updated!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    5 -> BootSettingsTab(
                        config = config,
                        viewModel = viewModel,
                        vmState = vmState,
                        bootOrder = bootOrderInput,
                        onBootOrderChange = {
                            bootOrderInput = it
                            viewModel.saveFullConfig(config.copy(bootOrder = it))
                            Toast.makeText(context, "Boot Order Saved!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    6 -> NetworkSettingsTab(
                        config = config,
                        engine = if (isThisVmActive) activeVM else null,
                        networkMode = networkModeInput,
                        onNetworkModeChange = {
                            networkModeInput = it
                            viewModel.saveFullConfig(config.copy(networkMode = it, networkEnabled = (it != "OFF")))
                            Toast.makeText(context, "Network Mode Saved!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    7 -> SerialConsoleSettingsTab(
                        serialEnabled = serialEnabledInput,
                        onSerialEnabledChange = {
                            serialEnabledInput = it
                            viewModel.saveFullConfig(config.copy(serialConsoleEnabled = it, baudRate = baudRateInput))
                        },
                        baudRate = baudRateInput,
                        onBaudRateChange = {
                            baudRateInput = it
                            viewModel.saveFullConfig(config.copy(serialConsoleEnabled = serialEnabledInput, baudRate = it))
                        }
                    )
                    8 -> DisplaySettingsTab(
                        displayMode = displayModeInput,
                        onDisplayModeChange = {
                            displayModeInput = it
                            viewModel.saveFullConfig(config.copy(displayMode = it, displayResolution = resolutionInput))
                        },
                        resolution = resolutionInput,
                        onResolutionChange = {
                            resolutionInput = it
                            viewModel.saveFullConfig(config.copy(displayMode = displayModeInput, displayResolution = it))
                        }
                    )
                    9 -> InputSettingsTab(
                        config = config,
                        onSave = { kMode, mMode ->
                            viewModel.saveFullConfig(config.copy(keyboardMode = kMode, mouseMode = mMode))
                            Toast.makeText(context, "Input Preferences Saved!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    10 -> UsbSettingsTab(viewModel = viewModel, vmId = config.id)
                    11 -> AdvancedSettingsTab(
                        debugLogging = debugLoggingInput,
                        onDebugLoggingChange = {
                            debugLoggingInput = it
                            viewModel.saveFullConfig(config.copy(debugLogging = it, cpuTracing = cpuTracingInput))
                        },
                        cpuTracing = cpuTracingInput,
                        onCpuTracingChange = {
                            cpuTracingInput = it
                            viewModel.saveFullConfig(config.copy(debugLogging = debugLoggingInput, cpuTracing = it))
                        }
                    )
                    12 -> VmLogsTab(engine = if (isThisVmActive) activeVM else null)
                }
            }
        }
    }
}

@Composable
fun VmRuntimeHeaderBar(config: VMConfig, vmState: VMState, engine: VMEngine?) {
    val stateColor = when (vmState) {
        VMState.RUNNING -> Color(0xFF00E676)
        VMState.PAUSED -> Color(0xFFFFB74D)
        VMState.STARTING, VMState.STOPPING -> Color(0xFF81D4FA)
        VMState.ERROR, VMState.NOT_VERIFIED -> Color(0xFFFF5252)
        else -> Color.Gray
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth().testTag("runtime_header_bar")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(stateColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "STATE: ${vmState.name}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = stateColor
                )
            }

            Text(
                text = "BACKEND: ${engine?.actualBackendName ?: config.cpuBackendPreference}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.LightGray
            )
        }
    }
}

@Composable
fun VmControlActionToolbar(
    vmState: VMState,
    isThisVmActive: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSelectTab: (Int) -> Unit
) {
    val isRunning = vmState == VMState.RUNNING
    val isPaused = vmState == VMState.PAUSED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0F17))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!isRunning && !isPaused) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp).testTag("btn_vm_start")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start", tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("START", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else if (isPaused) {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp).testTag("btn_vm_resume")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("RESUME", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onPause,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp).testTag("btn_vm_pause")
            ) {
                Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("PAUSE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (isRunning || isPaused) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp).testTag("btn_vm_stop")
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("STOP", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onForceStop,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp).testTag("btn_vm_force_stop")
            ) {
                Text("FORCE", color = Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onRestart,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp).testTag("btn_vm_restart")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = Color.LightGray, modifier = Modifier.size(14.dp))
            }
        }

        Button(
            onClick = onOpenTerminal,
            enabled = isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp).testTag("btn_vm_terminal")
        ) {
            Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = Color.Black, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("TERMINAL", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun OverviewTab(config: VMConfig, engine: VMEngine?, vmState: VMState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF232D38)),
            modifier = Modifier.fillMaxWidth().testTag("overview_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VM SPECIFICATIONS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Guest OS", fontSize = 12.sp, color = Color.Gray)
                    Text("${config.guestOsType} (${config.osVersion})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Architecture", fontSize = 12.sp, color = Color.Gray)
                    Text(config.getGuestArchName(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("vCPU Cores", fontSize = 12.sp, color = Color.Gray)
                    Text("${config.cpuCores} vCPU", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Allocated RAM", fontSize = 12.sp, color = Color.Gray)
                    Text("${config.ramSizeMb} MB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Virtual Disk Size", fontSize = 12.sp, color = Color.Gray)
                    Text("${config.diskSizeGb} GB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CPU Backend Preference", fontSize = 12.sp, color = Color.Gray)
                    Text(config.cpuBackendPreference, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Boot Sequence", fontSize = 12.sp, color = Color.Gray)
                    Text(config.bootOrder, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun GeneralSettingsTab(
    config: VMConfig,
    vmState: VMState,
    nameInput: String,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("General VM Identity & Metadata", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(
            value = nameInput,
            onValueChange = onNameChange,
            label = { Text("VM Display Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_edit_vm_name")
        )

        Button(
            onClick = onSaveName,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().testTag("btn_save_vm_name")
        ) {
            Text("Save VM Name", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        val createdStr = remember(config.createdAt) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(config.createdAt))
        }

        Text("Created: $createdStr", fontSize = 11.sp, color = Color.Gray)
        Text("Guest Architecture: ${config.getGuestArchName()} (Locked)", fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
fun CpuSettingsTab(
    viewModel: VMViewModel,
    vmState: VMState,
    backendPref: String,
    onBackendPrefChange: (String) -> Unit,
    cpuCores: Int,
    onCpuCoresChange: (Int) -> Unit,
    onApply: () -> Unit
) {
    val isRunning = vmState == VMState.RUNNING

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("vCPU & Hypervisor Backend Configuration", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Surface(
            color = if (viewModel.isKvmSupported) Color(0x2200E676) else Color(0x22FF5252),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Host KVM Virtualization (/dev/kvm):", fontSize = 11.sp, color = Color.LightGray)
                Text(
                    text = if (viewModel.isKvmSupported) "SUPPORTED" else "UNAVAILABLE (${viewModel.kvmReason})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (viewModel.isKvmSupported) Color(0xFF00E676) else Color(0xFFFF5252)
                )
            }
        }

        if (isRunning) {
            Surface(color = Color(0x33FFB74D), shape = RoundedCornerShape(6.dp)) {
                Text(
                    text = "VM is RUNNING. Stop VM to apply CPU backend or core changes.",
                    fontSize = 11.sp, color = Color(0xFFFFB74D), modifier = Modifier.padding(8.dp)
                )
            }
        }

        Text("CPU Backend Selection", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("AUTO", "KVM", "ARM64_SOFTWARE_EMULATOR").forEach { mode ->
                FilterChip(
                    selected = backendPref == mode,
                    onClick = { onBackendPrefChange(mode) },
                    enabled = !isRunning,
                    label = { Text(if (mode == "ARM64_SOFTWARE_EMULATOR") "EMULATOR" else mode, fontSize = 11.sp) }
                )
            }
        }

        Text("vCPU Cores", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4).forEach { cores ->
                FilterChip(
                    selected = cpuCores == cores,
                    onClick = { onCpuCoresChange(cores) },
                    enabled = !isRunning,
                    label = { Text("$cores Cores") }
                )
            }
        }

        Button(
            onClick = onApply,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().testTag("btn_apply_cpu_settings")
        ) {
            Text("Apply CPU Settings", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MemorySettingsTab(
    viewModel: VMViewModel,
    vmState: VMState,
    ramMb: Int,
    onRamMbChange: (Int) -> Unit,
    onApply: () -> Unit
) {
    val isRunning = vmState == VMState.RUNNING
    val diagnostics by viewModel.backendDiagnostics.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Guest Memory (RAM) Allocation", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Android Available Memory", fontSize = 12.sp, color = Color.Gray)
            Text("${diagnostics.availableRamMb} MB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
        }

        Text("Configured RAM", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(512, 1024, 1536, 2048, 3072, 4096).forEach { mb ->
                FilterChip(
                    selected = ramMb == mb,
                    onClick = { onRamMbChange(mb) },
                    enabled = !isRunning,
                    label = { Text(if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else if (mb >= 1024) "${mb / 1024.0} GB" else "$mb MB", fontSize = 10.sp) }
                )
            }
        }

        Button(
            onClick = onApply,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().testTag("btn_apply_ram_settings")
        ) {
            Text("Apply RAM Settings", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StorageSettingsTab(
    config: VMConfig,
    vmState: VMState,
    diskGb: Int,
    onDiskGbChange: (Int) -> Unit,
    onResize: () -> Unit
) {
    val isRunning = vmState == VMState.RUNNING
    val diskFile = File(config.diskImagePath)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Virtual Storage & Persistent Disk", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Boot Disk File", fontSize = 11.sp, color = Color.Gray)
                Text(config.diskImagePath, fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                Text("File Exists: ${diskFile.exists()} • Size: ${diskFile.length() / (1024 * 1024)} MB", fontSize = 11.sp, color = Color.LightGray)
            }
        }

        Text("Allocated Storage Capacity", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(8, 16, 32, 64, 128).forEach { gb ->
                FilterChip(
                    selected = diskGb == gb,
                    onClick = { onDiskGbChange(gb) },
                    enabled = !isRunning,
                    label = { Text("$gb GB") }
                )
            }
        }

        Button(
            onClick = onResize,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().testTag("btn_resize_disk")
        ) {
            Text("Re-initialize Disk File Structure", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BootSettingsTab(
    config: VMConfig,
    viewModel: VMViewModel,
    vmState: VMState,
    bootOrder: String,
    onBootOrderChange: (String) -> Unit
) {
    val isRunning = vmState == VMState.RUNNING
    val kernelFile = remember(config.kernelImagePath) { if (config.kernelImagePath.isNotBlank()) File(config.kernelImagePath) else null }
    val initrdFile = remember(config.initramfsPath) { if (config.initramfsPath.isNotBlank()) File(config.initramfsPath) else null }
    val diskFile = remember(config.diskImagePath) { if (config.diskImagePath.isNotBlank()) File(config.diskImagePath) else null }

    val kernelExists = kernelFile?.exists() == true
    val initrdExists = initrdFile?.exists() == true
    val diskExists = diskFile?.exists() == true

    val kernelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.importKernelForConfig(config, uri)
        }
    }
    val initrdPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.importInitramfsForConfig(config, uri)
        }
    }
    val diskPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.importDiskForConfig(config, uri)
        }
    }

    val kernelInfo = remember(config.kernelImagePath) { com.example.vm.guest.kernel.GuestKernelManager.inspectKernel(config.kernelImagePath) }
    val initrdInfo = remember(config.initramfsPath) { com.example.vm.guest.initramfs.GuestInitramfsManager.inspectInitramfs(config.initramfsPath) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Boot Devices & Linux Provisioning", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Text("Primary Boot Order", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = bootOrder == "VIRTUAL_DISK",
                onClick = { onBootOrderChange("VIRTUAL_DISK") },
                enabled = !isRunning,
                label = { Text("1st: Virtual Disk") }
            )
            FilterChip(
                selected = bootOrder == "CD_ROM",
                onClick = { onBootOrderChange("CD_ROM") },
                enabled = !isRunning,
                label = { Text("1st: Virtual CD-ROM (ISO)") }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text("Linux Guest Artifacts Status", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

        // Kernel Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (kernelInfo.isArm64Valid) Color(0xFF00E676) else Color(0x66FF5252)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Kernel Asset (ARM64)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (kernelInfo.isArm64Valid) "VALIDATED ARM64" else "INVALID / UNVERIFIED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (kernelInfo.isArm64Valid) Color(0xFF00E676) else Color(0xFFFF5252)
                    )
                }
                Text("Path: ${config.kernelImagePath.ifBlank { "(No kernel path configured)" }}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                Text("Architecture: ${kernelInfo.architecture} • Format: ${kernelInfo.formatDescription}", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                Text("Size: ${kernelFile?.length() ?: 0} Bytes (${(kernelFile?.length() ?: 0) / 1024} KB) • SHA-256: ${computeSha256Snippet(kernelFile)}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)

                OutlinedButton(
                    onClick = { kernelPicker.launch("*/*") },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth().height(32.dp).testTag("btn_import_custom_kernel")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Import Kernel", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import ARM64 Linux Kernel Image", fontSize = 10.sp)
                }
            }
        }

        // Initramfs Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (initrdInfo.hasUsableInit) Color(0xFF00E676) else if (initrdExists) Color(0xFF81D4FA) else Color(0x66FFB74D)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Initramfs / Ramdisk Asset", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (initrdInfo.hasUsableInit) "VALIDATED (has /init)" else if (initrdExists) "PRESENT" else "NOT CONFIGURED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (initrdInfo.hasUsableInit) Color(0xFF00E676) else if (initrdExists) Color(0xFF81D4FA) else Color(0xFFFFB74D)
                    )
                }
                Text("Path: ${config.initramfsPath.ifBlank { "(No initramfs configured)" }}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                Text("Size: ${initrdFile?.length() ?: 0} Bytes (${(initrdFile?.length() ?: 0) / 1024} KB) • Format: CPIO Gzip Archive", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)

                OutlinedButton(
                    onClick = { initrdPicker.launch("*/*") },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth().height(32.dp).testTag("btn_import_custom_initrd")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Import Initrd", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import Initramfs (CPIO / Gzip)", fontSize = 10.sp)
                }
            }
        }

        // Disk Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (diskExists && (diskFile?.length() ?: 0) >= 512) Color(0xFF00E676) else Color(0x66FFB74D)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Virtual Disk / Rootfs Asset (vda)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (diskExists && (diskFile?.length() ?: 0) >= 512) "VALIDATED DISK" else "NOT CREATED / INVALID",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (diskExists && (diskFile?.length() ?: 0) >= 512) Color(0xFF00E676) else Color(0xFFFFB74D)
                    )
                }
                Text("Path: ${config.diskImagePath.ifBlank { "(No disk path configured)" }}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                Text("Format: RAW / VirtIO Block • Allocated Size: ${config.diskSizeGb} GB (${(diskFile?.length() ?: 0) / (1024 * 1024)} MB on host)", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)

                OutlinedButton(
                    onClick = { diskPicker.launch("*/*") },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth().height(32.dp).testTag("btn_import_custom_disk")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Import Disk", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import Disk Image (*.img, *.raw, *.iso)", fontSize = 10.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                viewModel.provisionDefaultLinuxForConfig(config)
            },
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().testTag("btn_provision_linux_default")
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = "Provision", tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("1-Click Provision Default Linux ARM64 Files", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NetworkSettingsTab(
    config: VMConfig,
    engine: VMEngine?,
    networkMode: String,
    onNetworkModeChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Virtual Network Configuration", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("NAT", "OFF", "USER_MODE").forEach { mode ->
                FilterChip(
                    selected = networkMode == mode,
                    onClick = { onNetworkModeChange(mode) },
                    label = { Text(mode) }
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Network Status", fontSize = 11.sp, color = Color.Gray)
                Text("Mode: $networkMode", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (networkMode == "OFF") {
                    Text("State: Disabled (Air-Gapped / No NIC)", fontSize = 11.sp, color = Color.Gray)
                } else {
                    val isEngineRunning = engine?.state?.value == VMState.RUNNING
                    if (isEngineRunning) {
                        Text("State: Attached to VM (User-mode SLIRP / NAT)", fontSize = 11.sp, color = Color(0xFF00E676))
                        Text("Guest IP: 10.0.2.15 (VirtIO-Net)", fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                        Text("Gateway: 10.0.2.2 • DNS: ${config.dnsServer}", fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    } else {
                        Text("State: Configured ($networkMode) — VM Inactive", fontSize = 11.sp, color = Color.LightGray)
                        Text("Gateway: 10.0.2.2 • Configured DNS: ${config.dnsServer}", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun SerialConsoleSettingsTab(
    serialEnabled: Boolean, onSerialEnabledChange: (Boolean) -> Unit,
    baudRate: Int, onBaudRateChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Serial Console (PL011 UART)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Enable PL011 UART Console", fontSize = 12.sp, color = Color.White)
            Switch(checked = serialEnabled, onCheckedChange = onSerialEnabledChange)
        }

        Text("Baud Rate: $baudRate 8N1", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun DisplaySettingsTab(
    displayMode: String, onDisplayModeChange: (String) -> Unit,
    resolution: String, onResolutionChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Display Output Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = displayMode == "Serial / Terminal", onClick = { onDisplayModeChange("Serial / Terminal") }, label = { Text("Serial / Terminal") })
            FilterChip(selected = displayMode == "Virtual Display", onClick = { onDisplayModeChange("Virtual Display") }, label = { Text("Virtual Display (GPU)") })
        }
    }
}

@Composable
fun InputSettingsTab(
    config: VMConfig,
    onSave: (String, String) -> Unit
) {
    var kMode by remember(config) { mutableStateOf(config.keyboardMode) }
    var mMode by remember(config) { mutableStateOf(config.mouseMode) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Keyboard & Pointer Input Configuration", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Text("Keyboard Input Mode", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = kMode == "Android Keyboard",
                onClick = { kMode = "Android Keyboard"; onSave(kMode, mMode) },
                label = { Text("Android Soft Keyboard") }
            )
            FilterChip(
                selected = kMode == "Hardware OTG",
                onClick = { kMode = "Hardware OTG"; onSave(kMode, mMode) },
                label = { Text("OTG USB Keyboard") }
            )
        }

        Text("Pointer / Mouse Input Mode", fontSize = 11.sp, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mMode == "Touch Mouse",
                onClick = { mMode = "Touch Mouse"; onSave(kMode, mMode) },
                label = { Text("Touch-to-Pointer Direct") }
            )
            FilterChip(
                selected = mMode == "Hardware USB Mouse",
                onClick = { mMode = "Hardware USB Mouse"; onSave(kMode, mMode) },
                label = { Text("OTG USB Hardware Mouse") }
            )
        }
    }
}

@Composable
fun UsbSettingsTab(viewModel: VMViewModel, vmId: Long) {
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("USB Device Manager & Passthrough", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        if (usbDevices.isEmpty()) {
            Text("No OTG USB devices attached to Android host.", fontSize = 12.sp, color = Color.Gray)
        } else {
            usbDevices.forEach { dev ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23))) {
                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(dev.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("VID: ${dev.vendorHex} PID: ${dev.productHex}", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                        }

                        Button(onClick = { viewModel.toggleRouteUsbDevice(dev, vmId) }) {
                            Text(if (dev.isRoutedToVM) "Detach" else "Attach", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedSettingsTab(
    debugLogging: Boolean, onDebugLoggingChange: (Boolean) -> Unit,
    cpuTracing: Boolean, onCpuTracingChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Advanced Engine & Diagnostic Tracing", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Enable Debug Logging", fontSize = 12.sp, color = Color.White)
            Switch(checked = debugLogging, onCheckedChange = onDebugLoggingChange)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("CPU Instruction Tracing", fontSize = 12.sp, color = Color.White)
                Text("Warning: CPU tracing impacts execution speed.", fontSize = 10.sp, color = Color(0xFFFFB74D))
            }
            Switch(checked = cpuTracing, onCheckedChange = onCpuTracingChange)
        }
    }
}

@Composable
fun VmLogsTab(engine: VMEngine?) {
    val history by engine?.serialConsole?.history?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList()) }
    val logsText = remember(history) { history.joinToString("\n") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Structured Engine & Kernel Console Logs", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color(0xFF040608), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text(
                text = if (logsText.isEmpty()) "No engine logs available for this session." else logsText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF69F0AE)
            )
        }
    }
}

@Composable
fun DeleteVmConfirmationDialog(
    vmName: String,
    diskPath: String,
    onDismiss: () -> Unit,
    onDeleteVmOnly: () -> Unit,
    onDeleteVmAndDisk: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete VM '$vmName'?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Do you want to delete only the VM configuration or also purge the virtual disk file?")
                Text("Disk Path: $diskPath", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            Button(
                onClick = onDeleteVmAndDisk,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
            ) {
                Text("DELETE VM + DISK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onDeleteVmOnly) {
                    Text("DELETE VM ONLY", fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("CANCEL", fontSize = 11.sp)
                }
            }
        }
    )
}

fun computeSha256Snippet(file: File?): String {
    if (file == null || !file.exists()) return "N/A"
    return try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(16384)
            var bytesRead: Int
            while (stream.read(buf).also { bytesRead = it } != -1) {
                digest.update(buf, 0, bytesRead)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        hash.take(16) + "..."
    } catch (_: Exception) {
        "Unknown"
    }
}
