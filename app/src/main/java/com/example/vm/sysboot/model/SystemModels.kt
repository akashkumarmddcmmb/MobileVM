package com.example.vm.sysboot.model

data class AcpiTable(
    val signature: String,
    val description: String,
    val oemId: String,
    val oemTableId: String,
    val revision: Int,
    val physicalAddress: String,
    val lengthBytes: Int,
    val aslCodeSnippet: String
)

data class PartitionInfo(
    val index: Int,
    val name: String,
    val fileSystem: String,
    val sizeGb: Double,
    val usedGb: Double,
    val guid: String,
    val flags: List<String>,
    val mountPoint: String
)

data class EspFileItem(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val fileType: String, // EFI, BCD, CONFIG, TEXT, OTHER
    val contentPreview: String? = null
)

data class PcrRegister(
    val index: Int,
    val name: String,
    val sha256Hash: String,
    val description: String
)

data class SecureBootKey(
    val keyType: String, // PK, KEK, db, dbx
    val ownerGuid: String,
    val issuer: String,
    val subject: String,
    val validFrom: String,
    val validUntil: String,
    val algorithm: String
)

data class HardwareMetric(
    val cpuUsagePercent: Float,
    val cpuTempCelsius: Float,
    val cpuFreqGhz: Float,
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val nvmeHealthPercent: Int,
    val nvmeReadSpeedMb: Int,
    val nvmeWriteSpeedMb: Int,
    val tpmStatus: String,
    val secureBootStatus: String
)

data class ArchitectureDoc(
    val id: String,
    val title: String,
    val category: String,
    val contentMarkdown: String
)

data class ProjectFileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val children: List<ProjectFileNode> = emptyList(),
    val description: String = "",
    val fileContent: String = ""
)
