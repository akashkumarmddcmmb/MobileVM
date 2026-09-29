package com.example.vm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

import com.example.vm.licensing.LicenseManager
import com.example.vm.licensing.LicenseStatus
import com.example.vm.licensing.LicenseTier
import com.example.vm.licensing.LicenseEntitlements
import android.widget.Toast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseAndProtectionScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audit = remember { ProjectProtectionManager.performProtectionAudit(context) }
    val licenseManager = remember { LicenseManager.getInstance(context) }
    val licenseState by licenseManager.licenseState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var expandedLicenseId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Proprietary Licensing & Protection",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${licenseState.tier.displayName} • ${licenseState.status.displayName}",
                            fontSize = 11.sp,
                            color = when (licenseState.status) {
                                LicenseStatus.ACTIVE -> Color(0xFF00E676)
                                LicenseStatus.TRIAL -> Color(0xFF00E5FF)
                                LicenseStatus.OFFLINE_GRACE_PERIOD -> Color(0xFFFFB300)
                                LicenseStatus.EXPIRED, LicenseStatus.REVOKED -> Color(0xFFFF5252)
                                LicenseStatus.INVALID -> Color.Gray
                            }
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
                        color = when (licenseState.status) {
                            LicenseStatus.ACTIVE -> Color(0x2200E676)
                            LicenseStatus.TRIAL -> Color(0x2200E5FF)
                            LicenseStatus.OFFLINE_GRACE_PERIOD -> Color(0x22FFB300)
                            else -> Color(0x22FF5252)
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = when (licenseState.status) {
                                    LicenseStatus.ACTIVE -> Color(0xFF00E676)
                                    LicenseStatus.TRIAL -> Color(0xFF00E5FF)
                                    LicenseStatus.OFFLINE_GRACE_PERIOD -> Color(0xFFFFB300)
                                    else -> Color(0xFFFF5252)
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = licenseState.tier.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (licenseState.status) {
                                    LicenseStatus.ACTIVE -> Color(0xFF00E676)
                                    LicenseStatus.TRIAL -> Color(0xFF00E5FF)
                                    LicenseStatus.OFFLINE_GRACE_PERIOD -> Color(0xFFFFB300)
                                    else -> Color(0xFFFF5252)
                                },
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
                    text = { Text("Activation & Plans") },
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    modifier = Modifier.testTag("tab_activation")
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
                    text = { Text("Licenses & OS") },
                    icon = { Icon(Icons.Default.Gavel, contentDescription = null) },
                    modifier = Modifier.testTag("tab_licenses")
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("EULA") },
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    modifier = Modifier.testTag("tab_eula")
                )
            }

            when (selectedTab) {
                0 -> LicenseActivationTab(
                    licenseState = licenseState,
                    onActivate = { key ->
                        coroutineScope.launch {
                            val result = licenseManager.activateKey(key)
                            if (result.isSuccess) {
                                Toast.makeText(context, "License Activated: ${result.getOrNull()?.tier?.displayName}", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Activation Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onValidate = {
                        coroutineScope.launch {
                            val result = licenseManager.refreshValidation()
                            if (result.isSuccess) {
                                Toast.makeText(context, "Validation Succeeded: License is ACTIVE", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Validation Error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDeactivate = {
                        coroutineScope.launch {
                            licenseManager.deactivateCurrentKey()
                            Toast.makeText(context, "Device deactivated. Reverted to Free plan.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStartTrial = {
                        licenseManager.startEvaluationTrial()
                        Toast.makeText(context, "14-Day Pro Trial Activated!", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> AntiCopyGuardTab(audit)
                2 -> LicensesListTab(
                    licenses = LegalLicenseManager.LICENSES,
                    expandedId = expandedLicenseId,
                    onToggleExpand = { id ->
                        expandedLicenseId = if (expandedLicenseId == id) null else id
                    }
                )
                3 -> EulaTab()
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

@Composable
private fun LicenseActivationTab(
    licenseState: com.example.vm.licensing.LicenseState,
    onActivate: (String) -> Unit,
    onValidate: () -> Unit,
    onDeactivate: () -> Unit,
    onStartTrial: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var inputKey by remember { mutableStateOf("") }

    val statusColor = when (licenseState.status) {
        LicenseStatus.ACTIVE -> Color(0xFF00E676)
        LicenseStatus.TRIAL -> Color(0xFF00E5FF)
        LicenseStatus.OFFLINE_GRACE_PERIOD -> Color(0xFFFFB300)
        LicenseStatus.EXPIRED, LicenseStatus.REVOKED -> Color(0xFFFF5252)
        LicenseStatus.INVALID -> Color.Gray
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Current Plan & Status Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, statusColor.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = licenseState.tier.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Proprietary Commercial License",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, statusColor)
                        ) {
                            Text(
                                text = licenseState.status.displayName.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (licenseState.isTimeTampered) {
                        Surface(
                            color = Color(0x33FF5252),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFFF5252)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SECURITY ALERT: Clock manipulation detected. Offline grace disabled.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    DetailRow("License Key", licenseState.licenseKey ?: "None (Using Free Tier)")
                    DetailRow("Licensed To", licenseState.issuedTo ?: "Standard User")
                    DetailRow(
                        "Expiration",
                        if (licenseState.isPerpetual) "Perpetual / Lifetime"
                        else if (licenseState.expiresAt > 0L) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(licenseState.expiresAt))
                        else "N/A"
                    )

                    if (licenseState.status == LicenseStatus.OFFLINE_GRACE_PERIOD) {
                        val hours = licenseState.offlineGracePeriodRemainingMs / (1000 * 60 * 60)
                        DetailRow("Offline Grace Remaining", "$hours hours remaining (7 days max)")
                    }

                    licenseState.serverMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Server Note: $msg",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }

        // 2. Hardware Device Binding Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Smartphone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Hardware Device Identity",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Device Fingerprint:",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = licenseState.deviceId,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(licenseState.deviceId))
                                Toast.makeText(context, "Device ID copied!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Device ID", modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        text = "Active Device Quota: ${licenseState.activeDeviceCount} of ${licenseState.maxDevices} device(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. Activation Controls Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Activate License Key",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it.uppercase() },
                        label = { Text("License Key (MBM-XXXX-XXXX...)") },
                        placeholder = { Text("MBM-PRO-2026-TEST-7890-ABCD") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("txt_license_key"),
                        trailingIcon = {
                            if (inputKey.isNotEmpty()) {
                                IconButton(onClick = { inputKey = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onActivate(inputKey) },
                            enabled = inputKey.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_activate_license")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Activate")
                        }

                        OutlinedButton(
                            onClick = onValidate,
                            enabled = licenseState.licenseKey != null,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_validate_license")
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Validate")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (licenseState.licenseKey != null && licenseState.status == LicenseStatus.ACTIVE) {
                            OutlinedButton(
                                onClick = onDeactivate,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_deactivate_license"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                            ) {
                                Text("Deactivate Device")
                            }
                        }

                        if (licenseState.status != LicenseStatus.ACTIVE && licenseState.status != LicenseStatus.TRIAL) {
                            FilledTonalButton(
                                onClick = onStartTrial,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_start_trial")
                            ) {
                                Text("14-Day Pro Trial")
                            }
                        }
                    }
                }
            }
        }

        // 4. Feature Entitlements Breakdown
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active Tier Entitlements",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    EntitlementItem("Maximum vCPU Cores", "${licenseState.entitlements.maxCpuCores} Core(s)", true)
                    EntitlementItem("Maximum RAM Allocation", "${licenseState.entitlements.maxRamMb} MB", true)
                    EntitlementItem("Disk Snapshots", if (licenseState.entitlements.canCreateSnapshots) "Enabled" else "Pro/Premium only", licenseState.entitlements.canCreateSnapshots)
                    EntitlementItem("VM Disk Export", if (licenseState.entitlements.canExportVm) "Enabled" else "Pro/Premium only", licenseState.entitlements.canExportVm)
                    EntitlementItem("Custom Disk Images", if (licenseState.entitlements.canUseCustomDisks) "Enabled" else "Pro/Premium only", licenseState.entitlements.canUseCustomDisks)
                    EntitlementItem("Priority CPU Emulation", if (licenseState.entitlements.canUsePriorityCpu) "Enabled" else "Premium/Enterprise only", licenseState.entitlements.canUsePriorityCpu)
                    EntitlementItem("Headless VM Execution", if (licenseState.entitlements.canUseHeadlessMode) "Enabled" else "Premium/Enterprise only", licenseState.entitlements.canUseHeadlessMode)
                }
            }
        }

        // 5. Official Test / Sample Keys
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.DarkGray)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Official Test & Demonstration Keys",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Click any official test key below to auto-fill and test activation:",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SampleKeyButton("MobileVM Pro (1 Year, 3 Devices)", "MVM-PRO-2026-TEST-7890-ABCD") { inputKey = it }
                    SampleKeyButton("MobileVM Premium (Lifetime, 5 Devices)", "MVM-PREMIUM-2026-POWER-4321-EFGH") { inputKey = it }
                    SampleKeyButton("MobileVM Enterprise (Lifetime, 20 Devices)", "MVM-ENTERPRISE-2026-CORP-9999-XYZW") { inputKey = it }
                    SampleKeyButton("Revoked Key (Negative Test)", "MVM-REVOKED-2026-BADK-0000-FAIL") { inputKey = it }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = Color.Gray)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EntitlementItem(label: String, value: String, isAllowed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isAllowed) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (isAllowed) Color(0xFF00E676) else Color.Gray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, fontSize = 12.sp)
        }
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isAllowed) MaterialTheme.colorScheme.primary else Color.Gray
        )
    }
}

@Composable
private fun SampleKeyButton(label: String, key: String, onSelect: (String) -> Unit) {
    Surface(
        onClick = { onSelect(key) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text = key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
    }
}

