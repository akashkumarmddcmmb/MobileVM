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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vm.sysboot.viewmodel.SysBootViewModel

@Composable
fun SysBootRecoveryHubScreen(viewModel: SysBootViewModel) {
    val recoveryState by viewModel.recoveryState.collectAsState()

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
                    text = "Recovery & Startup Repair Hub",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Automated BCD Repair, EFI Rebuild & GPT Recovery Tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SysBootStatusBadge(
                text = "Recovery.efi",
                containerColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                contentColor = Color(0xFFF59E0B)
            )
        }

        // Live Wizard Output Card
        if (recoveryState.isRunning || recoveryState.isCompleted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = recoveryState.taskTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = recoveryState.stepText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { recoveryState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SysBootTerminalWindowCard(
                        title = "recovery_wizard_execution.log",
                        logs = recoveryState.resultLog,
                        maxHeightDp = 180
                    )
                    if (recoveryState.isCompleted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.dismissRecoveryState() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Dismiss Wizard")
                        }
                    }
                }
            }
        }

        // Repair Tool Cards
        Text(
            text = "Automated Repair Wizards",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RepairToolCard(
                title = "Rebuild Windows BCD Store",
                description = "Scans C:\\Windows for BCD hives and runs bcdboot /s S: /f UEFI to fix '0xc000000e' boot errors.",
                icon = Icons.Default.Build,
                buttonText = "Rebuild BCD",
                onClick = { viewModel.runRecoveryRepair("BCD_REPAIR") },
                enabled = !recoveryState.isRunning,
                testTag = "repair_bcd_btn"
            )
            RepairToolCard(
                title = "Rebuild EFI System Partition (ESP)",
                description = "Recreates /EFI/BOOT/BOOTAA64.EFI and restores MobileVMBootManager.efi stage-2 binaries.",
                icon = Icons.Default.Restore,
                buttonText = "Rebuild EFI",
                onClick = { viewModel.runRecoveryRepair("EFI_REBUILD") },
                enabled = !recoveryState.isRunning,
                testTag = "repair_efi_btn"
            )
            RepairToolCard(
                title = "Filesystem & GPT Header Check",
                description = "Validates Primary/Secondary GPT partition headers and performs FAT32/NTFS cluster scan.",
                icon = Icons.Default.Storage,
                buttonText = "Scan & Repair",
                onClick = { viewModel.runRecoveryRepair("GPT_REPAIR") },
                enabled = !recoveryState.isRunning,
                testTag = "repair_gpt_btn"
            )
        }
    }
}

@Composable
private fun RepairToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean,
    testTag: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag(testTag)
            ) {
                Text(buttonText, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
