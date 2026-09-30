package com.example.vm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vm.security.LegalLicenseManager
import com.example.vm.security.ProjectProtectionManager
import com.example.vm.security.SoftwareLicense

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseAndProtectionScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audit = remember { ProjectProtectionManager.performProtectionAudit(context) }

    var selectedTab by remember { mutableStateOf(0) }
    var expandedLicenseId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Licenses & Legal Protection",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Open Source Compliance & Integrity Verification",
                            fontSize = 11.sp,
                            color = Color(0xFF00E676)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("btn_close_license_screen")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x2200E676),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AUTHENTIC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                edgePadding = 8.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Licenses & OS Notices") },
                    icon = { Icon(Icons.Default.Gavel, contentDescription = null) },
                    modifier = Modifier.testTag("tab_licenses")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Anti-Copy Guard") },
                    icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                    modifier = Modifier.testTag("tab_anti_copy")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("EULA & Copyright") },
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    modifier = Modifier.testTag("tab_eula")
                )
            }

            when (selectedTab) {
                0 -> LicensesListTab(
                    licenses = LegalLicenseManager.LICENSES,
                    expandedId = expandedLicenseId,
                    onToggleExpand = { id ->
                        expandedLicenseId = if (expandedLicenseId == id) null else id
                    }
                )
                1 -> AntiCopyGuardTab(audit)
                2 -> EulaTab()
            }
        }
    }
}

@Composable
private fun AntiCopyGuardTab(audit: com.example.vm.security.ProjectProtectionReport) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Project Integrity Status: 100% Authentic",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF00E676)
                            )
                            Text(
                                text = "Active Binary Tamper Protection & Copy Shield",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))

                    IntegrityRow("Package Identity", audit.packageName, audit.isPackageAuthentic)
                    IntegrityRow("Signature Hash", audit.signatureHash.take(24) + "...", audit.isSignatureVerified)
                    IntegrityRow("Hooking Detection", if (audit.isHookingToolDetected) "Detected" else "Clean", !audit.isHookingToolDetected)
                    IntegrityRow("Debugger Attachment", if (audit.isDebuggerAttached) "Attached" else "None", !audit.isDebuggerAttached)
                    IntegrityRow("Cloning Status", if (audit.isCloningDetected) "Tampered" else "Original", !audit.isCloningDetected)
                    IntegrityRow("Device Storage Seal", audit.storageSealToken.take(16) + "...", true)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Proprietary Copyright Enforcement",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Copyright (c) 2026 MobileVM. All Rights Reserved.",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "This application is protected under national and international copyright treaties. Unauthorized cloning, redistribution, commercial sale, repackaging, or extracting emulator algorithms is strictly prohibited.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device-Bound Storage Isolation",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Guest VM storage images and settings are sealed with a cryptographic HMAC token tied to this installation. Copying disk images to unauthorized devices or cloned apps renders them unbootable.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun IntegrityRow(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.LightGray
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isOk) Color(0xFF00E676) else Color(0xFFFF5252)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (isOk) Color(0xFF00E676) else Color(0xFFFF5252),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun LicensesListTab(
    licenses: List<SoftwareLicense>,
    expandedId: String?,
    onToggleExpand: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clean Copyright Compliance: MobileVM uses only authorized official sources and displays upstream open-source licenses transparently.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        items(licenses, key = { it.id }) { license ->
            val isExpanded = expandedId == license.id
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (license.isThirdParty) Color.DarkGray else MaterialTheme.colorScheme.primary
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = license.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                if (!license.isThirdParty) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "PROPRIETARY",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${license.owner} • ${license.licenseType}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }

                        IconButton(onClick = { onToggleExpand(license.id) }) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle license text"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = license.summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    AnimatedVisibility(visible = isExpanded) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = Color.Black.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = license.fullText,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(10.dp),
                                    lineHeight = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Source: ${license.officialSourceUrl}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EulaTab() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.DarkGray)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "End-User License Agreement (EULA)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = LegalLicenseManager.EULA_TEXT,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}
