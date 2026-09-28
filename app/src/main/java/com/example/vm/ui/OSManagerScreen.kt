package com.example.vm.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.vm.guest.os.*
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Kali Linux", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_kali"))
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Debian", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_debian"))
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Alpine", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_alpine"))
                Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text("Fedora", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_fedora"))
                Tab(selected = selectedTab == 5, onClick = { selectedTab = 5 }, text = { Text("Enterprise (Alma/Rocky)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_enterprise"))
                Tab(selected = selectedTab == 6, onClick = { selectedTab = 6 }, text = { Text("Arch Linux", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_arch"))
                Tab(selected = selectedTab == 7, onClick = { selectedTab = 7 }, text = { Text("openSUSE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_opensuse"))
                Tab(selected = selectedTab == 8, onClick = { selectedTab = 8 }, text = { Text("My Images", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_my_images"))
                Tab(selected = selectedTab == 9, onClick = { selectedTab = 9 }, text = { Text("Downloads", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_downloads"))
                Tab(selected = selectedTab == 10, onClick = { selectedTab = 10 }, text = { Text("Import", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_import"))
                Tab(selected = selectedTab == 11, onClick = { selectedTab = 11 }, text = { Text("Storage", fontSize = 12.sp, fontWeight = FontWeight.Bold) }, modifier = Modifier.testTag("tab_storage"))
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

                    1 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Kali Linux" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    2 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Debian" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    3 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Alpine" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    4 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Fedora" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    5 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "AlmaLinux" || it.osCategory == "Rocky Linux" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    6 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "Arch Linux" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    7 -> CategoryTabContent(
                        manifests = allManifests.filter { it.osCategory == "openSUSE" },
                        progressMap = progressMap,
                        downloadManager = downloadManager,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    8 -> MyImagesTabContent(
                        manifests = allManifests,
                        onCreateVm = { manifest ->
                            wizardTargetManifest = manifest
                            showWizardDialog = true
                        }
                    )

                    9 -> DownloadManagerTabContent(
                        progressMap = progressMap,
                        downloadManager = downloadManager
                    )

                    10 -> ImportImageTabContent(
                        onImported = { importedInfo ->
                            Toast.makeText(context, "Imported ${importedInfo.fileName} (${importedInfo.format})", Toast.LENGTH_SHORT).show()
                            selectedTab = 8 // Switch to My Images
                        }
                    )

                    11 -> StorageManagerTabContent()
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

    if (verifiedManifests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.DownloadDone, contentDescription = "Empty", tint = Color.Gray, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("No verified downloaded OS images yet.", fontSize = 14.sp, color = Color.Gray)
                Text("Download Ubuntu or Kali Linux from the tabs above.", fontSize = 12.sp, color = Color.DarkGray)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                Button(
                    onClick = {
                        Toast.makeText(context, "Select an ARM64 image from device file storage.", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().testTag("btn_browse_custom_image")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse", tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Local File", color = Color.Black, fontWeight = FontWeight.Bold)
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
