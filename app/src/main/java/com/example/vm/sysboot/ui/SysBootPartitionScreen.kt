package com.example.vm.sysboot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vm.sysboot.model.EspFileItem
import com.example.vm.sysboot.model.PartitionInfo
import com.example.vm.sysboot.viewmodel.SysBootViewModel

@Composable
fun SysBootPartitionScreen(viewModel: SysBootViewModel) {
    val partitions = viewModel.partitionList
    val espFiles = viewModel.espFiles
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedFilePreview by remember { mutableStateOf<EspFileItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Storage & ESP Partition Manager",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "GPT Partition Map & EFI System Partition (FAT32) Explorer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SysBootStatusBadge(
                text = "GPT ACTIVE",
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                contentColor = MaterialTheme.colorScheme.primary
            )
        }

        // Tabs
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text("GPT Partition Map") },
                modifier = Modifier.testTag("tab_partition_map")
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text("ESP File Browser") },
                modifier = Modifier.testTag("tab_esp_browser")
            )
        }

        if (selectedTabIndex == 0) {
            Text(
                text = "Visual NVMe Disk Allocation Map (120 GB)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    Color(0xFF64748B),
                    Color(0xFF00A4EF),
                    Color(0xFFE95420),
                    Color(0xFFF59E0B)
                )
                partitions.forEachIndexed { idx, p ->
                    val weight = (p.sizeGb / 120.0).toFloat().coerceAtLeast(0.02f)
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxSize()
                            .background(colors[idx % colors.size])
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = p.fileSystem,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(partitions) { p ->
                    PartitionCard(p)
                }
            }
        } else {
            Text(
                text = "ESP Partition Files (/boot/efi)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(espFiles) { fileItem ->
                    EspFileRow(
                        fileItem = fileItem,
                        onClick = {
                            if (!fileItem.isDirectory) {
                                selectedFilePreview = fileItem
                            }
                        }
                    )
                }
            }
            selectedFilePreview?.let { preview ->
                Spacer(modifier = Modifier.height(12.dp))
                SysBootTerminalWindowCard(
                    title = "preview_${preview.name}",
                    logs = listOf(
                        "File: ${preview.path}",
                        "Size: ${preview.sizeBytes / 1024} KB | Type: ${preview.fileType}",
                        "--- File Header / Content ---",
                        preview.contentPreview ?: "Binary EFI Image file header validated."
                    ),
                    maxHeightDp = 180
                )
            }
        }
    }
}

@Composable
private fun PartitionCard(p: PartitionInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("partition_card_${p.index}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = p.name,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "p${p.index}: ${p.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                SysBootStatusBadge(
                    text = p.fileSystem,
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Size: ${p.sizeGb} GB (Used: ${p.usedGb} GB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Mount: ${p.mountPoint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (p.usedGb / p.sizeGb).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "GUID: ${p.guid}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun EspFileRow(
    fileItem: EspFileItem,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .testTag("esp_file_${fileItem.name}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (fileItem.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = fileItem.name,
                    tint = if (fileItem.isDirectory) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = fileItem.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = fileItem.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SysBootStatusBadge(
                text = fileItem.fileType,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
