package com.example.vm.sysboot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import com.example.vm.sysboot.model.AcpiTable
import com.example.vm.sysboot.viewmodel.SysBootViewModel

@Composable
fun SysBootAcpiFirmwareScreen(viewModel: SysBootViewModel) {
    val acpiTables = viewModel.acpiTables
    var selectedTable by remember { mutableStateOf(acpiTables.first()) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

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
                    text = "ACPI Tables & Firmware Inspector",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "AML/ASL Disassembler, DSDT, MADT, FADT & Platform YAML configs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SysBootStatusBadge(
                text = "ACPI 6.5",
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
                text = { Text("ACPI Table Disassembler") },
                modifier = Modifier.testTag("tab_acpi_tables")
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text("Platform Config YAML") },
                modifier = Modifier.testTag("tab_acpi_yaml")
            )
        }

        if (selectedTabIndex == 0) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(0.45f)
            ) {
                items(acpiTables) { tbl ->
                    AcpiRow(
                        table = tbl,
                        isSelected = tbl.signature == selectedTable.signature,
                        onClick = { selectedTable = tbl }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "${selectedTable.signature} ASL Disassembly Code",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            SysBootTerminalWindowCard(
                title = "${selectedTable.signature.lowercase()}.asl",
                logs = selectedTable.aslCodeSnippet.split("\n"),
                modifier = Modifier.weight(0.55f),
                maxHeightDp = 300
            )
        } else {
            Text(
                text = "System Platform YAML Configurations",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SysBootTerminalWindowCard(
                title = "config/platform.yaml",
                logs = listOf(
                    "# MobileVM Platform Specification",
                    "platform:",
                    "  name: MobileVM Consolidated ARM64 Hypervisor",
                    "  soc: ARM Cortex-A78 / Snapdragon / Dimensity / Tensor",
                    "  ram_type: LPDDR5-5600",
                    "  nvme_controller: VirtIO Block Device",
                    "  tpm_version: 2.0 (CRB MMIO)",
                    "  secure_boot: enabled"
                ),
                maxHeightDp = 200,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            SysBootTerminalWindowCard(
                title = "config/boot.yaml",
                logs = listOf(
                    "# MobileVMBootManager EFI Config",
                    "boot:",
                    "  default_os: Windows 11 ARM64",
                    "  timeout: 5",
                    "  show_gui_menu: true",
                    "  theme: dark_cyber"
                ),
                maxHeightDp = 200
            )
        }
    }
}

@Composable
private fun AcpiRow(
    table: AcpiTable,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .testTag("acpi_table_${table.signature}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DeveloperBoard,
                    contentDescription = table.signature,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = table.signature,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = table.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                SysBootStatusBadge(
                    text = "${table.lengthBytes} B",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = table.physicalAddress,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF38BDF8)
                )
            }
        }
    }
}
