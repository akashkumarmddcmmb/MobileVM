package com.example.vm.sysboot.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.vm.sysboot.data.DiagnosticResultEntity
import com.example.vm.sysboot.viewmodel.SysBootViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SysBootDiagnosticsScreen(viewModel: SysBootViewModel) {
    val diagResults by viewModel.diagnosticResults.collectAsState()
    val diagRunState by viewModel.diagRunState.collectAsState()
    val categories = listOf("ALL", "CPU", "RAM", "NVME", "TPM", "ACPI")
    var selectedCategory by remember { mutableStateOf("ALL") }

    val filteredResults = if (selectedCategory == "ALL") {
        diagResults
    } else {
        diagResults.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }

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
                    text = "Hardware & Memory Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Stress test ARM64 CPU, RAM pattern, VirtIO SMART & TPM 2.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SysBootStatusBadge(
                text = "SYSTEM OK",
                containerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                contentColor = Color(0xFF10B981)
            )
        }

        // Action Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("run_diag_banner")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Execute System Diagnostic Suite",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Runs low-level memory pattern verification, vector unit stress, and ACPI table consistency checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val runCat = if (selectedCategory == "ALL") "CPU" else selectedCategory
                        viewModel.runDiagnosticsSuite(runCat)
                    },
                    enabled = !diagRunState.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("start_diag_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (diagRunState.isRunning) "Running Test Suite..." else "Run ${if (selectedCategory == "ALL") "Comprehensive" else selectedCategory} Test",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (diagRunState.isRunning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { diagRunState.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = diagRunState.currentLog,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Live Output
        if (diagRunState.liveOutput.isNotEmpty()) {
            SysBootTerminalWindowCard(
                title = "diagnostics_test_runner.log",
                logs = diagRunState.liveOutput,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            items(categories) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat) },
                    modifier = Modifier.testTag("diag_filter_$cat")
                )
            }
        }

        // History
        Text(
            text = "Diagnostic History & Logs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (filteredResults.isEmpty()) {
                item {
                    Text(
                        text = "No diagnostic records found for $selectedCategory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(filteredResults, key = { it.id }) { item ->
                    DiagnosticResultCard(item)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticResultCard(item: DiagnosticResultEntity) {
    val statusColor = when (item.status) {
        "PASSED" -> Color(0xFF10B981)
        "WARNING" -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.error
    }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("diag_result_item_${item.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (item.status == "PASSED") Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = item.status,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.testName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                SysBootStatusBadge(
                    text = item.status,
                    containerColor = statusColor.copy(alpha = 0.2f),
                    contentColor = statusColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Metric: ${item.scoreOrMetric}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.detailsLog,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = dateFormatter.format(Date(item.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
