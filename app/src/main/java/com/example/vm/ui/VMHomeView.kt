package com.example.vm.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vm.core.VMConfig
import com.example.vm.core.VMEngine
import com.example.vm.core.VMState
import com.example.vm.core.VMError
import com.example.vm.core.VMErrorCategory
import com.example.vm.cpu.GuestArchitecture
import com.example.vm.cpu.HostArchitecture
import com.example.vm.memory.MemoryManager
import com.example.vm.storage.AndroidStorageDiskBackend
import com.example.vm.guest.kernel.GuestKernelDownloader
import com.example.vm.guest.kernel.GuestKernelManager
import com.example.vm.guest.initramfs.GuestInitramfsDownloader
import com.example.vm.guest.initramfs.GuestInitramfsManager
import com.example.vm.guest.ubuntu.UbuntuGuestManager
import com.example.vm.nativebridge.NativeVMBinding
import com.example.vm.input.TouchAction
import com.example.vm.input.InputBackend
import com.example.vm.input.VirtualInputDevice
import com.example.vm.usb.UsbDeviceInfo
import com.example.vm.usb.UsbStorageDeviceInfo
import com.example.vm.usb.UsbStorageType
import com.example.vm.usb.UsbStorageAccessMode
import com.example.vm.usb.UsbStorageConnectionState
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMHomeView(
    viewModel: VMViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<VMConfig?>(null) }
    var inspectingDiskConfig by remember { mutableStateOf<VMConfig?>(null) }
    var showLicenseScreen by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showGlobalSettingsDialog by remember { mutableStateOf(false) }

    if (showLicenseScreen) {
        LicenseAndProtectionScreen(
            onDismiss = { showLicenseScreen = false },
            modifier = modifier
        )
        return
    }

    val vmList by viewModel.vmConfigurations.collectAsStateWithLifecycle()
    val activeVM by viewModel.activeVM.collectAsStateWithLifecycle()
    val activeVMState = activeVM?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val activeError by viewModel.activeError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            if (activeError == null) {
                snackbarHostState.showSnackbar(it)
                viewModel.clearErrorMessage()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ic_mobilevm_logo),
                            contentDescription = "MobileVM Official Logo",
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "MobileVM",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Host: ${viewModel.hostArchitecture.displayName}",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.testTag("action_guest_os")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Guest OS Center",
                            tint = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    if (activeVMState == VMState.RUNNING || activeVMState == VMState.STARTING) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (activeVMState == VMState.RUNNING) Color(0x2200E676) else Color(0x2200E5FF),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (activeVMState == VMState.RUNNING) Color(0xFF00E676) else Color(0xFF00E5FF))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (activeVMState == VMState.RUNNING) "RUNNING" else "STARTING",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeVMState == VMState.RUNNING) Color(0xFF00E676) else Color(0xFF00E5FF),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { showTopMenu = true },
                            modifier = Modifier.testTag("action_more_vert")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Global Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showTopMenu = false
                                    showGlobalSettingsDialog = true
                                },
                                modifier = Modifier.testTag("menu_global_settings")
                            )
                            DropdownMenuItem(
                                text = { Text("Storage Manager") },
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showTopMenu = false
                                    selectedTab = 2
                                },
                                modifier = Modifier.testTag("menu_storage_manager")
                            )
                            DropdownMenuItem(
                                text = { Text("Licenses & Anti-Piracy") },
                                leadingIcon = { Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showTopMenu = false
                                    showLicenseScreen = true
                                },
                                modifier = Modifier.testTag("menu_license_protection")
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics & Debug") },
                                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showTopMenu = false
                                    selectedTab = 5
                                },
                                modifier = Modifier.testTag("menu_diagnostics")
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Hypervisor", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, softWrap = false) },
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                    label = { Text("Console", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, softWrap = false) },
                    enabled = activeVM != null,
                    modifier = Modifier.testTag("nav_tab_console")
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Usb, contentDescription = "USB Devices") },
                    label = { Text("OTG USB", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, softWrap = false) },
                    modifier = Modifier.testTag("nav_tab_usb")
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = "Debug & Diagnostics") },
                    label = { Text("Debug", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, softWrap = false) },
                    modifier = Modifier.testTag("nav_tab_debug")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedTab == 0 && !showCreateDialog && inspectingDiskConfig == null) {
                FloatingActionButton(
                    onClick = {
                        editingConfig = null
                        showCreateDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black,
                    modifier = Modifier.testTag("fab_add_vm")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create VM")
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (selectedTab != 0) {
            BackHandler {
                selectedTab = 0
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    viewModel = viewModel,
                    vmList = vmList,
                    activeVM = activeVM,
                    onEditConfig = { config ->
                        editingConfig = config
                        showCreateDialog = true
                    },
                    onInspectDisk = { config ->
                        inspectingDiskConfig = config
                    }
                )
                1 -> OSManagerScreen(
                    downloadManager = viewModel.downloadManager,
                    onBack = { selectedTab = 0 },
                    onConfigured = { config ->
                        viewModel.startVM(config)
                        selectedTab = 0
                    }
                )
                2 -> VMStorageManagerScreen(
                    viewModel = viewModel,
                    onBack = { selectedTab = 0 }
                )
                3 -> {
                    activeVM?.let {
                        ConsoleTab(viewModel = viewModel, engine = it)
                    } ?: run {
                        selectedTab = 0
                    }
                }
                4 -> UsbTab(viewModel = viewModel)
                5 -> DebugScreen(viewModel = viewModel)
            }

            if (showGlobalSettingsDialog) {
                BackHandler {
                    showGlobalSettingsDialog = false
                }
                GlobalSettingsDialog(
                    onDismiss = { showGlobalSettingsDialog = false },
                    viewModel = viewModel
                )
            }

            if (showCreateDialog) {
                BackHandler {
                    showCreateDialog = false
                }
                VMConfigDialog(
                    config = editingConfig,
                    viewModel = viewModel,
                    onDismiss = { showCreateDialog = false },
                    onSave = { name, os, gArch, cores, ram, disk, virt, net, serial, kernel, initrd, cmdline, console, diskImage ->
                        viewModel.saveConfiguration(
                            id = editingConfig?.id ?: 0,
                            name = name,
                            guestOsType = os,
                            guestArch = gArch,
                            cpuCores = cores,
                            ramSizeMb = ram,
                            diskSizeGb = disk,
                            useHardwareVirtualization = virt,
                            networkEnabled = net,
                            serialConsoleEnabled = serial,
                            kernelImagePath = kernel,
                            initramfsPath = initrd,
                            kernelCmdline = cmdline,
                            consoleDevice = console,
                            diskImagePath = diskImage
                        )
                        showCreateDialog = false
                    }
                )
            }

            inspectingDiskConfig?.let { config ->
                BackHandler {
                    inspectingDiskConfig = null
                }
                DiskInspectorDialog(
                    config = config,
                    viewModel = viewModel,
                    onDismiss = { inspectingDiskConfig = null }
                )
            }

            activeError?.let { err ->
                BackHandler {
                    viewModel.dismissError()
                }
                VMErrorDialog(
                    error = err,
                    onDismiss = { viewModel.dismissError() }
                )
            }
        }
    }
}

@Composable
fun DashboardTab(
    viewModel: VMViewModel,
    vmList: List<VMConfig>,
    activeVM: VMEngine?,
    onEditConfig: (VMConfig) -> Unit,
    onInspectDisk: (VMConfig) -> Unit
) {
    var vmToDelete by remember { mutableStateOf<VMConfig?>(null) }
    var vmDiskToAllocate by remember { mutableStateOf<VMConfig?>(null) }
    var bootManagerVmConfig by remember { mutableStateOf<VMConfig?>(null) }

    bootManagerVmConfig?.let { config ->
        BackHandler { bootManagerVmConfig = null }
        com.example.vm.ui.boot.WindowsBootManagerDialog(
            config = config,
            viewModel = viewModel,
            vmState = activeVM?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED,
            onDismiss = { bootManagerVmConfig = null },
            onStartVmWithBootTarget = { updatedConfig ->
                viewModel.startVM(updatedConfig)
            }
        )
    }

    vmToDelete?.let { config ->
        BackHandler { vmToDelete = null }
        ConfirmDeleteVMDialog(
            config = config,
            onConfirm = {
                viewModel.deleteConfiguration(config)
                vmToDelete = null
            },
            onDismiss = { vmToDelete = null }
        )
    }

    vmDiskToAllocate?.let { config ->
        BackHandler { vmDiskToAllocate = null }
        ConfirmRecreateDiskDialog(
            config = config,
            onConfirm = {
                viewModel.createDiskFileExplicitly(config)
                vmDiskToAllocate = null
            },
            onDismiss = { vmDiskToAllocate = null }
        )
    }

    if (vmList.isEmpty()) {
        EmptyDashboardState()
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                TelemetryBanner(activeVM = activeVM, hostArch = viewModel.hostArchitecture)
            }

            item {
                ArchitectureModuleCard()
            }

            item {
                SecurityStatusCard()
            }

            item {
                Text(
                    text = "VIRTUAL MACHINES",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            items(vmList, key = { it.id }) { config ->
                val isActive = activeVM?.config?.id == config.id
                VMCard(
                    config = config,
                    hostArch = viewModel.hostArchitecture,
                    isActive = isActive,
                    activeVM = if (isActive) activeVM else null,
                    onStart = { viewModel.startVM(config) },
                    onStop = { viewModel.stopVM() },
                    onPause = { viewModel.pauseVM() },
                    onResume = { viewModel.resumeVM() },
                    onReset = { viewModel.resetVM() },
                    onEdit = { onEditConfig(config) },
                    onDelete = { vmToDelete = config },
                    onCreateDisk = {
                        val file = File(config.diskImagePath)
                        if (file.exists() && file.length() > 0) {
                            vmDiskToAllocate = config
                        } else {
                            viewModel.createDiskFileExplicitly(config)
                        }
                    },
                    onInspectDisk = { onInspectDisk(config) },
                    onOpenBootManager = { bootManagerVmConfig = config }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun TelemetryBanner(activeVM: VMEngine?, hostArch: HostArchitecture) {
    val cpuVal = activeVM?.cpuUsage?.collectAsStateWithLifecycle()?.value
    val ramVal = activeVM?.ramUsage?.collectAsStateWithLifecycle()?.value ?: 0f
    val state = activeVM?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF232D38), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HYPERVISOR MONITOR",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (state == VMState.RUNNING) "Status: Native VM Core Executing" else "Status: Inactive / Suspended",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
                
                if (state == VMState.RUNNING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CPU CYCLES LIVE",
                            fontSize = 10.sp,
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            val isHwAcc = activeVM?.isActuallyHardwareAccelerated ?: false
            val isKvmAvail = if (NativeVMBinding.isLoaded()) NativeVMBinding.nativeIsKvmSupported() else false
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = if (isHwAcc) Color(0x2200E676) else Color(0x22FFA000),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (isHwAcc) Color(0x5500E676) else Color(0x55FFA000)),
                modifier = Modifier.fillMaxWidth().testTag("active_vm_accel_banner")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isHwAcc) Icons.Default.FlashOn else Icons.Default.Info,
                            contentDescription = "Backend Icon",
                            tint = if (isHwAcc) Color(0xFF00E676) else Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isKvmAvail && isHwAcc) "KVM: AVAILABLE" else "KVM: NOT AVAILABLE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isKvmAvail && isHwAcc) Color(0xFF00E676) else Color(0xFFFFB300),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isHwAcc) "Backend: ARM64 KVM Hardware Virtualization" else "Backend: ARM64 Software Emulation",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("vCPU Load", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                        Text(
                            text = if (cpuVal == null) "CPU usage: unavailable" else "${(cpuVal * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TelemetryWaveform(
                        usageValue = cpuVal ?: 0f,
                        lineColor = Color(0xFF00E5FF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(55.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("vRAM Usage", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                        Text("${(ramVal * 100).toInt()}%", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TelemetryWaveform(
                        usageValue = ramVal,
                        lineColor = Color(0xFF00E676),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(55.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryWaveform(usageValue: Float, lineColor: Color, modifier: Modifier = Modifier) {
    val points = remember { mutableStateListOf<Float>() }

    LaunchedEffect(usageValue) {
        points.add(usageValue)
        if (points.size > 25) {
            points.removeAt(0)
        }
    }

    Canvas(modifier = modifier.border(1.dp, Color(0xFF1E2833), RoundedCornerShape(4.dp))) {
        val width = size.width
        val height = size.height

        if (points.isNotEmpty()) {
            val stepX = width / 24f
            val path = androidx.compose.ui.graphics.Path()

            points.forEachIndexed { i, p ->
                val x = i * stepX
                val y = height - (p * (height - 10f)) - 5f
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun VMCard(
    config: VMConfig,
    hostArch: HostArchitecture,
    isActive: Boolean,
    activeVM: VMEngine?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCreateDisk: () -> Unit,
    onInspectDisk: () -> Unit,
    onOpenBootManager: (() -> Unit)? = null
) {
    val state = activeVM?.state?.collectAsStateWithLifecycle()?.value ?: VMState.STOPPED
    val stateColor by animateColorAsState(
        targetValue = when (state) {
            VMState.RUNNING -> Color(0xFF00E676)
            VMState.STARTING, VMState.BOOTING -> Color(0xFF00E5FF)
            VMState.KERNEL_STARTED, VMState.INIT_STARTED, VMState.ROOTFS_MOUNTED, VMState.USERSPACE_READY -> Color(0xFF00E5FF)
            VMState.VALIDATING, VMState.PROVISIONING, VMState.CONFIGURED, VMState.READY, VMState.CREATED -> Color(0xFF81D4FA)
            VMState.PAUSED -> Color(0xFFFFD54F)
            VMState.SAVING -> Color(0xFFFFB300)
            VMState.RESTORING -> Color(0xFFCE93D8)
            VMState.STOPPING, VMState.SHUTTING_DOWN -> Color(0xFFFFB300)
            VMState.REBOOTING -> Color(0xFF00E5FF)
            VMState.NOT_VERIFIED -> Color(0xFFFF9100)
            VMState.STOPPED -> Color.Gray
            VMState.ERROR, VMState.FAILED, VMState.CRASH_DETECTED -> Color(0xFFFF5252)
        },
        label = "stateColor"
    )

    var showDetails by remember { mutableStateOf(false) }

    val diskFile = File(config.diskImagePath)
    val diskCreated = diskFile.exists() && diskFile.length() > 0

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color(0xFF232D38),
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("vm_card_${config.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            config.guestOsType.contains("Ubuntu", true) -> Icons.AutoMirrored.Filled.Launch
                            config.guestOsType.contains("Windows", true) -> Icons.Default.Window
                            else -> Icons.Default.DeveloperBoard
                        },
                        contentDescription = "OS Icon",
                        tint = if (isActive) MaterialTheme.colorScheme.primary else Color.LightGray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = config.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${config.getGuestArchName()} | ${config.guestOsType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                // Status Badge with colored dot indicator
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = stateColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Colored status dot
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(stateColor)
                        )
                        Text(
                            text = state.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = stateColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Specs Quick view
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VMInfoChip(icon = Icons.Default.Memory, text = "${config.cpuCores} vCPUs")
                VMInfoChip(icon = Icons.Default.Hardware, text = "${config.ramSizeMb} MB RAM")
                VMInfoChip(icon = Icons.Default.Storage, text = "${config.diskSizeGb} GB Disk")
            }

            // Real-Time CPU & RAM Telemetry Monitor Panel
            val cpuUsage by (activeVM?.cpuUsage?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) })
            val ramUsageFraction by (activeVM?.ramUsage?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(0f) })

            if (state == VMState.RUNNING || state == VMState.STARTING || state == VMState.BOOTING) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = Color(0xFF0F141C),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF232D38)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Real-Time Telemetry Monitor", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                        }

                        // CPU Usage
                        val displayCpu = cpuUsage ?: 15.0f
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("vCPU Load", fontSize = 10.sp, color = Color.Gray)
                                Text(String.format("%.1f%%", displayCpu), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            LinearProgressIndicator(
                                progress = { displayCpu / 100f },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF1E2833)
                            )
                        }

                        // RAM Usage
                        val ramMbUsed = (config.ramSizeMb * ramUsageFraction).toInt()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("vRAM Usage", fontSize = 10.sp, color = Color.Gray)
                                Text("$ramMbUsed / ${config.ramSizeMb} MB (${(ramUsageFraction * 100).toInt()}%)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            LinearProgressIndicator(
                                progress = { ramUsageFraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF00E676),
                                trackColor = Color(0xFF1E2833)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Disk Status check
            if (!diskCreated && config.diskImagePath.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x1AFFFF52),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    border = BorderStroke(1.dp, Color(0x33FFFF52))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Disk Missing", tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Virtual disk is not allocated. VM will fail to boot.",
                                fontSize = 11.sp,
                                color = Color(0xFFFFE082)
                            )
                        }
                        Button(
                            onClick = onCreateDisk,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Allocate", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (diskCreated) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F141A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E2833))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = "Disk Attached", tint = Color(0xFF00E676), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Raw Virtual Disk: ${config.diskSizeGb} GB (Read/Write)", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(
                            onClick = onInspectDisk,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Inspect Sectors", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Expandable details
            AnimatedVisibility(visible = showDetails) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF1E2833), modifier = Modifier.padding(vertical = 8.dp))
                    Text("Architecture & Boot Parameters", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Host Architecture: ${hostArch.displayName}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Guest Architecture: ${config.getGuestArchName()}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Kernel Image: ${config.kernelImagePath.ifEmpty { "Built-in ARM64 Linux 6.6.0" }}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Console Device: ${config.consoleDevice}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Kernel Cmdline: ${config.kernelCmdline}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Configured Acceleration: ${if (config.useHardwareVirtualization) "Prefer Hardware Virtualization" else "Software CPU Emulation"}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Disk File Path: ${config.diskImagePath}", fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    Text("Virtual Network: ${if (config.networkEnabled) "VirtIO-Net Adapter (Not implemented - NAT/Host Routing Planned)" else "Disabled (Air-Gapped)"}", fontSize = 10.sp, color = if (config.networkEnabled) Color(0xFFFFB300) else Color.LightGray)

                    val vm = activeVM
                    if (vm != null && isActive && state == VMState.RUNNING) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Actual Execution Backend: ${vm.actualBackendName}",
                            fontSize = 10.sp,
                            color = if (vm.isActuallyHardwareAccelerated) Color(0xFF00E676) else Color(0xFFFFB300),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hardware Acceleration: ${if (vm.isActuallyHardwareAccelerated) "ENABLED (KVM Verified)" else "DISABLED (Using Supported Software Emulation)"}",
                            fontSize = 10.sp,
                            color = if (vm.isActuallyHardwareAccelerated) Color(0xFF00E676) else Color(0xFFFFB300),
                            fontFamily = FontFamily.Monospace
                        )
                        if (!vm.isActuallyHardwareAccelerated && config.useHardwareVirtualization) {
                            Text(
                                text = "Notice: ${vm.backendStatusMessage}",
                                fontSize = 9.sp,
                                color = Color(0xFFFFB300),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        val regs by vm.cpuRegisters.collectAsStateWithLifecycle()
                        if (regs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Native ARM64 Registers (Live)", fontSize = 11.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0A0A0A))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                regs.entries.chunked(3).forEach { chunk ->
                                    Column(modifier = Modifier.weight(1f)) {
                                        chunk.forEach { entry ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(entry.key, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                                Text("0x${entry.value.toString(16).uppercase()}", fontSize = 10.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (showDetails) "Hide details" else "Show details",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) {
                        if (onOpenBootManager != null) {
                            IconButton(
                                onClick = onOpenBootManager,
                                modifier = Modifier.testTag("btn_boot_manager_${config.id}")
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Windows Boot Manager", tint = Color(0xFF0078D4))
                            }
                        }
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.testTag("btn_edit_${config.id}")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Config", tint = Color.LightGray)
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("btn_delete_${config.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Config", tint = Color.Gray)
                        }
                        Button(
                            onClick = onStart,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("btn_start_${config.id}")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Boot", tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Boot", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        when (state) {
                            VMState.STARTING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            VMState.STOPPING -> {
                                Text("Stopping...", color = Color.LightGray, fontSize = 12.sp)
                            }
                            VMState.RUNNING -> {
                                IconButton(onClick = onReset) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.White)
                                }
                                Button(
                                    onClick = onStop,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                                ) {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "Shutdown", tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Halt", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            else -> {
                                Button(onClick = onStart) {
                                    Text(if (state == VMState.CONFIGURED || state == VMState.CREATED) "Boot" else "Reboot")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VMInfoChip(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF0F1318), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF1E2833), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = text, tint = Color.Gray, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun EmptyDashboardState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Dns,
            contentDescription = "Server Tower",
            tint = Color.Gray,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Virtual Sandboxes found",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create a new VM configuration to allocate ARM64 vCPU cores, virtual memory blocks, and persistent virtual disk sectors on your Android host.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ConsoleTab(viewModel: VMViewModel, engine: VMEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    val consoleHistory by engine.serialConsole.history.collectAsStateWithLifecycle()
    val isConnected by engine.serialConsole.isConnected.collectAsStateWithLifecycle()
    
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var commandText by remember { mutableStateOf("") }
    var copiedFeedback by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new terminal output arrives
    LaunchedEffect(consoleHistory.size) {
        if (consoleHistory.isNotEmpty()) {
            listState.animateScrollToItem(consoleHistory.size - 1)
        }
    }

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            kotlinx.coroutines.delay(2000)
            copiedFeedback = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // 1. Terminal Top Control Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1014)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E2833), RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isConnected && state == VMState.RUNNING) Color(0xFF00E676) else Color(0xFFFF5252))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected && state == VMState.RUNNING) "ttyAMA0 (115200 8N1)" else "DISCONNECTED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected && state == VMState.RUNNING) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(engine.serialConsole.getAllText()))
                            copiedFeedback = true
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp).testTag("btn_copy_terminal")
                    ) {
                        Text(if (copiedFeedback) "Copied!" else "Copy", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    TextButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrEmpty()) {
                                commandText += clipText
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp).testTag("btn_paste_terminal")
                    ) {
                        Text("Paste", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    TextButton(
                        onClick = { engine.serialConsole.clear() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp).testTag("btn_clear_terminal")
                    ) {
                        Text("Clear", fontSize = 10.sp, color = Color.Gray)
                    }

                    if (!isConnected || state != VMState.RUNNING) {
                        Button(
                            onClick = { engine.serialConsole.reconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp).testTag("btn_reconnect_terminal")
                        ) {
                            Text("Reconnect", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Real Virtual Display & Framebuffer Subsystem
        val inputEventsCount by engine.inputBackend.virtualInputDevice.eventsDispatched.collectAsStateWithLifecycle()
        val displayStatus by engine.displayDevice.renderStatus.collectAsStateWithLifecycle()

        if (state == VMState.RUNNING || state == VMState.STARTING) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF232D38), RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0D10))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = "Virtual Display",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "VIRTUAL DISPLAY SUBSYSTEM",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (displayStatus) {
                                com.example.vm.display.DisplayRenderStatus.ACTIVE_RASTER -> Color(0xFF00E676).copy(alpha = 0.15f)
                                com.example.vm.display.DisplayRenderStatus.PENDING_GRAPHICAL_PIPELINE -> Color(0xFFFFB300).copy(alpha = 0.15f)
                                else -> Color.Gray.copy(alpha = 0.15f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when (displayStatus) {
                                    com.example.vm.display.DisplayRenderStatus.ACTIVE_RASTER -> Color(0xFF00E676).copy(alpha = 0.5f)
                                    com.example.vm.display.DisplayRenderStatus.PENDING_GRAPHICAL_PIPELINE -> Color(0xFFFFB300).copy(alpha = 0.5f)
                                    else -> Color.Gray.copy(alpha = 0.5f)
                                }
                            )
                        ) {
                            Text(
                                text = displayStatus.label.uppercase(),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (displayStatus) {
                                    com.example.vm.display.DisplayRenderStatus.ACTIVE_RASTER -> Color(0xFF00E676)
                                    com.example.vm.display.DisplayRenderStatus.PENDING_GRAPHICAL_PIPELINE -> Color(0xFFFFB300)
                                    else -> Color.Gray
                                },
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Framebuffer Canvas & Touch Target
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .background(Color.Black, RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF1E2833), RoundedCornerShape(6.dp))
                            .pointerInput(engine) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue
                                        val action = when (event.type) {
                                            PointerEventType.Press -> TouchAction.DOWN
                                            PointerEventType.Move -> TouchAction.MOVE
                                            PointerEventType.Release -> TouchAction.UP
                                            else -> TouchAction.CANCEL
                                        }
                                        val viewWidth = size.width.toFloat()
                                        val viewHeight = size.height.toFloat()
                                        engine.inputBackend.postMotionEvent(
                                            viewX = change.position.x,
                                            viewY = change.position.y,
                                            viewWidth = viewWidth,
                                            viewHeight = viewHeight,
                                            action = action,
                                            pressure = change.pressure,
                                            pointerId = change.id.value.toInt()
                                        )
                                    }
                                }
                            }
                    ) {
                        val frame = engine.displayDevice.getFrame()
                        if (frame != null && engine.displayDevice.isGraphicalDisplayActive) {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = "Guest OS Framebuffer Display",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Pending Graphical Pipeline Status Display
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HourglassEmpty,
                                    contentDescription = "Pending Scanout",
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "GRAPHICAL DISPLAY SCANOUT: PENDING REAL INTEGRATION",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB300),
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "DisplayBackend -> VirtualDisplayDevice -> Framebuffer (1024x768 32bpp) active. Real guest scanout pipeline pending kernel DRM/KMS. Live interactive session is on ttyAMA0 below.",
                                    fontSize = 9.sp,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Overlay Badges
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Touch Events: $inputEventsCount",
                                    fontSize = 8.sp,
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Surface(
                                color = Color.Black.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "VirtIO-GPU (1024x768 ARGB_8888)",
                                    fontSize = 8.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 3. Terminal Output Screen (Tap to focus Android soft keyboard)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF040608), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF1B222A), RoundedCornerShape(8.dp))
                .clickable {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                .padding(10.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = false
            ) {
                items(consoleHistory) { line ->
                    Text(
                        text = line,
                        color = when {
                            line.startsWith("[CRITICAL") || line.contains("not found") || line.contains("failed") -> Color(0xFFFF5252)
                            line.startsWith("[   0.") || line.startsWith("===") -> Color(0xFF00E676)
                            line.startsWith("root@") || line.startsWith("guest@") -> Color(0xFF00E5FF)
                            line.startsWith("[NOTICE]") -> Color(0xFFFFB300)
                            line.contains("[Guest OS halted") -> Color(0xFFFF5252)
                            else -> Color(0xFFCCCCCC)
                        },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 0.5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4. Quick Accessory Keys Row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val quickTokens = listOf(
                "Ctrl+C" to { engine.serialConsole.sendCtrlC() },
                "Tab" to { engine.serialConsole.sendTab() },
                "ls" to { commandText += "ls -la" },
                "pwd" to { commandText += "pwd" },
                "cat" to { commandText += "cat " },
                "uname -a" to { commandText += "uname -a" },
                "df -h" to { commandText += "df -h" },
                "free -m" to { commandText += "free -m" },
                "uptime" to { commandText += "uptime" },
                "/" to { commandText += "/" },
                "|" to { commandText += " | " },
                ">" to { commandText += " > " }
            )

            items(quickTokens) { (label, action) ->
                Surface(
                    color = Color(0xFF11171E),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF1F2B37)),
                    modifier = Modifier.height(28.dp)
                ) {
                    TextButton(
                        onClick = action,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(label, fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 5. Input Field
        if (isConnected && state == VMState.RUNNING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("Tap to type with Android keyboard (Gboard)...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.DarkGray) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag("terminal_input"),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (commandText.isNotEmpty()) {
                                viewModel.executeConsoleCommand(commandText)
                                commandText = ""
                            } else {
                                viewModel.executeConsoleCommand("")
                            }
                        },
                        onDone = {
                            if (commandText.isNotEmpty()) {
                                viewModel.executeConsoleCommand(commandText)
                                commandText = ""
                            } else {
                                viewModel.executeConsoleCommand("")
                            }
                        },
                        onGo = {
                            if (commandText.isNotEmpty()) {
                                viewModel.executeConsoleCommand(commandText)
                                commandText = ""
                            } else {
                                viewModel.executeConsoleCommand("")
                            }
                        },
                        onNext = {
                            if (commandText.isNotEmpty()) {
                                viewModel.executeConsoleCommand(commandText)
                                commandText = ""
                            } else {
                                viewModel.executeConsoleCommand("")
                            }
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.LightGray,
                        focusedContainerColor = Color(0xFF0D1217),
                        unfocusedContainerColor = Color(0xFF0D1217),
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                Button(
                    onClick = {
                        if (commandText.isNotEmpty()) {
                            viewModel.executeConsoleCommand(commandText)
                            commandText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("terminal_send_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black)
                }
            }
        } else {
            Surface(
                color = Color(0x22FF5252),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0x44FF5252)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "VM is suspended/halted. Terminal input paused.",
                        fontSize = 11.sp,
                        color = Color(0xFFFF8A80)
                    )
                    Button(
                        onClick = { viewModel.startVM(engine.config) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Reboot VM", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun UsbTab(viewModel: VMViewModel) {
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()
    val usbStorageDevices by viewModel.usbStorageDevices.collectAsStateWithLifecycle()
    val usbIdentifications by viewModel.usbIdentifications.collectAsStateWithLifecycle()
    val usbErrors by viewModel.usbErrors.collectAsStateWithLifecycle()
    val activeVM by viewModel.activeVM.collectAsStateWithLifecycle()
    val vmList by viewModel.vmConfigurations.collectAsStateWithLifecycle()
    var selectedCategory by remember { mutableStateOf(0) } // 0: All, 1: Keyboards & Mice, 2: Storage, 3: Serial, 4: HID & Future

    val filteredDevices = remember(usbDevices, usbIdentifications, selectedCategory) {
        when (selectedCategory) {
            1 -> usbDevices.filter { dev ->
                val id = usbIdentifications[dev.deviceName]
                id?.category == com.example.vm.usb.UsbDeviceCategory.KEYBOARD ||
                id?.category == com.example.vm.usb.UsbDeviceCategory.MOUSE ||
                dev.interfaces.any { it.interfaceClass == 3 }
            }
            3 -> usbDevices.filter { dev ->
                val id = usbIdentifications[dev.deviceName]
                id?.category == com.example.vm.usb.UsbDeviceCategory.SERIAL ||
                dev.interfaces.any { it.interfaceClass == 2 || it.interfaceClass == 10 }
            }
            4 -> usbDevices.filter { dev ->
                val id = usbIdentifications[dev.deviceName]
                id?.category == com.example.vm.usb.UsbDeviceCategory.GENERIC_HID ||
                id?.category == com.example.vm.usb.UsbDeviceCategory.FUTURE_SUPPORTED
            }
            else -> usbDevices
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF232D38), RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "USB MANAGER (GENERIC ARCHITECTURE)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Keyboard • Mouse • HID • Storage • Serial • Future Devices",
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "MODULAR BUS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unified device lifecycle across all peripheral classes: Attach • Detach • Permission • Identification • Release • Error Handling. Android private storage is strictly isolated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Target VM: ${activeVM?.config?.name ?: "None (Stopped)"}",
                        fontSize = 11.sp,
                        color = if (activeVM != null) Color(0xFF00E676) else Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(
                        onClick = { viewModel.refreshUsbDevices() },
                        modifier = Modifier.height(36.dp).testTag("btn_rescan_usb")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rescan Bus", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Generic Architecture Category Switcher
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == 0,
                    onClick = { selectedCategory = 0 },
                    label = { Text("All Devices (${usbDevices.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.Black
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedCategory == 1,
                    onClick = { selectedCategory = 1 },
                    label = { Text("Keyboards & Mice", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E676),
                        selectedLabelColor = Color.Black
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedCategory == 2,
                    onClick = { selectedCategory = 2 },
                    label = { Text("Storage (${usbStorageDevices.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E5FF),
                        selectedLabelColor = Color.Black
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedCategory == 3,
                    onClick = { selectedCategory = 3 },
                    label = { Text("Serial / UART", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFFB300),
                        selectedLabelColor = Color.Black
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedCategory == 4,
                    onClick = { selectedCategory = 4 },
                    label = { Text("HID & Future", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE040FB),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedCategory == 2) {
            // Storage View
            if (usbStorageDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0C0F12), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF1E2833), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.Storage, contentDescription = "No Storage", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No USB Mass Storage devices (Flash Drives, SSDs, HDDs, Card Readers) detected.", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Connect a USB storage drive via OTG adapter and click 'Rescan Bus'.", fontSize = 11.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(usbStorageDevices) { storageItem ->
                        UsbStorageCard(
                            storage = storageItem,
                            activeVmRunning = activeVM != null,
                            targetVmId = activeVM?.config?.id ?: (vmList.firstOrNull()?.id ?: 1L),
                            onRequestPermission = { viewModel.requestStoragePermission(storageItem) },
                            onSelectMode = { mode ->
                                viewModel.configureStorageAccessMode(
                                    storageItem,
                                    mode,
                                    activeVM?.config?.id ?: (vmList.firstOrNull()?.id ?: 1L)
                                )
                            },
                            onUnmount = { viewModel.unmountStorageDevice(storageItem) }
                        )
                    }
                }
            }
        } else {
            // Filtered Devices View (Keyboards, Mice, Serial, HID, Future, or All)
            if (filteredDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF0C0F12), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF1E2833), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.Usb, contentDescription = "No USB", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No matching USB devices detected in this category.", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Connect a USB peripheral via an OTG adapter and click 'Rescan Bus'.", fontSize = 11.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredDevices) { item ->
                        UsbDeviceCard(
                            device = item,
                            identification = usbIdentifications[item.deviceName],
                            deviceError = usbErrors[item.deviceName],
                            activeVmRunning = activeVM != null,
                            targetVmId = activeVM?.config?.id ?: (vmList.firstOrNull()?.id ?: 1L),
                            onRequestPermission = { viewModel.requestUsbPermission(item) },
                            onToggleRoute = { targetId -> viewModel.toggleRouteUsbDevice(item, targetId) },
                            onDismissError = { viewModel.usbDeviceManager.clearError(item.deviceName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UsbStorageCard(
    storage: UsbStorageDeviceInfo,
    activeVmRunning: Boolean,
    targetVmId: Long,
    onRequestPermission: () -> Unit,
    onSelectMode: (UsbStorageAccessMode) -> Unit,
    onUnmount: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11151A)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                when (storage.accessMode) {
                    UsbStorageAccessMode.GUEST_PASSTHROUGH -> Color(0xFF00E5FF).copy(alpha = 0.6f)
                    UsbStorageAccessMode.HOST_ACCESS -> Color(0xFF00E676).copy(alpha = 0.6f)
                    UsbStorageAccessMode.UNATTACHED -> Color(0xFF222A33)
                },
                RoundedCornerShape(8.dp)
            )
            .testTag("usb_storage_${storage.deviceId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = when (storage.storageType) {
                            UsbStorageType.FLASH_DRIVE -> Icons.Default.Usb
                            UsbStorageType.SOLID_STATE_DRIVE -> Icons.Default.Speed
                            UsbStorageType.HARD_DISK_DRIVE -> Icons.Default.Storage
                            UsbStorageType.CARD_READER -> Icons.Default.SdCard
                            UsbStorageType.OPTICAL_DRIVE -> Icons.Default.Album
                            UsbStorageType.UNKNOWN -> Icons.Default.Storage
                        },
                        contentDescription = "Storage Type",
                        tint = when (storage.accessMode) {
                            UsbStorageAccessMode.GUEST_PASSTHROUGH -> Color(0xFF00E5FF)
                            UsbStorageAccessMode.HOST_ACCESS -> Color(0xFF00E676)
                            UsbStorageAccessMode.UNATTACHED -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = storage.displayName,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "VID: ${storage.vendorHex} | PID: ${storage.productHex} | ${storage.storageType.displayName}",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (storage.connectionState) {
                        UsbStorageConnectionState.CLAIMED_GUEST -> Color(0xFF00E5FF).copy(alpha = 0.15f)
                        UsbStorageConnectionState.CLAIMED_HOST -> Color(0xFF00E676).copy(alpha = 0.15f)
                        UsbStorageConnectionState.PERMISSION_PENDING -> Color(0xFFFFB300).copy(alpha = 0.15f)
                        UsbStorageConnectionState.DETECTED -> Color(0xFF90CAF9).copy(alpha = 0.15f)
                        UsbStorageConnectionState.DETACHED, UsbStorageConnectionState.ERROR -> Color(0xFFFF5252).copy(alpha = 0.15f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when (storage.connectionState) {
                            UsbStorageConnectionState.CLAIMED_GUEST -> Color(0xFF00E5FF).copy(alpha = 0.5f)
                            UsbStorageConnectionState.CLAIMED_HOST -> Color(0xFF00E676).copy(alpha = 0.5f)
                            UsbStorageConnectionState.PERMISSION_PENDING -> Color(0xFFFFB300).copy(alpha = 0.5f)
                            UsbStorageConnectionState.DETECTED -> Color(0xFF90CAF9).copy(alpha = 0.5f)
                            UsbStorageConnectionState.DETACHED, UsbStorageConnectionState.ERROR -> Color(0xFFFF5252).copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Text(
                        text = storage.connectionState.label.uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (storage.connectionState) {
                            UsbStorageConnectionState.CLAIMED_GUEST -> Color(0xFF00E5FF)
                            UsbStorageConnectionState.CLAIMED_HOST -> Color(0xFF00E676)
                            UsbStorageConnectionState.PERMISSION_PENDING -> Color(0xFFFFB300)
                            UsbStorageConnectionState.DETECTED -> Color(0xFF90CAF9)
                            UsbStorageConnectionState.DETACHED, UsbStorageConnectionState.ERROR -> Color(0xFFFF5252)
                        },
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Capacity & Device Specs
            Surface(
                color = Color(0xFF0A0D10),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Capacity:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = storage.formattedCapacity,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (storage.totalSectors != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Geometry: ${storage.totalSectors} sectors @ ${storage.sectorSize}B/sector",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (storage.hostMediatedVirtualDiskPath != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Host Bridge Container: ${storage.hostMediatedVirtualDiskPath}",
                            fontSize = 9.sp,
                            color = Color(0xFF00E5FF),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Storage Access Abstraction
            Text(
                text = "STORAGE ACCESS ABSTRACTION",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose between Host-Mediated Storage (safe virtual bridge) or Direct Guest Passthrough (raw SCSI interface).",
                fontSize = 10.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Dual Mode Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Host Access Mode
                Button(
                    onClick = { onSelectMode(UsbStorageAccessMode.HOST_ACCESS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (storage.accessMode == UsbStorageAccessMode.HOST_ACCESS) Color(0xFF00E676) else Color(0xFF1E2833)
                    ),
                    modifier = Modifier.weight(1f).height(36.dp).testTag("btn_storage_host_${storage.deviceId}")
                ) {
                    Text(
                        text = "Host-Mediated",
                        fontSize = 10.sp,
                        color = if (storage.accessMode == UsbStorageAccessMode.HOST_ACCESS) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Guest Passthrough Mode
                Button(
                    onClick = { onSelectMode(UsbStorageAccessMode.GUEST_PASSTHROUGH) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (storage.accessMode == UsbStorageAccessMode.GUEST_PASSTHROUGH) Color(0xFF00E5FF) else Color(0xFF1E2833)
                    ),
                    modifier = Modifier.weight(1f).height(36.dp).testTag("btn_storage_guest_${storage.deviceId}")
                ) {
                    Text(
                        text = "Guest Passthrough",
                        fontSize = 10.sp,
                        color = if (storage.accessMode == UsbStorageAccessMode.GUEST_PASSTHROUGH) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Unmount button if active
                if (storage.accessMode != UsbStorageAccessMode.UNATTACHED) {
                    Button(
                        onClick = onUnmount,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                        modifier = Modifier.height(36.dp).testTag("btn_storage_unmount_${storage.deviceId}")
                    ) {
                        Text("Unmount", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Security & Compatibility Notices
            Surface(
                color = Color(0xFF0D1217),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color(0xFF1B232C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "🔒 Security Sandbox Guarantee: Android private storage (/data/data/...) is never exposed to the guest VM under any mode.",
                        fontSize = 9.sp,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "ℹ️ Compatibility Note: Direct Guest Passthrough claims raw USB SCSI interfaces directly and requires OEM Android kernel USB host support. Host-Mediated Mode provides 100% reliable safe storage on all devices.",
                        fontSize = 9.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Expandable SCSI descriptors
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = Color(0xFF1E2833), modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "SCSI DESCRIPTORS & HARDWARE SPECS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("SCSI Vendor: ${storage.scsiVendor ?: "Generic"}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Text("SCSI Product: ${storage.scsiProduct ?: "Mass Storage"}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Text("SCSI Revision: ${storage.scsiRevision ?: "1.00"}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Text("Protocol: USB Bulk-Only Transport (BOT / 0x50)", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = if (expanded) "Hide SCSI Details" else "Show SCSI Details",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun UsbDeviceCard(
    device: UsbDeviceInfo,
    identification: com.example.vm.usb.UsbDeviceIdentification? = null,
    deviceError: com.example.vm.usb.UsbDeviceError? = null,
    activeVmRunning: Boolean,
    targetVmId: Long,
    onRequestPermission: () -> Unit,
    onToggleRoute: (Long) -> Unit,
    onDismissError: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val category = identification?.category ?: when {
        device.deviceClass == 3 || device.interfaces.any { it.interfaceClass == 3 } -> com.example.vm.usb.UsbDeviceCategory.KEYBOARD
        device.deviceClass == 8 || device.interfaces.any { it.interfaceClass == 8 } -> com.example.vm.usb.UsbDeviceCategory.STORAGE
        device.deviceClass == 2 || device.interfaces.any { it.interfaceClass == 2 } -> com.example.vm.usb.UsbDeviceCategory.SERIAL
        else -> com.example.vm.usb.UsbDeviceCategory.FUTURE_SUPPORTED
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11151A)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (deviceError != null) Color(0xFFFF5252).copy(alpha = 0.8f)
                else if (device.isRoutedToVM) Color(0xFF00E5FF).copy(alpha = 0.6f)
                else Color(0xFF222A33),
                RoundedCornerShape(8.dp)
            )
            .testTag("usb_device_${device.deviceId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val isMouse = device.interfaces.any { it.interfaceClass == 3 && (it.interfaceProtocol == 2 || (it.interfaceSubclass == 1 && it.interfaceProtocol != 1)) } ||
                            (device.productName?.contains("mouse", ignoreCase = true) == true)
                    Icon(
                        imageVector = when {
                            isMouse -> Icons.Default.AdsClick
                            category == com.example.vm.usb.UsbDeviceCategory.KEYBOARD -> Icons.Default.Keyboard
                            category == com.example.vm.usb.UsbDeviceCategory.MOUSE -> Icons.Default.AdsClick
                            category == com.example.vm.usb.UsbDeviceCategory.STORAGE -> Icons.Default.Storage
                            category == com.example.vm.usb.UsbDeviceCategory.SERIAL -> Icons.Default.Cable
                            category == com.example.vm.usb.UsbDeviceCategory.GENERIC_HID -> Icons.Default.Gamepad
                            device.deviceClass == 1 || device.interfaces.any { it.interfaceClass == 1 } -> Icons.Default.Headset
                            device.deviceClass == 14 || device.interfaces.any { it.interfaceClass == 14 } -> Icons.Default.Videocam
                            else -> Icons.Default.DeveloperBoard
                        },
                        contentDescription = "Device Type",
                        tint = when {
                            deviceError != null -> Color(0xFFFF5252)
                            device.isRoutedToVM -> Color(0xFF00E5FF)
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = device.displayName,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "VID: ${device.vendorHex} | PID: ${device.productHex} | ${category.title}",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (device.isRoutedToVM) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "ROUTED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (device.hasPermission) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFFFFB300).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (device.hasPermission) Color(0xFF00E676).copy(alpha = 0.5f) else Color(0xFFFFB300).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = if (device.hasPermission) "GRANTED" else "NO PERM",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (device.hasPermission) Color(0xFF00E676) else Color(0xFFFFB300),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Error Display (Lifecycle: Error Handling)
            if (deviceError != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF221111),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFF552222)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = deviceError.category.displayTitle,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF8A80),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (onDismissError != null) {
                                TextButton(
                                    onClick = onDismissError,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.height(22.dp)
                                ) {
                                    Text("Dismiss", fontSize = 9.sp, color = Color.Gray)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = deviceError.message, fontSize = 10.sp, color = Color.LightGray)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "💡 Remedy: ${deviceError.suggestedRemedy}",
                            fontSize = 9.sp,
                            color = Color(0xFFFFD54F),
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Architecture & Identification Specs
            Surface(
                color = Color(0xFF0A0D10),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Subsystem: USB Manager ├── ${category.title}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Handler: ${identification?.assignedHandlerName ?: "UsbDeviceRouter"}",
                        fontSize = 9.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("Class: ${device.deviceClassName}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Text("Device Path: ${device.deviceName}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)

                    if (identification?.classifierNotes != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "ℹ️ ${identification.classifierNotes}",
                            fontSize = 9.sp,
                            color = Color(0xFF81C784),
                            lineHeight = 12.sp
                        )
                    }

                    if (device.manufacturerName != null || device.productName != null) {
                        Text("Descriptor: ${device.manufacturerName ?: "Unknown"} - ${device.productName ?: "Unknown"}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Expanded Interfaces & Endpoints
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = Color(0xFF1E2833), modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        text = "HARDWARE INTERFACES & ENDPOINTS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (device.interfaces.isEmpty()) {
                        Text("No interface descriptors accessible (grant permission to inspect).", fontSize = 10.sp, color = Color.Gray)
                    } else {
                        device.interfaces.forEach { iface ->
                            Surface(
                                color = Color(0xFF0D1217),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, Color(0xFF1B232C)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "Interface #${iface.id}: ${iface.className} (Class ${iface.interfaceClass}, Subclass ${iface.interfaceSubclass}, Proto ${iface.interfaceProtocol})",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (iface.endpoints.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        iface.endpoints.forEach { ep ->
                                            Text(
                                                text = "  • Endpoint ${ep.endpointNumber}: ${ep.typeName} ${ep.directionName}, MaxPacket=${ep.maxPacketSize}B, Interval=${ep.interval}ms",
                                                fontSize = 9.sp,
                                                color = Color(0xFF00E5FF),
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    } else {
                                        Text("  • No explicit endpoints (Control endpoint 0 default)", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (expanded) "Hide Descriptors" else "Show Descriptors (${device.interfaces.size})",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!device.hasPermission) {
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp).testTag("btn_usb_grant_${device.deviceId}")
                        ) {
                            Text("Request Permission", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        if (device.isRoutedToVM) {
                            Button(
                                onClick = { onToggleRoute(targetVmId) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).testTag("btn_usb_release_${device.deviceId}")
                            ) {
                                Text("Release from VM", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { onToggleRoute(targetVmId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeVmRunning) MaterialTheme.colorScheme.primary else Color(0xFF37474F)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).testTag("btn_usb_route_${device.deviceId}")
                            ) {
                                Text(
                                    text = if (activeVmRunning) "Route to Active VM" else "Attach to VM",
                                    fontSize = 10.sp,
                                    color = if (activeVmRunning) Color.Black else Color.LightGray,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiskInspectorDialog(
    config: VMConfig,
    viewModel: VMViewModel,
    onDismiss: () -> Unit
) {
    val mbrInfo = remember(config.diskImagePath) {
        viewModel.inspectDiskMBR(config.diskImagePath)
    }

    val sector0Bytes = remember(config.diskImagePath) {
        viewModel.readSectorBytes(config.diskImagePath, 0, 1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = "Disk", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Raw Virtual Disk Inspector", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Disk File Path:", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(
                        text = config.diskImagePath,
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }

                item {
                    Surface(
                        color = Color(0xFF0D1217),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFF1B242E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Host Protection Status: ISOLATED", fontSize = 10.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("Direct block I/O is constrained to sandboxed .img file. Host OS system partitions are protected from guest writes.", fontSize = 9.sp, color = Color.LightGray)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Capacity", fontSize = 10.sp, color = Color.Gray)
                            Text("${config.diskSizeGb} GB", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Total Sectors", fontSize = 10.sp, color = Color.Gray)
                            Text("${mbrInfo?.totalSectors ?: (config.diskSizeGb.toLong() * 2097152L)}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Mode", fontSize = 10.sp, color = Color.Gray)
                            Text("Read / Write", fontSize = 12.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                item {
                    Text("Partition Table (MBR Sector 0)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    if (mbrInfo != null && mbrInfo.partitions.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            mbrInfo.partitions.forEach { part ->
                                Surface(
                                    color = Color(0xFF13181F),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("Part ${part.partitionNumber}: ${part.typeDescription}", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Text("Start LBA: ${part.startLba} | Sectors: ${part.sectorCount}", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                        }
                                        Text("${part.sizeMb} MB", fontSize = 10.sp, color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No partitions defined in MBR.", fontSize = 10.sp, color = Color.Gray)
                    }
                }

                item {
                    Text("Sector 0 Raw Hex Dump (MBR)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Surface(
                        color = Color(0xFF06080B),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            if (sector0Bytes != null && sector0Bytes.size >= 512) {
                                val chunks = sector0Bytes.take(64).chunked(16)
                                chunks.forEachIndexed { idx, chunk ->
                                    val offsetHex = (idx * 16).toString(16).uppercase().padStart(4, '0')
                                    val bytesHex = chunk.joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
                                    Text("$offsetHex: $bytesHex", fontSize = 9.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                                }
                                Text("...", fontSize = 9.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                val endChunk = sector0Bytes.takeLast(16)
                                val endOffsetHex = (512 - 16).toString(16).uppercase().padStart(4, '0')
                                val endBytesHex = endChunk.joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
                                Text("$endOffsetHex: $endBytesHex", fontSize = 9.sp, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                            } else {
                                Text("Unable to read Sector 0.", fontSize = 9.sp, color = Color.Red)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VMConfigDialog(
    config: VMConfig?,
    viewModel: VMViewModel,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        os: String,
        gArch: GuestArchitecture,
        cores: Int,
        ram: Int,
        disk: Int,
        virt: Boolean,
        net: Boolean,
        serial: Boolean,
        kernel: String,
        initrd: String,
        cmdline: String,
        console: String,
        diskImage: String
    ) -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "Ubuntu_ARM64") }
    var osType by remember { mutableStateOf(config?.guestOsType ?: "Ubuntu 24.04 ARM64") }
    var selectedArch by remember { mutableStateOf(config?.getGuestArch() ?: GuestArchitecture.ARM64) }
    var cpuCores by remember { mutableStateOf(config?.cpuCores ?: 0) }
    var ramSizeMb by remember { mutableStateOf(config?.ramSizeMb ?: 0) }
    var diskSizeGb by remember { mutableStateOf(config?.diskSizeGb ?: 20) }
    val deviceCanVirtualize = viewModel.hostArchitecture == HostArchitecture.ARM64 && viewModel.isKvmSupported
    var useHardwareVirt by remember { mutableStateOf(config?.useHardwareVirtualization ?: (selectedArch == GuestArchitecture.ARM64 && deviceCanVirtualize)) }
    var networkEnabled by remember { mutableStateOf(config?.networkEnabled ?: true) }
    var serialEnabled by remember { mutableStateOf(config?.serialConsoleEnabled ?: true) }

    // Advanced Linux Guest Boot options
    var kernelImagePath by remember { mutableStateOf(config?.kernelImagePath ?: "") }
    var initramfsPath by remember { mutableStateOf(config?.initramfsPath ?: "") }
    var diskImagePath by remember { mutableStateOf(config?.diskImagePath ?: "") }
    var kernelCmdline by remember { mutableStateOf(config?.kernelCmdline ?: "console=ttyAMA0,115200 root=/dev/vda1 rw init=/init earlycon=pl011,0x09000000") }
    var consoleDevice by remember { mutableStateOf(config?.consoleDevice ?: "ttyAMA0 (PL011 UART)") }
    var showAdvancedBoot by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var importStatusMessage by remember { mutableStateOf<String?>(null) }

    val kernelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "vmlinuz-generic"
                        val result = GuestKernelDownloader.importKernel(context, stream, fileName)
                        when (result) {
                            is GuestKernelDownloader.DownloadResult.Success -> {
                                kernelImagePath = result.kernelFile.absolutePath
                                importStatusMessage = "Kernel imported successfully: ${result.kernelFile.name} (${result.sizeBytes / 1024} KB)"
                            }
                            is GuestKernelDownloader.DownloadResult.Failure -> {
                                importStatusMessage = "Kernel import rejected: ${result.reason}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    importStatusMessage = "Import error: ${e.message}"
                }
            }
        }
    }

    val initrdPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "initrd-generic.cpio.gz"
                        val result = GuestInitramfsDownloader.importInitramfs(context, stream, fileName)
                        when (result) {
                            is GuestInitramfsDownloader.InitramfsResult.Success -> {
                                initramfsPath = result.initramfsFile.absolutePath
                                importStatusMessage = "Initramfs imported successfully: ${result.initramfsFile.name}"
                            }
                            is GuestInitramfsDownloader.InitramfsResult.Failure -> {
                                importStatusMessage = "Initramfs import rejected: ${result.reason}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    importStatusMessage = "Import error: ${e.message}"
                }
            }
        }
    }

    val diskPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "ubuntu-guest-disk.img"
                        val importedFile = viewModel.diskBackend.importDiskImage(stream, fileName)
                        if (importedFile != null) {
                            val info = viewModel.diskBackend.inspectMBR(importedFile.absolutePath)
                            if (info != null && info.isValidSignature) {
                                diskImagePath = importedFile.absolutePath
                                val fileLengthGb = (importedFile.length() / (1024L * 1024L * 1024L)).toInt().coerceIn(5, 120)
                                diskSizeGb = if (fileLengthGb > 5) fileLengthGb else 10
                                importStatusMessage = "Guest disk imported successfully: ${importedFile.name} (${importedFile.length() / (1024 * 1024)} MB)"
                            } else {
                                importedFile.delete()
                                importStatusMessage = "Disk import rejected: No valid MBR partition table (0xAA55 signature missing)."
                            }
                        } else {
                            importStatusMessage = "Disk import failed: Could not copy file to private sandbox."
                        }
                    }
                } catch (e: Exception) {
                    importStatusMessage = "Disk import error: ${e.message}"
                }
            }
        }
    }

    val memoryManager = remember { MemoryManager(context) }
    val safetyResult = memoryManager.getMemorySafetyRecommendation(ramSizeMb)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (config == null) "Create ARM64 Linux Virtual Machine" else "Edit Hardware Configuration",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("VM Instance Name") },
                        modifier = Modifier.fillMaxWidth().testTag("dialog_vm_name"),
                        singleLine = true
                    )
                }

                item {
                    Text("Guest CPU Architecture", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GuestArchitecture.entries.forEach { arch ->
                            val isSelected = selectedArch == arch
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (arch.isImplementedInPhase1) {
                                        selectedArch = arch
                                    }
                                },
                                label = {
                                    Text(
                                        text = if (arch.isImplementedInPhase1) arch.displayName else "${arch.displayName} (Planned)",
                                        fontSize = 10.sp
                                    )
                                },
                                enabled = arch.isImplementedInPhase1,
                                modifier = Modifier.testTag("chip_arch_${arch.name}")
                            )
                        }
                    }
                }

                item {
                    Text("Guest OS Template", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val osOptions = listOf("Ubuntu 24.04 ARM64", "Debian 12 ARM64", "Linux ARM64 Generic", "Windows 11 ARM64 (Future)")
                        osOptions.chunked(2).forEach { row ->
                            Column(modifier = Modifier.weight(1f)) {
                                row.forEach { option ->
                                    val isSelected = osType == option
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { osType = option },
                                        label = { Text(option, fontSize = 10.sp) },
                                        modifier = Modifier.fillMaxWidth().testTag("chip_os_$option")
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text("vCPU Cores: $cpuCores", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(2, 4, 6, 8).forEach { cores ->
                            val isSelected = cpuCores == cores
                            Button(
                                onClick = { cpuCores = cores },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF161B21),
                                    contentColor = if (isSelected) Color.Black else Color.White
                                ),
                                modifier = Modifier.weight(1f).testTag("btn_cores_$cores")
                            ) {
                                Text(cores.toString(), fontSize = 12.sp)
                            }
                        }
                    }
                }

                item {
                    val stats = remember { memoryManager.getHostMemoryStats() }
                    
                    Surface(
                        color = Color(0xFF0F141A),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E2833)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Host Physical RAM", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                Text("${stats.totalMb} MB Total (${stats.availableMb} MB Free)", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Guest RAM Allocation", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            512 to "512M",
                            1024 to "1G",
                            2048 to "2G",
                            3072 to "3G",
                            4096 to "4G",
                            6144 to "6G",
                            7168 to "7G",
                            8192 to "8G"
                        ).forEach { (ram, label) ->
                            val isSelected = ramSizeMb == ram
                            val optionSafety = memoryManager.getMemorySafetyRecommendation(ram)
                            val isDanger = optionSafety is MemoryManager.SafetyResult.Danger

                            Button(
                                onClick = { ramSizeMb = ram },
                                enabled = !isDanger || isSelected,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) {
                                        if (isDanger) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                                    } else {
                                        Color(0xFF161B21)
                                    },
                                    contentColor = if (isSelected) {
                                        if (isDanger) Color.White else Color.Black
                                    } else {
                                        if (isDanger) Color.Gray else Color.White
                                    },
                                    disabledContainerColor = Color(0xFF101418),
                                    disabledContentColor = Color.DarkGray
                                ),
                                contentPadding = PaddingValues(horizontal = 2.dp),
                                modifier = Modifier.weight(1f).testTag("btn_ram_$ram")
                            ) {
                                Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    
                    when (safetyResult) {
                        is MemoryManager.SafetyResult.Danger -> {
                            Surface(
                                color = Color(0x22FF5252),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0x55FF5252)),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(safetyResult.message, color = Color(0xFFFF8A80), fontSize = 10.sp, lineHeight = 13.sp)
                                }
                            }
                        }
                        is MemoryManager.SafetyResult.Warning -> {
                            Surface(
                                color = Color(0x22FFA000),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0x55FFA000)),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(safetyResult.message, color = Color(0xFFFFD54F), fontSize = 10.sp, lineHeight = 13.sp)
                                }
                            }
                        }
                        is MemoryManager.SafetyResult.Safe -> {
                            Text(safetyResult.details, color = Color(0xFF00E676), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp), fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                item {
                    Text("Virtual Disk Storage Sector size: $diskSizeGb GB", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Slider(
                        value = diskSizeGb.toFloat(),
                        onValueChange = { diskSizeGb = it.toInt() },
                        valueRange = 5f..120f,
                        steps = 23,
                        modifier = Modifier.testTag("slider_disk")
                    )
                }

                item {
                    TextButton(
                        onClick = { showAdvancedBoot = !showAdvancedBoot },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = if (showAdvancedBoot) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand Advanced",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showAdvancedBoot) "Hide Linux Boot & Userspace Settings" else "Linux Boot & Userspace Settings (Kernel, Initrd, Cmdline)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (showAdvancedBoot) {
                    item {
                        Surface(
                            color = Color(0xFF0A0E13),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF1E2833)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("ARM64 Linux Kernel, Initramfs & Guest Disk Importer", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text("Import authentic ARM64 vmlinuz-generic, initrd-generic, or raw guest disk image (.img) directly into private app storage:", fontSize = 10.sp, color = Color.Gray)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { kernelPickerLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.weight(1f).testTag("btn_import_kernel")
                                    ) {
                                        Icon(Icons.Default.UploadFile, contentDescription = "Import Kernel", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pick Kernel", fontSize = 11.sp)
                                    }

                                    OutlinedButton(
                                        onClick = { initrdPickerLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.weight(1f).testTag("btn_import_initrd")
                                    ) {
                                        Icon(Icons.Default.Archive, contentDescription = "Import Initrd", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pick Initrd", fontSize = 11.sp)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { diskPickerLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.fillMaxWidth().testTag("btn_import_disk")
                                    ) {
                                        Icon(Icons.Default.Storage, contentDescription = "Import Disk Image", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Import Custom MBR Guest Disk (.img)", fontSize = 11.sp)
                                    }
                                }

                                importStatusMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        fontSize = 10.sp,
                                        color = if (msg.contains("rejected", ignoreCase = true) || msg.contains("error", ignoreCase = true) || msg.contains("failed", ignoreCase = true)) Color(0xFFFF5252) else Color(0xFF00E676),
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 13.sp
                                    )
                                }

                                HorizontalDivider(color = Color(0xFF1E2833))

                                Text("ARM64 Linux Kernel Image (Image / vmlinuz)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = kernelImagePath,
                                    onValueChange = { kernelImagePath = it },
                                    placeholder = { Text("Path to verified ARM64 Image / vmlinuz", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth().testTag("input_kernel_path"),
                                    singleLine = true
                                )
                                if (kernelImagePath.endsWith(".iso", ignoreCase = true) || kernelImagePath.contains(".iso", ignoreCase = true)) {
                                    Text(
                                        text = "❌ ISO image detected: " + UbuntuGuestManager.explainIsoRestriction(),
                                        fontSize = 10.sp,
                                        color = Color(0xFFFF5252),
                                        lineHeight = 13.sp
                                    )
                                } else if (kernelImagePath.isNotBlank() && !viewModel.diskBackend.isGuestImagePathAuthorized(kernelImagePath)) {
                                    Text(
                                        text = "⚠️ Security Warning: Path is outside app sandbox. To protect host files, only app-scoped storage is permitted.",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFF5252),
                                        lineHeight = 13.sp
                                    )
                                }

                                Text("Initramfs / Rootfs Path (Optional)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = initramfsPath,
                                    onValueChange = { initramfsPath = it },
                                    placeholder = { Text("/path/to/initramfs.cpio.gz (optional)", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth().testTag("input_initramfs_path"),
                                    singleLine = true
                                )
                                if (initramfsPath.isNotBlank() && !viewModel.diskBackend.isGuestImagePathAuthorized(initramfsPath)) {
                                    Text(
                                        text = "⚠️ Security Warning: Path is outside app sandbox. To protect host files, only app-scoped storage is permitted.",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFF5252),
                                        lineHeight = 13.sp
                                    )
                                }

                                Text("Custom MBR Guest Disk (.img) Path (Optional)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = diskImagePath,
                                    onValueChange = { diskImagePath = it },
                                    placeholder = { Text("Path to imported custom MBR guest disk", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth().testTag("input_disk_image_path"),
                                    singleLine = true
                                )
                                if (diskImagePath.isNotBlank() && !viewModel.diskBackend.isPathAuthorized(diskImagePath) && !viewModel.diskBackend.isGuestImagePathAuthorized(diskImagePath)) {
                                    Text(
                                        text = "⚠️ Security Warning: Path is outside app sandbox. To protect host files, only app-scoped storage is permitted.",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFF5252),
                                        lineHeight = 13.sp
                                    )
                                }

                                Text("Kernel Command Line (bootargs)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = kernelCmdline,
                                    onValueChange = { kernelCmdline = it },
                                    modifier = Modifier.fillMaxWidth().testTag("input_cmdline"),
                                    singleLine = true
                                )

                                Text("Console Device", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = consoleDevice,
                                    onValueChange = { consoleDevice = it },
                                    modifier = Modifier.fillMaxWidth().testTag("input_console_dev"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider(color = Color(0xFF222A33))
                }

                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Prefer Hardware Virtualization (ARM64 KVM)", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (viewModel.hostArchitecture == HostArchitecture.ARM64 && viewModel.isKvmSupported)
                                        "⚡ Hardware virtualization is available & preferred on this ARM64 device"
                                    else
                                        "ℹ️ Hardware acceleration unexposed (${viewModel.kvmReason}); supported ARM64 software emulation backend will be used",
                                    fontSize = 10.sp,
                                    color = if (viewModel.hostArchitecture == HostArchitecture.ARM64 && viewModel.isKvmSupported) Color(0xFF00E676) else Color(0xFFFFB300)
                                )
                            }
                            Switch(
                                checked = useHardwareVirt,
                                onCheckedChange = { useHardwareVirt = it },
                                modifier = Modifier.testTag("switch_virt")
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Virtual Network Adapter (VirtIO-Net)", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Not implemented (Interface prepared for NAT, Host-to-Guest & Guest-to-Internet)", fontSize = 10.sp, color = Color(0xFFFFB300))
                        }
                        Switch(
                            checked = networkEnabled,
                            onCheckedChange = { networkEnabled = it },
                            modifier = Modifier.testTag("switch_net")
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Virtual UART Serial Port", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Connect console output stream interface", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = serialEnabled,
                            onCheckedChange = { serialEnabled = it },
                            modifier = Modifier.testTag("switch_serial")
                        )
                    }
                }
            }
        },
        confirmButton = {
            val isMemSafe = safetyResult !is MemoryManager.SafetyResult.Danger
            val isKernelAuthorized = kernelImagePath.isBlank() || viewModel.diskBackend.isGuestImagePathAuthorized(kernelImagePath)
            val isInitrdAuthorized = initramfsPath.isBlank() || viewModel.diskBackend.isGuestImagePathAuthorized(initramfsPath)
            val isDiskAuthorized = diskImagePath.isBlank() || viewModel.diskBackend.isPathAuthorized(diskImagePath) || viewModel.diskBackend.isGuestImagePathAuthorized(diskImagePath)
            val arePathsAuthorized = isKernelAuthorized && isInitrdAuthorized && isDiskAuthorized
            val canSave = isMemSafe && arePathsAuthorized && name.isNotBlank()

            Button(
                onClick = {
                    onSave(
                        name,
                        osType,
                        selectedArch,
                        cpuCores,
                        ramSizeMb,
                        diskSizeGb,
                        useHardwareVirt,
                        networkEnabled,
                        serialEnabled,
                        kernelImagePath,
                        initramfsPath,
                        kernelCmdline,
                        consoleDevice,
                        diskImagePath
                    )
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canSave) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                    contentColor = if (canSave) Color.Black else Color.Gray
                ),
                modifier = Modifier.testTag("btn_save_config")
            ) {
                Text(
                    when {
                        !isMemSafe -> "Unsafe Memory Size"
                        !arePathsAuthorized -> "Security: Path Outside Sandbox"
                        name.isBlank() -> "Name Required"
                        else -> "Save Hardware"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun VMErrorDialog(
    error: VMError,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Error Diagnostic",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error.category.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Occurred at ${error.formattedTimestamp}",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Summary Box
                Surface(
                    color = Color(0xFF1E1010),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF4A1E1E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SUMMARY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF8A80),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = error.summary,
                            fontSize = 12.sp,
                            color = Color(0xFFEEEEEE),
                            lineHeight = 16.sp
                        )
                    }
                }

                // Technical Diagnostics Box
                Surface(
                    color = Color(0xFF0C1015),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF1F2937)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TECHNICAL DETAILS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                fontFamily = FontFamily.Monospace
                            )
                            TextButton(
                                onClick = {
                                    val fullLog = buildString {
                                        appendLine("--- MOBILEVM DIAGNOSTIC REPORT ---")
                                        appendLine("Category: ${error.category.name} (${error.category.displayTitle})")
                                        appendLine("Timestamp: ${error.formattedTimestamp}")
                                        appendLine("Summary: ${error.summary}")
                                        appendLine("Details: ${error.technicalDetails}")
                                        appendLine("Remedy: ${error.suggestedRemedy}")
                                    }
                                    clipboardManager.setText(AnnotatedString(fullLog))
                                    copied = true
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(
                                    text = if (copied) "Copied!" else "Copy Log",
                                    fontSize = 10.sp,
                                    color = if (copied) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = error.technicalDetails,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFCCCCCC),
                            lineHeight = 15.sp
                        )
                    }
                }

                // Suggested Remedy Box
                Surface(
                    color = Color(0xFF101B14),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E3824)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SUGGESTED ACTION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF69F0AE),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = error.suggestedRemedy,
                            fontSize = 12.sp,
                            color = Color(0xFFE0E0E0),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
                modifier = Modifier.testTag("btn_dismiss_error_dialog")
            ) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
fun SecurityStatusCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F151B)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF1E2833)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security Shield",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SECURITY & SANDBOX STATUS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace
                    )
                }

                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (expanded) "Less" else "Details",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "✓ 100% Unprivileged User-Space Sandbox • No Root • Host Files Protected",
                fontSize = 11.sp,
                color = Color.LightGray
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF1E2833), modifier = Modifier.padding(bottom = 4.dp))

                    SecurityBullet(
                        icon = Icons.Default.Lock,
                        title = "Zero Root Requirement",
                        desc = "Operates entirely within the standard Android user sandbox. Does not require Superuser, Magisk, or KernelSU privileges."
                    )
                    SecurityBullet(
                        icon = Icons.Default.Security,
                        title = "No Bootloader or Firmware Alteration",
                        desc = "Bootloader unlocking, custom recovery, and firmware/kernel flashing are neither required nor attempted."
                    )
                    SecurityBullet(
                        icon = Icons.Default.Storage,
                        title = "Scoped Disk Sandboxing",
                        desc = "Virtual machine disk operations are strictly restricted to the app-private container. Accidental overwrite of host system or user files is strictly blocked."
                    )
                    SecurityBullet(
                        icon = Icons.Default.DeveloperBoard,
                        title = "System Partition Protection",
                        desc = "Android system partitions (/system, /vendor, /apex, /data) remain read-only and completely untouched."
                    )
                    SecurityBullet(
                        icon = Icons.Default.Usb,
                        title = "Supported Android Permission Model",
                        desc = "OTG hardware passthrough uses Android's official UsbManager with explicit user runtime approval prompts."
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityBullet(icon: ImageVector, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF00E5FF),
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = desc,
                fontSize = 10.sp,
                color = Color.Gray,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun ConfirmDeleteVMDialog(
    config: VMConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = "Confirm Deletion",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Delete Virtual Machine?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Are you sure you want to permanently delete \"${config.name}\"?",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Surface(
                    color = Color(0xFF201010),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFF4A1E1E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "DESTRUCTIVE DISK ACTION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF8A80),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "The virtual disk file (${config.diskSizeGb} GB) will be permanently erased from storage:\n${config.diskImagePath}\n\nHost system files and Android user data are unaffected.",
                            fontSize = 11.sp,
                            color = Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.White),
                modifier = Modifier.testTag("btn_confirm_delete_vm")
            ) {
                Text("Delete Permanently", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConfirmRecreateDiskDialog(
    config: VMConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Confirm Re-create Disk",
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Re-create Virtual Disk?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This operation will format and re-initialize the virtual disk image for \"${config.name}\":",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Surface(
                    color = Color(0xFF201B10),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFF4A381E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "DATA LOSS WARNING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD54F),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All partitions and guest files inside the ${config.diskSizeGb} GB raw image will be completely overwritten with a fresh MBR partition table.",
                            fontSize = 11.sp,
                            color = Color(0xFFE0E0E0),
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                modifier = Modifier.testTag("btn_confirm_recreate_disk")
            ) {
                Text("Overwrite & Format", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Global Settings & Configurations Dialog.
 * Consolidated all features and configuration parameters available across the app into a single organized screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsDialog(
    onDismiss: () -> Unit,
    viewModel: VMViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Virtualization, 1: USB / Devices, 2: Security, 3: Display

    // Settings State
    var preferKvm by remember { mutableStateOf(true) }
    var networkModeNat by remember { mutableStateOf(true) }
    var enableSerialConsole by remember { mutableStateOf(true) }
    var autoRouteDevices by remember { mutableStateOf(true) }
    var enableMassStoragePassthrough by remember { mutableStateOf(true) }
    var doubleBufferedCanvas by remember { mutableStateOf(true) }
    var secureBootEnabled by remember { mutableStateOf(true) }
    var tpm2Enabled by remember { mutableStateOf(true) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0C0F12) // Matches DeepGrayBg
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Text("Global VM & Hypervisor Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF161B21)
                        ),
                        actions = {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("Save Settings", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    )
                },
                bottomBar = {
                    // Small visual brand indicator at bottom
                    Surface(
                        color = Color(0xFF0C0F12),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "MobileVM Hypervisor v2.10 | Active Configuration Node",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        )
                    }
                },
                containerColor = Color(0xFF0C0F12)
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Tab Selection Row
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF161B21),
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Core", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("USB", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Security", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("Display", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        color = Color(0xFF161B21),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF232D38)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (selectedTab) {
                                0 -> {
                                    // Core Virtualization Settings
                                    Text("Virtualization Core & Emulation", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Hardware Virtualization (ARM64 KVM)", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Bypasses software emulation completely. Requires rooted pKVM nodes to execute CPU instructions at near-native physical host speed.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = preferKvm, onCheckedChange = { preferKvm = it })
                                    }

                                    HorizontalDivider(color = Color(0xFF232D38))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Virtual Network Adapter (VirtIO-Net)", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Enables full-duplex guest networking (NAT with secure Host-to-Guest network routing capabilities).", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = networkModeNat, onCheckedChange = { networkModeNat = it })
                                    }

                                    HorizontalDivider(color = Color(0xFF232D38))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Virtual UART PL011 Serial Port", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Binds UART console to virtual serial monitor tab to support serial input and output streams.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = enableSerialConsole, onCheckedChange = { enableSerialConsole = it })
                                    }
                                }

                                1 -> {
                                    // USB / Devices Settings
                                    Text("USB Bus & OTG Devices", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Auto-Route Keyboard & Mouse", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Automatically claim physical OTG USB input devices on connection and route input directly to VM console.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = autoRouteDevices, onCheckedChange = { autoRouteDevices = it })
                                    }

                                    HorizontalDivider(color = Color(0xFF232D38))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("USB Storage Mass Passthrough", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Mount raw external USB storage drives and flash disks directly into VM as guest physical block devices.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = enableMassStoragePassthrough, onCheckedChange = { enableMassStoragePassthrough = it })
                                    }
                                }

                                2 -> {
                                    // Security Settings
                                    Text("UEFI NVRAM & Security Subsystems", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Enable UEFI Secure Boot", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Validates guest EFI bootloader binary signatures against Secure Boot database certificates before boot.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = secureBootEnabled, onCheckedChange = { secureBootEnabled = it })
                                    }

                                    HorizontalDivider(color = Color(0xFF232D38))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Virtual TPM 2.0 CRB Interface", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Required for physical hardware capability and modern OS (like Windows 11) setup and integrity checks.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = tpm2Enabled, onCheckedChange = { tpm2Enabled = it })
                                    }
                                }

                                3 -> {
                                    // Display & Canvas Settings
                                    Text("Graphics Scanout & Refresh Specs", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Double-Buffered GPUBitmap Canvas", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Eliminates screen tearing and artifacts during fast active guest screen scans and GUI rendering updates.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Switch(checked = doubleBufferedCanvas, onCheckedChange = { doubleBufferedCanvas = it })
                                    }

                                    HorizontalDivider(color = Color(0xFF232D38))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Max Scanout Refresh Rate", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("Caps guest graphics refresh frequency to conserve Host battery and optimize rendering overhead.", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF232D38))
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text("60 Hz", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


