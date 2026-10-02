package com.example.vm.sysboot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "boot_entries")
data class BootEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val loaderPath: String,
    val kernelParams: String,
    val isDefault: Boolean,
    val timeoutSeconds: Int,
    val osType: String, // WINDOWS, LINUX, RECOVERY, DIAGNOSTICS, SHELL
    val displayOrder: Int,
    val isEnabled: Boolean,
    val secureBootRequired: Boolean
)

@Entity(tableName = "diagnostic_results")
data class DiagnosticResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val testName: String,
    val category: String, // CPU, RAM, NVME, GPU, DISPLAY, NETWORK, AUDIO, TPM, ACPI
    val timestamp: Long,
    val status: String, // PASSED, WARNING, FAILED
    val scoreOrMetric: String,
    val detailsLog: String
)

@Entity(tableName = "security_audits")
data class SecurityAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val eventType: String, // SECURE_BOOT_KEY, MEASURED_BOOT, PCR_EXTEND, EFI_SIGNATURE, TPM_ATTESTATION
    val severity: String, // INFO, SUCCESS, WARNING, CRITICAL
    val summary: String,
    val details: String
)

@Entity(tableName = "c_source_modules")
data class CSourceModuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val filename: String, // e.g. main.c, boot_manager.c, windows.c, linux.c
    val category: String, // CORE, OS_LOADER, STORAGE, SECURITY, UTILITY, BUILD
    val description: String,
    val codeContent: String,
    val headerContent: String = "",
    val dependencies: String = ""
)

@Entity(tableName = "failure_simulations")
data class BootFailureSimulationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long,
    val failureType: String, // WINDOWS_MISSING, LINUX_MISSING, ESP_DAMAGED, BOOT_FILE_MISSING, SECURE_BOOT_FAIL, STORAGE_UNAVAILABLE
    val status: String, // DETECTED, DIVERTED_TO_RECOVERY, REPAIRED
    val rootCause: String,
    val recoveryAction: String,
    val detailedLog: String
)
