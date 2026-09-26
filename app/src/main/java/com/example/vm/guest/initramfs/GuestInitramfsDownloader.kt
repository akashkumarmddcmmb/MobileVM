package com.example.vm.guest.initramfs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads or imports legitimate, open-source ARM64 BusyBox / Alpine Linux initramfs
 * archives from public repositories. Never bundles copyright-restricted payloads.
 */
object GuestInitramfsDownloader {

    sealed class InitramfsResult {
        data class Success(val initramfsFile: File, val sizeBytes: Long, val message: String) : InitramfsResult()
        data class Failure(val reason: String) : InitramfsResult()
    }

    /**
     * Imports a user-selected initramfs file into the app's internal sandbox.
     */
    suspend fun importInitramfs(
        context: Context,
        sourceInputStream: InputStream,
        destinationFileName: String = "initramfs-busybox-arm64.cpio.gz"
    ): InitramfsResult = withContext(Dispatchers.IO) {
        try {
            val initrdDir = File(context.filesDir, "guest_initramfs").apply { mkdirs() }
            val targetFile = File(initrdDir, destinationFileName)

            sourceInputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val info = GuestInitramfsManager.inspectInitramfs(targetFile.absolutePath)
            if (!info.exists || info.sizeBytes == 0L) {
                targetFile.delete()
                return@withContext InitramfsResult.Failure("Imported initramfs file is empty or unreadable.")
            }

            InitramfsResult.Success(
                initramfsFile = targetFile,
                sizeBytes = targetFile.length(),
                message = "Successfully imported ARM64 initramfs (${info.format}, ${targetFile.length() / 1024} KB)."
            )
        } catch (e: Exception) {
            InitramfsResult.Failure("Initramfs import failed: ${e.localizedMessage}")
        }
    }

    /**
     * Downloads an open-source ARM64 BusyBox initramfs directly into the app sandbox.
     */
    suspend fun downloadInitramfsFromUrl(
        context: Context,
        initramfsUrl: String,
        destinationFileName: String = "initramfs-busybox-arm64.cpio.gz"
    ): InitramfsResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(initramfsUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext InitramfsResult.Failure(
                    "HTTP Download failed: ${connection.responseCode} ${connection.responseMessage}"
                )
            }

            val initrdDir = File(context.filesDir, "guest_initramfs").apply { mkdirs() }
            val targetFile = File(initrdDir, destinationFileName)

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val info = GuestInitramfsManager.inspectInitramfs(targetFile.absolutePath)
            if (!info.exists || info.sizeBytes == 0L) {
                targetFile.delete()
                return@withContext InitramfsResult.Failure("Downloaded initramfs file is empty.")
            }

            InitramfsResult.Success(
                initramfsFile = targetFile,
                sizeBytes = targetFile.length(),
                message = "Downloaded and verified ARM64 initramfs (${info.format}, ${targetFile.length() / 1024} KB)."
            )
        } catch (e: Exception) {
            InitramfsResult.Failure("Download error: ${e.localizedMessage}")
        }
    }
}
