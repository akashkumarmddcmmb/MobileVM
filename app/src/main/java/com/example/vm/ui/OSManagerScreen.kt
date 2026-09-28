package com.example.vm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.vm.guest.os.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSManagerScreen(
    downloadManager: OSDownloadManager,
    onBack: () -> Unit,
    onConfigured: (com.example.vm.core.VMConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val progressMap by downloadManager.downloadProgress.collectAsStateWithLifecycle()
    val manifests = remember { OSManifestRegistry.OFFICIAL_MANIFESTS }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OFFICIAL OS MANAGER",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Download legitimate ARM64 operating systems directly into VM storage",
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
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Storage Capacity Overview Card
                val overallCapacity = remember {
                    OSStorageManager.checkStorageCapacity(context, 100L * 1024L * 1024L)
                }
                StorageOverviewCard(capacity = overallCapacity)
            }

            items(manifests, key = { it.id }) { manifest ->
                val progress = progressMap[manifest.id] ?: OSDownloadProgress(manifestId = manifest.id)
                val isInstalled = remember(progress.state) {
                    OSStorageManager.isOsInstalled(context, manifest)
                }

                OSManifestCard(
                    manifest = manifest,
                    progress = progress,
                    isInstalled = isInstalled,
                    onDownload = {
                        downloadManager.startDownload(manifest)
                    },
                    onPause = {
                        downloadManager.pauseDownload(manifest.id)
                    },
                    onResume = {
                        downloadManager.resumeDownload(manifest)
                    },
                    onCancel = {
                        downloadManager.cancelDownload(manifest.id)
                    },
                    onConfigureVM = {
                        val result = OSAutoConfigurator.createConfigurationForManifest(context, manifest)
                        if (result is AutoConfigResult.Success) {
                            onConfigured(result.config)
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF232D38)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("os_card_${manifest.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name, Architecture, Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manifest.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${manifest.version} • ${manifest.architecture}",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Surface(
                    color = if (isInstalled) Color(0x2200E676) else Color(0x22FFA000),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (isInstalled) Color(0x5500E676) else Color(0x55FFA000))
                ) {
                    Text(
                        text = if (isInstalled) "INSTALLED & VERIFIED" else "DOWNLOAD AVAILABLE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isInstalled) Color(0xFF00E676) else Color(0xFFFFB300),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Specs Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SpecItem("Download Size", "${manifest.downloadSizeBytes / (1024 * 1024)} MB")
                SpecItem("RAM Needed", "${manifest.recommendedRamMb} MB")
                SpecItem("Disk Space", "${manifest.minimumStorageGb} GB")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Official Source & Checksum Details
            Surface(
                color = Color(0xFF141A23),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Verified",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Official Source: ${manifest.sourceName}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Checksum: Official SHA-256 Available",
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Compatibility: ${manifest.compatibilityStatus}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (manifest.isBootSupported) Color(0xFF00E676) else Color(0xFFFFB300),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = manifest.copyrightNotice,
                        fontSize = 9.sp,
                        color = Color.Gray
                    )
                }
            }

            // Download Progress Bar (when downloading, verifying, or paused)
            if (progress.state == OSDownloadState.DOWNLOADING ||
                progress.state == OSDownloadState.PAUSED ||
                progress.state == OSDownloadState.VERIFYING ||
                progress.state == OSDownloadState.INSTALLING ||
                progress.state == OSDownloadState.QUEUED ||
                progress.state == OSDownloadState.FAILED
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = progress.statusMessage,
                            fontSize = 11.sp,
                            color = if (progress.state == OSDownloadState.FAILED) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${progress.progressPercent}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { progress.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (progress.state == OSDownloadState.FAILED) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFF232D38)
                    )

                    if (progress.speedBytesPerSec > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Speed: ${progress.speedBytesPerSec / 1024} KB/s",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "ETA: ${progress.estimatedRemainingSeconds}s",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isInstalled) {
                    Button(
                        onClick = onConfigureVM,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_configure_vm_${manifest.id}")
                    ) {
                        Icon(Icons.Default.SettingsSuggest, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure VM & Ready to Boot", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                } else {
                    when (progress.state) {
                        OSDownloadState.DOWNLOADING -> {
                            OutlinedButton(
                                onClick = onPause,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pause")
                            }
                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cancel", color = Color.White)
                            }
                        }
                        OSDownloadState.PAUSED -> {
                            Button(
                                onClick = onResume,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.Black)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Resume", color = Color.Black)
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                        }
                        else -> {
                            Button(
                                onClick = onDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_download_${manifest.id}")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download from Official Source", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpecItem(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
    }
}
