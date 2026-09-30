package com.example.vm.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.vm.guest.linux.LinuxImageProvisioner
import com.example.vm.guest.os.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSManagerScreen(
    downloadManager: OSDownloadManager,
    onBack: () -> Unit,
    onConfigured: (VMConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Ubuntu, 1: Kali, 2: My Images, 3: Downloads, 4: Import, 5: Storage
    val progressMap by downloadManager.downloadProgress.collectAsStateWithLifecycle()
    val allManifests = remember { OSManifestRegistry.OFFICIAL_MANIFESTS }

    var showWizardDialog by remember { mutableStateOf(false) }
    var wizardTargetManifest by remember { mutableStateOf<OSManifest?>(null) }

    if (showWizardDialog) {
        VMCreatorWizardDialog(
            onDismiss = { showWizardDialog = false },
            onSaveConfig = { config ->
                onConfigured(config)
            },
            initialManifest = wizardTargetManifest
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "GUEST OS CENTER",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Official ARM64 Images • Downloads • Verification • Storage",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("os_manager_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
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
            // Navigation Subsections TabRow
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0F141C),
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth().testTag("os_manager_tab_row")
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Ubuntu", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_ubuntu"))
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Windows ARM64", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_windows"))
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Kali Linux", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_kali"))
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Debian", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_debian"))
                Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text("Alpine", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_alpine"))
                Tab(selected = selectedTab == 5, onClick = { selectedTab = 5 }, text = { Text("Fedora", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_fedora"))
                Tab(selected = selectedTab == 6, onClick = { selectedTab = 6 }, text = { Text("Enterprise (Alma/Rocky)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_enterprise"))
                Tab(selected = selectedTab == 7, onClick = { selectedTab = 7 }, text = { Text("Arch Linux", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_arch"))
                Tab(selected = selectedTab == 8, onClick = { selectedTab = 8 }, text = { Text("openSUSE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_opensuse"))
                Tab(selected = selectedTab == 9, onClick = { selectedTab = 9 }, text = { Text("My Images", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_my_images"))
                Tab(selected = selectedTab == 10, onClick = { selectedTab = 10 }, text = { Text("Downloads", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_downloads"))
                Tab(selected = selectedTab == 11, onClick = { selectedTab = 11 }, text = { Text("Import", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_import"))
                Tab(selected = selectedTab == 12, onClick = { selectedTab = 12 }, text = { Text("Storage", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_storage"))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                when (selectedTab) {
                    0 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Ubuntu" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    1 -> WindowsARM64TabContent(
                        onCreateWindowsVm = { winConfig ->
                            onConfigured(winConfig)
                        }
                    )

                    2 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Kali Linux" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    3 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Debian" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    4 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Alpine" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    5 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Fedora" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    6 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "AlmaLinux" || it.osCategory == "Rocky Linux" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    7 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Arch Linux" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    8 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "openSUSE" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    9 -> MyImagesTabContent(
                        manifests = allManifests,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    10 -> DownloadManagerTabContent(
                        progressMap = progressMap,
                        downloadManager = downloadManager
                    )

                    11 -> ImportImageTabContent(
                        onImported = { importedInfo ->
                            Toast.makeText(context, "Imported ${importedInfo.fileName} (${importedInfo.format})", Toast.LENGTH_SHORT).show()
                            selectedTab = 9 // Switch to My Images
                        }
                    )

                    12 -> StorageManagerTabContent()
                }
            }
        }
    }
}

@Composable
fun StorageOverviewCard(capacity: StorageCapacityReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF232D38)),
        modifier = Modifier.fillMaxWidth().testTag("storage_capacity_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = "Storage",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DEVICE STORAGE ALLOCATION",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = if (capacity.isSufficient) Color(0x2200E676) else Color(0x22FF5252),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (capacity.isSufficient) "SUFFICIENT SPACE" else "LOW STORAGE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (capacity.isSufficient) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Available Space", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "${capacity.availableBytes / (1024 * 1024)} MB",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column {
                    Text("Safety Margin", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "${capacity.safetyMarginBytes / (1024 * 1024)} MB",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF81D4FA),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column {
                    Text("Storage Type", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "Private App Storage",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryTabContent(
    manifests: List<OSManifest>,
    progressMap: Map<String, OSDownloadProgress>,
    downloadManager: OSDownloadManager,
    onCreateVm: (OSManifest) -> Unit
) {
    val context = LocalContext.current
    if (manifests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No official manifests available for this distribution.", fontSize = 13.sp, color = Color.Gray)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(manifests, key = { it.id }) { manifest ->
                val progress = progressMap[manifest.id] ?: OSDownloadProgress(manifestId = manifest.id)
                val isInstalled = remember(progress.state) { OSStorageManager.isOsInstalled(context, manifest) }

                OSManifestCard(
                    manifest = manifest, progress = progress, isInstalled = isInstalled,
                    onDownload = { downloadManager.startDownload(manifest) },
                    onPause = { downloadManager.pauseDownload(manifest.id) },
                    onResume = { downloadManager.resumeDownload(manifest) },
                    onCancel = { downloadManager.cancelDownload(manifest.id) },
                    onConfigureVM = { onCreateVm(manifest) }
                )
            }
        }
    }
}

@Composable
fun MyImagesTabContent(manifests: List<OSManifest>, onCreateVm: (OSManifest) -> Unit) {
    val context = LocalContext.current
    val verifiedManifests = manifests.filter { OSStorageManager.isOsInstalled(context, it) }
    val isDefaultProvisioned = remember { LinuxImageProvisioner.isDefaultEnvironmentProvisioned(context) }
    val scope = rememberCoroutineScope()
    var isProvisioning by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Built-in Default ARM64 Linux Environment Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isDefaultProvisioned) Color(0xFF00E676) else Color(0xFF232D38)),
                modifier = Modifier.fillMaxWidth().testTag("default_linux_provision_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Default Linux ARM64 Environment", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Surface(
                            color = if (isDefaultProvisioned) Color(0x3300E676) else Color(0x3381D4FA),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                if (isDefaultProvisioned) "READY TO BOOT" else "SANDBOX PROVISION",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDefaultProvisioned) Color(0xFF00E676) else Color(0xFF81D4FA),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Includes authentic ARM64 Linux kernel (vmlinuz), initramfs (/init), and partitioned virtual disk.",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isProvisioning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Button(
                            onClick = {
                                isProvisioning = true
                                scope.launch {
                                    val result = LinuxImageProvisioner.provisionDefaultLinuxEnvironment(context)
                                    isProvisioning = false
                                    if (result is LinuxImageProvisioner.ProvisionResult.Success) {
                                        Toast.makeText(context, "Linux ARM64 environment ready!", Toast.LENGTH_SHORT).show()
                                    } else if (result is LinuxImageProvisioner.ProvisionResult.Failure) {
                                        Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("btn_provision_default_env")
                        ) {
                            Icon(Icons.Default.Build, contentDescription = "Provision", tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isDefaultProvisioned) "Re-Provision Default Linux Environment" else "1-Click Provision Default Linux Environment",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (verifiedManifests.isEmpty() && !isDefaultProvisioned) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DownloadDone, contentDescription = "Empty", tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No verified downloaded OS images yet.", fontSize = 14.sp, color = Color.Gray)
                        Text("Click the button above or download from the distribution tabs.", fontSize = 12.sp, color = Color.DarkGray)
                    }
                }
            }
        } else {
            items(verifiedManifests) { manifest ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF232D38)),
                    modifier = Modifier.fillMaxWidth().testTag("verified_image_card_${manifest.id}")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(manifest.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Surface(color = Color(0x3300E676), shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    "VERIFIED", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676), fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Version: ${manifest.version} • Format: ${manifest.format}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { onCreateVm(manifest) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("btn_create_vm_${manifest.id}")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Create", tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Virtual Machine", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadManagerTabContent(progressMap: Map<String, OSDownloadProgress>, downloadManager: OSDownloadManager) {
    val activeProgressList = progressMap.values.filter { it.state != OSDownloadState.IDLE }

    if (activeProgressList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CloudDownload, contentDescription = "Downloads", tint = Color.Gray, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("No active or queued downloads.", fontSize = 14.sp, color = Color.Gray)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(activeProgressList, key = { it.manifestId }) { progress ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF232D38)),
                    modifier = Modifier.fillMaxWidth().testTag("download_progress_card_${progress.manifestId}")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Manifest ID: ${progress.manifestId}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(progress.state.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress.progressPercent / 100.0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(progress.statusMessage, fontSize = 11.sp, color = Color.LightGray)

                        if (progress.state == OSDownloadState.DOWNLOADING) {
                            Text(
                                "Downloaded: ${progress.bytesDownloaded / (1024 * 1024)} / ${progress.totalBytes / (1024 * 1024)} MB • Speed: ${progress.speedBytesPerSec / 1024} KB/s",
                                fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (progress.state == OSDownloadState.DOWNLOADING) {
                                OutlinedButton(onClick = { downloadManager.pauseDownload(progress.manifestId) }) {
                                    Text("Pause", fontSize = 11.sp)
                                }
                            }
                            OutlinedButton(onClick = { downloadManager.cancelDownload(progress.manifestId) }) {
                                Text("Cancel", fontSize = 11.sp, color = Color(0xFFFF5252))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImportImageTabContent(onImported: (ImportedImageInfo) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isImporting by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importedResult by remember { mutableStateOf<ImportedImageInfo?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                isImporting = true
                importError = null
                importStatus = "Opening selected file..."
                try {
                    val contentResolver = context.contentResolver
                    val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            cursor.getString(nameIndex)
                        } else null
                    } ?: "imported_guest_image.img"

                    importStatus = "Importing $fileName into private sandbox..."
                    val inputStream = contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        withContext(Dispatchers.Main) {
                            isImporting = false
                            importError = "Could not open selected file stream."
                        }
                        return@launch
                    }

                    val isIso = fileName.endsWith(".iso", ignoreCase = true)
                    val result = ImportManager.importImageStream(
                        context = context,
                        inputStream = inputStream,
                        destinationFileName = fileName,
                        targetMode = if (isIso) InstallationMode.MODE_B_ISO_INSTALLER else InstallationMode.MODE_A_PREINSTALLED
                    )

                    withContext(Dispatchers.Main) {
                        isImporting = false
                        when (result) {
                            is ImportResult.Success -> {
                                importedResult = result.imageInfo
                                importStatus = "Successfully imported and verified: ${result.imageInfo.fileName}"
                            }
                            is ImportResult.Failure -> {
                                importError = result.reason
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isImporting = false
                        importError = "Import failed: ${e.localizedMessage ?: "Unknown I/O error"}"
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF232D38)),
            modifier = Modifier.fillMaxWidth().testTag("import_image_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FileOpen, contentDescription = "Import", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Import Custom Guest OS Image", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Supports .img, .iso, .raw, .qcow2 files. Enforces ARM64 architecture check.",
                    fontSize = 12.sp, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isImporting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFF232D38)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = importStatus ?: "Importing...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Button(
                        onClick = {
                            filePickerLauncher.launch("*/*")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag("btn_browse_custom_image")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse", tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Local File (*.img, *.iso, *.raw)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                if (importError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0x33FF5252),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0x66FF5252)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import Rejected", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = importError ?: "", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }

                if (importedResult != null) {
                    val info = importedResult!!
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0x3300E676),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0x6600E676)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("File: ${info.fileName}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Format: ${info.format} • Detected: ${info.detectedArchitecture.displayName}", fontSize = 11.sp, color = Color(0xFF00E676))
                            Text("Size: ${info.sizeBytes / (1024 * 1024)} MB", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { onImported(info) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Create VM With This Image", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StorageManagerTabContent() {
    val context = LocalContext.current
    val capacity = remember { OSStorageManager.checkStorageCapacity(context, 0L) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StorageOverviewCard(capacity = capacity)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF232D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Storage Breakdown", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("App Sandboxed Directory", fontSize = 12.sp, color = Color.Gray)
                    Text("${context.filesDir.canonicalPath}", fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Available Free Headroom", fontSize = 12.sp, color = Color.Gray)
                    Text("${capacity.availableBytes / (1024 * 1024)} MB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                }
            }
        }
    }
}

@Composable
fun OSManifestCard(
    manifest: OSManifest,
    progress: OSDownloadProgress,
    isInstalled: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onConfigureVM: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF232D38)),
        modifier = Modifier.fillMaxWidth().testTag("manifest_card_${manifest.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(manifest.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Surface(
                    color = if (isInstalled) Color(0x3300E676) else Color(0x3381D4FA),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (isInstalled) "INSTALLED" else "AVAILABLE",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (isInstalled) Color(0xFF00E676) else Color(0xFF81D4FA),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("Version: ${manifest.version} • Size: ${manifest.downloadSizeBytes / (1024 * 1024)} MB", fontSize = 12.sp, color = Color.Gray)
            Text(manifest.notes, fontSize = 11.sp, color = Color.LightGray)

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isInstalled -> {
                    Button(
                        onClick = onConfigureVM,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag("btn_configure_${manifest.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Create", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create VM From Image", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                progress.state == OSDownloadState.DOWNLOADING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${progress.progressPercent}% • ${progress.bytesDownloaded / (1024 * 1024)} / ${progress.totalBytes / (1024 * 1024)} MB",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${progress.speedBytesPerSec / 1024} KB/s",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        LinearProgressIndicator(
                            progress = { progress.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFF232D38)
                        )

                        Text(
                            text = progress.statusMessage,
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPause,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pause", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel", fontSize = 11.sp, color = Color(0xFFFF5252))
                            }
                        }
                    }
                }

                progress.state == OSDownloadState.QUEUED ||
                progress.state == OSDownloadState.VERIFYING ||
                progress.state == OSDownloadState.INSTALLING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFF232D38)
                        )
                        Text(
                            text = progress.statusMessage,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel", fontSize = 11.sp, color = Color(0xFFFF5252))
                        }
                    }
                }

                progress.state == OSDownloadState.PAUSED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Download paused at ${progress.progressPercent}%.",
                            fontSize = 12.sp,
                            color = Color(0xFFFFD54F)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onResume,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Resume", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel", fontSize = 12.sp, color = Color(0xFFFF5252))
                            }
                        }
                    }
                }

                progress.state == OSDownloadState.FAILED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = Color(0x33FF5252),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0x66FF5252)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download Error", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = progress.errorMessage ?: progress.statusMessage,
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("btn_retry_${manifest.id}")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Download", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag("btn_download_${manifest.id}")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download OS Image", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun WindowsARM64TabContent(
    onCreateWindowsVm: (VMConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isImporting by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var validatedResult by remember { mutableStateOf<com.example.vm.guest.windows.WindowsValidationResult?>(null) }
    var importedIsoPath by remember { mutableStateOf<String?>(null) }

    val windowsIsoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                isImporting = true
                importError = null
                importStatus = "Validating user-supplied Windows media..."

                try {
                    com.example.vm.guest.iso.ISOManager.takePersistableUriPermission(context, uri)
                    val validation = com.example.vm.guest.windows.WindowsGuestManager.validateWindowsIso(context, uri.toString())

                    if (validation is com.example.vm.guest.windows.WindowsValidationResult.Invalid) {
                        withContext(Dispatchers.Main) {
                            isImporting = false
                            importError = "${validation.reason} ${validation.suggestedAction}"
                        }
                        return@launch
                    }

                    importStatus = "Copying Windows ARM64 ISO to private sandbox storage..."
                    val contentResolver = context.contentResolver
                    val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            cursor.getString(nameIndex)
                        } else null
                    } ?: "windows11_arm64.iso"

                    val targetDir = File(context.filesDir, "guest_os/windows_arm64").apply { mkdirs() }
                    val targetFile = File(targetDir, fileName)

                    contentResolver.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        isImporting = false
                        validatedResult = validation
                        importedIsoPath = targetFile.absolutePath
                        importStatus = "Windows ARM64 Media verified and imported into sandbox (${targetFile.length() / (1024 * 1024)} MB)!"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isImporting = false
                        importError = "Windows media import failed: ${e.localizedMessage ?: "Unknown error"}"
                    }
                }
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Architecture & Specs Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF232D38)),
                modifier = Modifier.fillMaxWidth().testTag("windows_specs_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Window, contentDescription = "Windows", tint = Color(0xFF00A4EF), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Microsoft Windows on ARM (WoA)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Surface(color = Color(0x3300A4EF), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                "UEFI + TPM 2.0",
                                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFF00A4EF), fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Boot legitimate, user-provided Windows 11/10 ARM64 ISO installation media using standard TianoCore EDK2 UEFI firmware, ACPI 6.2 tables, VirtIO-SCSI storage, and Virtual TPM 2.0.",
                        fontSize = 12.sp, color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Minimum RAM", fontSize = 10.sp, color = Color.Gray)
                            Text("4096 MB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Minimum Disk", fontSize = 10.sp, color = Color.Gray)
                            Text("64 GB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("vCPU Cores", fontSize = 10.sp, color = Color.Gray)
                            Text("2+ Cores", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Hypervisor", fontSize = 10.sp, color = Color.Gray)
                            Text("ARM64 KVM", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Compliance & Policy Card
        item {
            Surface(
                color = Color(0xFF0F1722),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = "Legal", tint = Color(0xFF81D4FA), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Legal Policy: MobileVM does not bundle or distribute proprietary Microsoft Windows binaries. You must supply your own legally acquired Windows 11 on ARM ISO image.",
                        fontSize = 11.sp, color = Color.LightGray
                    )
                }
            }
        }

        // Import Media Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141A23)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF232D38)),
                modifier = Modifier.fillMaxWidth().testTag("windows_import_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select User-Provided Windows ARM64 Media", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Select your official Windows 11 ARM64 ISO (e.g. Win11_Arm64_English.iso). File will be verified for ARM64 EFI bootloader before VM setup.",
                        fontSize = 11.sp, color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isImporting) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF00A4EF),
                            trackColor = Color(0xFF232D38)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(importStatus ?: "Importing...", fontSize = 11.sp, color = Color(0xFF00A4EF), fontFamily = FontFamily.Monospace)
                    } else {
                        Button(
                            onClick = { windowsIsoPicker.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A4EF)),
                            modifier = Modifier.fillMaxWidth().testTag("btn_select_windows_iso")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Browse", tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Windows ARM64 ISO Image (*.iso)", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (importError != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = Color(0x33FF5252),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0x66FF5252)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Validation Error", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(importError ?: "", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }

                    if (validatedResult is com.example.vm.guest.windows.WindowsValidationResult.Valid && importedIsoPath != null) {
                        val valid = validatedResult as com.example.vm.guest.windows.WindowsValidationResult.Valid
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = Color(0x3300E676),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0x6600E676)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Verified: ${valid.isoInfo.displayName}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Architecture: ARM64 • EFI Bootloader: ${valid.isoInfo.efiBootLoaderName}", fontSize = 11.sp, color = Color(0xFF00E676))
                                Text("Size: ${valid.isoInfo.sizeBytes / (1024 * 1024)} MB", fontSize = 11.sp, color = Color.LightGray)

                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val disksDir = File(context.filesDir, "app_disks").apply { mkdirs() }
                                        val targetDisk = File(disksDir, "windows11_arm64_disk.img").absolutePath
                                        val config = com.example.vm.guest.windows.WindowsGuestManager.createWindowsVMConfig(
                                            vmName = "Windows 11 ARM64",
                                            isoPath = importedIsoPath!!,
                                            targetDiskPath = targetDisk,
                                            allocatedRamMb = 4096,
                                            allocatedCores = 4,
                                            diskSizeGb = 64
                                        )
                                        onCreateWindowsVm(config)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().testTag("btn_create_windows_vm")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Create", tint = Color.Black)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Create & Configure Windows 11 VM", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
