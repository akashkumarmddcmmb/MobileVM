package com.example.vm.sysboot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SysBootStatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SysBootMetricGaugeCard(
    title: String,
    valueText: String,
    subText: String,
    progress: Float,
    barColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(barColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = barColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = subText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = barColor.copy(alpha = 0.15f),
            )
        }
    }
}

@Composable
fun SysBootTerminalWindowCard(
    title: String,
    logs: List<String>,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 220
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF030712)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
    ) {
        Column {
            // Terminal Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF10B981)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Terminal",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(16.dp)
                )
            }
            // Terminal Logs List
            LazyColumn(
                modifier = Modifier
                    .padding(12.dp)
                    .heightIn(max = maxHeightDp.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = "$ _ Waiting for system execution input...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    items(logs) { logLine ->
                        val textColor = when {
                            logLine.contains("ERROR") || logLine.contains("FAIL") -> Color(0xFFF87171)
                            logLine.contains("SUCCESS") || logLine.contains("PASS") -> Color(0xFF10B981)
                            logLine.contains("SECURE_BOOT") || logLine.contains("LOADER") -> Color(0xFF38BDF8)
                            else -> Color(0xFFE2E8F0)
                        }
                        Text(
                            text = logLine,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
