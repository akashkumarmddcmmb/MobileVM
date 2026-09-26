package com.example.vm.guest.kernel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads or imports legitimate, open-source ARM64 Linux Kernel images
 * from verified sources (e.g. Alpine Linux / Debian ARM64 official repositories).
 * Never bundles copyrighted or proprietary OS binary payloads.
 */
object GuestKernelDownloader {

    sealed class DownloadResult {
        data class Success(val kernelFile: File, val sizeBytes: Long, val message: String) : DownloadResult()
        data class Failure(val reason: String) : DownloadResult()
    }

    /**
     * Imports a user-selected kernel file into the app's internal sandbox.
     */
    suspend fun importKernel(
        context: Context,
        sourceInputStream: InputStream,
        destinationFileName: String = "vmlinuz-arm64"
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val kernelDir = File(context.filesDir, "guest_kernels").apply { mkdirs() }
            val targetFile = File(kernelDir, destinationFileName)

            sourceInputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val info = GuestKernelManager.inspectKernel(targetFile.absolutePath)
            if (!info.isArm64Valid) {
                targetFile.delete()
                return@withContext DownloadResult.Failure(
                    "Imported file is not a valid ARM64 Linux kernel image (Header check failed)."
                )
            }

            DownloadResult.Success(
                kernelFile = targetFile,
                sizeBytes = targetFile.length(),
                message = "Successfully imported ARM64 Linux kernel (${targetFile.length() / 1024} KB)."
            )
        } catch (e: Exception) {
            DownloadResult.Failure("Kernel import failed: ${e.localizedMessage}")
        }
    }

    /**
     * Downloads an open-source ARM64 Linux kernel directly into the app sandbox.
     */
    suspend fun downloadKernelFromUrl(
        context: Context,
        kernelUrl: String,
        destinationFileName: String = "vmlinuz-arm64"
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(kernelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext DownloadResult.Failure(
                    "HTTP Download failed: ${connection.responseCode} ${connection.responseMessage}"
                )
            }

            val kernelDir = File(context.filesDir, "guest_kernels").apply { mkdirs() }
            val targetFile = File(kernelDir, destinationFileName)

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val info = GuestKernelManager.inspectKernel(targetFile.absolutePath)
            if (!info.isArm64Valid) {
                targetFile.delete()
                return@withContext DownloadResult.Failure(
                    "Downloaded file is not a valid ARM64 kernel (Architecture header mismatch)."
                )
            }

            DownloadResult.Success(
                kernelFile = targetFile,
                sizeBytes = targetFile.length(),
                message = "Downloaded and verified ARM64 Linux kernel (${targetFile.length() / 1024} KB)."
            )
        } catch (e: Exception) {
            DownloadResult.Failure("Download error: ${e.localizedMessage}")
        }
    }
}
