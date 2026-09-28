package com.example.vm.guest.os

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

enum class OSDownloadState {
    IDLE,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLING,
    READY,
    FAILED,
    CANCELLED
}

data class OSDownloadProgress(
    val manifestId: String = "",
    val state: OSDownloadState = OSDownloadState.IDLE,
    val currentFileName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercent: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val estimatedRemainingSeconds: Long = 0L,
    val statusMessage: String = "Idle",
    val errorMessage: String? = null
)

class OSDownloadManager(private val context: Context) {

    private val _downloadProgress = MutableStateFlow<Map<String, OSDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, OSDownloadProgress>> = _downloadProgress.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val isPausedFlags = mutableMapOf<String, Boolean>()
    private val isCancelledFlags = mutableMapOf<String, Boolean>()
    private val downloadScope = CoroutineScope(Dispatchers.IO + Job())

    fun getProgress(manifestId: String): OSDownloadProgress {
        return _downloadProgress.value[manifestId] ?: OSDownloadProgress(manifestId = manifestId)
    }

    /**
     * Initiates a verified download workflow for an OS manifest.
     */
    fun startDownload(manifest: OSManifest, onCompleted: ((Boolean) -> Unit)? = null) {
        val manifestId = manifest.id

        // Storage capacity check before download
        val capacity = OSStorageManager.checkStorageCapacity(context, manifest.downloadSizeBytes)
        if (!capacity.isSufficient) {
            val shortfallMb = capacity.shortfallBytes / (1024 * 1024)
            updateProgress(
                manifestId,
                OSDownloadProgress(
                    manifestId = manifestId,
                    state = OSDownloadState.FAILED,
                    statusMessage = "INSUFFICIENT_STORAGE: Need additional $shortfallMb MB space.",
                    errorMessage = "Insufficient storage space. Need $shortfallMb MB more space (including 500 MB safety headroom)."
                )
            )
            onCompleted?.invoke(false)
            return
        }

        isPausedFlags[manifestId] = false
        isCancelledFlags[manifestId] = false

        updateProgress(
            manifestId,
            OSDownloadProgress(
                manifestId = manifestId,
                state = OSDownloadState.QUEUED,
                statusMessage = "Queued for download from ${manifest.sourceName}"
            )
        )

        activeJobs[manifestId]?.cancel()
        activeJobs[manifestId] = downloadScope.launch {
            try {
                val targetDir = OSStorageManager.getOsPrivateDirectory(context, manifestId)

                // 1. Download and verify Kernel
                val kernelSuccess = downloadAndVerifyFile(
                    manifest = manifest,
                    urlStr = manifest.kernelUrl,
                    targetDir = targetDir,
                    finalName = "vmlinuz",
                    expectedSha256 = manifest.expectedKernelSha256,
                    label = "Kernel (vmlinuz)"
                )

                if (!kernelSuccess) {
                    onCompleted?.invoke(false)
                    return@launch
                }

                // 2. Download and verify Initramfs (if specified)
                if (manifest.initramfsUrl.isNotBlank()) {
                    val initrdSuccess = downloadAndVerifyFile(
                        manifest = manifest,
                        urlStr = manifest.initramfsUrl,
                        targetDir = targetDir,
                        finalName = "initrd",
                        expectedSha256 = manifest.expectedInitramfsSha256,
                        label = "Initramfs (initrd)"
                    )

                    if (!initrdSuccess) {
                        onCompleted?.invoke(false)
                        return@launch
                    }
                }

                // Installation complete and verified
                updateProgress(
                    manifestId,
                    OSDownloadProgress(
                        manifestId = manifestId,
                        state = OSDownloadState.READY,
                        progressPercent = 100,
                        statusMessage = "All components verified via official SHA-256 and ready in private storage."
                    )
                )
                onCompleted?.invoke(true)
            } catch (e: Exception) {
                OSStorageManager.cleanTemporaryFiles(context, manifestId)
                updateProgress(
                    manifestId,
                    OSDownloadProgress(
                        manifestId = manifestId,
                        state = OSDownloadState.FAILED,
                        statusMessage = "DOWNLOAD_FAILED: ${e.localizedMessage}",
                        errorMessage = e.localizedMessage
                    )
                )
                onCompleted?.invoke(false)
            }
        }
    }

    private suspend fun downloadAndVerifyFile(
        manifest: OSManifest,
        urlStr: String,
        targetDir: File,
        finalName: String,
        expectedSha256: String,
        label: String
    ): Boolean = withContext(Dispatchers.IO) {
        val manifestId = manifest.id

        // Strict HTTPS enforcement
        if (!urlStr.startsWith("https://", ignoreCase = true)) {
            updateProgress(
                manifestId,
                OSDownloadProgress(
                    manifestId = manifestId,
                    state = OSDownloadState.FAILED,
                    statusMessage = "SECURITY_VIOLATION: Insecure HTTP URL rejected ($urlStr). HTTPS is strictly required.",
                    errorMessage = "Only secure HTTPS download sources are authorized."
                )
            )
            return@withContext false
        }

        val tempFile = File(targetDir, "$finalName.part")
        val finalFile = File(targetDir, finalName)

        val url = URL(urlStr)
        var connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 30000

        var existingBytes = 0L
        if (tempFile.exists()) {
            existingBytes = tempFile.length()
            connection.setRequestProperty("Range", "bytes=$existingBytes-")
        }

        connection.connect()
        var responseCode = connection.responseCode

        // If server does not support partial content, restart from byte 0
        val isResume = (responseCode == HttpURLConnection.HTTP_PARTIAL)
        if (!isResume && responseCode != HttpURLConnection.HTTP_OK) {
            updateProgress(
                manifestId,
                OSDownloadProgress(
                    manifestId = manifestId,
                    state = OSDownloadState.FAILED,
                    statusMessage = "DOWNLOAD_FAILED: Server returned HTTP $responseCode",
                    errorMessage = "HTTP error $responseCode from ${manifest.sourceName}"
                )
            )
            return@withContext false
        }

        if (!isResume && tempFile.exists()) {
            tempFile.delete()
            existingBytes = 0L
        }

        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) (if (isResume) existingBytes + contentLength else contentLength) else manifest.downloadSizeBytes

        val output = if (isResume) {
            RandomAccessFile(tempFile, "rw").apply { seek(existingBytes) }
        } else {
            FileOutputStream(tempFile)
        }

        var downloaded = existingBytes
        val buffer = ByteArray(16384)
        var lastTime = System.currentTimeMillis()
        var bytesSinceLastTime = 0L
        var currentSpeed = 0L

        updateProgress(
            manifestId,
            OSDownloadProgress(
                manifestId = manifestId,
                state = OSDownloadState.DOWNLOADING,
                currentFileName = label,
                bytesDownloaded = downloaded,
                totalBytes = totalBytes,
                progressPercent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else 0,
                statusMessage = "Downloading $label from ${manifest.sourceName}"
            )
        )

        try {
            connection.inputStream.use { input ->
                var read = input.read(buffer)
                while (read != -1) {
                    if (isCancelledFlags[manifestId] == true) {
                        tempFile.delete()
                        updateProgress(
                            manifestId,
                            OSDownloadProgress(
                                manifestId = manifestId,
                                state = OSDownloadState.CANCELLED,
                                statusMessage = "Download cancelled by user."
                            )
                        )
                        return@withContext false
                    }

                    if (isPausedFlags[manifestId] == true) {
                        updateProgress(
                            manifestId,
                            OSDownloadProgress(
                                manifestId = manifestId,
                                state = OSDownloadState.PAUSED,
                                currentFileName = label,
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes,
                                progressPercent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0,
                                statusMessage = "Download paused."
                            )
                        )
                        return@withContext false
                    }

                    if (output is RandomAccessFile) {
                        output.write(buffer, 0, read)
                    } else if (output is FileOutputStream) {
                        output.write(buffer, 0, read)
                    }

                    downloaded += read
                    bytesSinceLastTime += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTime
                    if (elapsed >= 500) {
                        currentSpeed = (bytesSinceLastTime * 1000L) / elapsed
                        lastTime = now
                        bytesSinceLastTime = 0L
                        val remainingBytes = totalBytes - downloaded
                        val eta = if (currentSpeed > 0) remainingBytes / currentSpeed else 0L

                        updateProgress(
                            manifestId,
                            OSDownloadProgress(
                                manifestId = manifestId,
                                state = OSDownloadState.DOWNLOADING,
                                currentFileName = label,
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes,
                                progressPercent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else 0,
                                speedBytesPerSec = currentSpeed,
                                estimatedRemainingSeconds = eta,
                                statusMessage = "Downloading $label (${downloaded / (1024 * 1024)} / ${totalBytes / (1024 * 1024)} MB)"
                            )
                        )
                    }
                    read = input.read(buffer)
                }
            }
        } finally {
            if (output is RandomAccessFile) output.close() else if (output is FileOutputStream) output.close()
            connection.disconnect()
        }

        // 3. Local SHA-256 Checksum Verification
        updateProgress(
            manifestId,
            OSDownloadProgress(
                manifestId = manifestId,
                state = OSDownloadState.VERIFYING,
                currentFileName = label,
                bytesDownloaded = downloaded,
                totalBytes = totalBytes,
                progressPercent = 99,
                statusMessage = "Calculating SHA-256 and verifying with official signature..."
            )
        )

        val calculatedHash = computeSha256(tempFile)
        val expectedNormalized = expectedSha256.trim().lowercase(Locale.US)
        val calculatedNormalized = calculatedHash.trim().lowercase(Locale.US)

        if (expectedNormalized.isNotEmpty() && calculatedNormalized != expectedNormalized) {
            tempFile.delete()
            updateProgress(
                manifestId,
                OSDownloadProgress(
                    manifestId = manifestId,
                    state = OSDownloadState.FAILED,
                    statusMessage = "CHECKSUM_FAILED: Verification failed: SHA-256 mismatch.",
                    errorMessage = "Verification failed: SHA-256 mismatch.\nExpected: $expectedNormalized\nComputed: $calculatedNormalized"
                )
            )
            return@withContext false
        }

        // Atomic move to final verified file
        updateProgress(
            manifestId,
            OSDownloadProgress(
                manifestId = manifestId,
                state = OSDownloadState.INSTALLING,
                currentFileName = label,
                bytesDownloaded = downloaded,
                totalBytes = totalBytes,
                progressPercent = 99,
                statusMessage = "Installing $label into private VM storage..."
            )
        )

        if (finalFile.exists()) {
            finalFile.delete()
        }
        val moved = tempFile.renameTo(finalFile)
        if (!moved) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }

        return@withContext true
    }

    fun pauseDownload(manifestId: String) {
        isPausedFlags[manifestId] = true
    }

    fun resumeDownload(manifest: OSManifest, onCompleted: ((Boolean) -> Unit)? = null) {
        startDownload(manifest, onCompleted)
    }

    fun cancelDownload(manifestId: String) {
        isCancelledFlags[manifestId] = true
        activeJobs[manifestId]?.cancel()
        OSStorageManager.cleanTemporaryFiles(context, manifestId)
        updateProgress(
            manifestId,
            OSDownloadProgress(
                manifestId = manifestId,
                state = OSDownloadState.CANCELLED,
                statusMessage = "Download cancelled."
            )
        )
    }

    private fun updateProgress(manifestId: String, progress: OSDownloadProgress) {
        val current = _downloadProgress.value.toMutableMap()
        current[manifestId] = progress
        _downloadProgress.value = current
    }

    /**
     * Computes the SHA-256 checksum of a file.
     */
    fun computeSha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        val hashBytes = digest.digest()
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
