package com.example.vm.sharing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * File descriptor inside the virtual shared folder.
 */
data class SharedFileEntry(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val lastModified: Long
)

/**
 * Configuration mapping for an Android host directory mounted inside guest OS.
 */
data class HostSharedDirectory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val guestMountPath: String,
    val hostAbsolutePath: String,
    val isReadOnly: Boolean = false
)

/**
 * SharedFolderManager: Implements real Android ↔ Guest shared folder bridge.
 *
 * Provides safe file operations (read, write, list, create, delete, rename, stream import/export)
 * between the Android host storage and the virtual machine with strict path-traversal prevention.
 */
class SharedFolderManager(private val context: Context) {
    companion object {
        private const val TAG = "SharedFolderManager"
        const val MAX_FILE_SIZE_BYTES = 4L * 1024L * 1024L * 1024L // 4 GB safe bound
        const val BUFFER_SIZE = 65536 // 64 KB streaming buffer
    }

    private val configuredShares = mutableListOf<HostSharedDirectory>()

    init {
        // Initialize default primary share
        val defaultRoot = getSharedFolderRoot()
        configuredShares.add(
            HostSharedDirectory(
                id = "default_shared",
                name = "Shared",
                guestMountPath = "/shared",
                hostAbsolutePath = defaultRoot.absolutePath,
                isReadOnly = false
            )
        )
    }

    /**
     * Resolves the primary shared folder root in app sandboxed storage.
     */
    fun getSharedFolderRoot(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val sharedDir = File(baseDir, "shared")
        if (!sharedDir.exists()) {
            sharedDir.mkdirs()
        }
        return sharedDir
    }

    fun getShares(): List<HostSharedDirectory> = synchronized(configuredShares) {
        configuredShares.toList()
    }

    fun addShare(name: String, guestMountPath: String, hostPath: String, isReadOnly: Boolean = false): HostSharedDirectory {
        val share = HostSharedDirectory(
            name = name,
            guestMountPath = guestMountPath,
            hostAbsolutePath = hostPath,
            isReadOnly = isReadOnly
        )
        synchronized(configuredShares) {
            configuredShares.removeAll { it.name == name || it.guestMountPath == guestMountPath }
            configuredShares.add(share)
        }
        return share
    }

    fun removeShare(shareId: String): Boolean = synchronized(configuredShares) {
        configuredShares.removeAll { it.id == shareId && it.id != "default_shared" }
    }

    /**
     * Validates and resolves a relative path strictly within the shared folder boundaries.
     * Prevents '../' path traversal, absolute path escape, and symlink escape.
     */
    fun resolveSafeFile(relativePath: String): File {
        val cleanPath = relativePath.trimStart('/', '\\')
        if (cleanPath.contains("..") || cleanPath.contains("\u0000")) {
            throw SecurityException("Path traversal or illegal character detected in path: $relativePath")
        }

        val root = getSharedFolderRoot().canonicalFile
        val target = File(root, cleanPath).canonicalFile

        if (!target.path.startsWith(root.path)) {
            throw SecurityException("Access outside shared folder boundary is strictly prohibited: $relativePath")
        }
        return target
    }

    suspend fun listFiles(subDirectory: String = ""): List<SharedFileEntry> = withContext(Dispatchers.IO) {
        try {
            val dir = resolveSafeFile(subDirectory)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

            dir.listFiles()?.map { file ->
                val rel = file.relativeTo(getSharedFolderRoot()).path
                SharedFileEntry(
                    name = file.name,
                    relativePath = rel,
                    sizeBytes = if (file.isDirectory) 0L else file.length(),
                    isDirectory = file.isDirectory,
                    lastModified = file.lastModified()
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing shared directory '$subDirectory': ${e.message}")
            emptyList()
        }
    }

    suspend fun readFileBytes(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = resolveSafeFile(relativePath)
            if (!file.exists() || file.isDirectory) return@withContext null
            if (file.length() > 64 * 1024 * 1024) {
                Log.w(TAG, "File too large for byte array read; use streaming API: ${file.length()} bytes")
                return@withContext null
            }
            FileInputStream(file).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file '$relativePath': ${e.message}")
            null
        }
    }

    suspend fun writeFileBytes(relativePath: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = resolveSafeFile(relativePath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(data) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file '$relativePath': ${e.message}")
            false
        }
    }

    suspend fun renameFile(oldRelativePath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
                throw SecurityException("Illegal new file name: $newName")
            }
            val oldFile = resolveSafeFile(oldRelativePath)
            if (!oldFile.exists()) return@withContext false

            val newFile = File(oldFile.parentFile, newName)
            oldFile.renameTo(newFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming '$oldRelativePath' to '$newName': ${e.message}")
            false
        }
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = resolveSafeFile(relativePath)
            if (file.exists()) file.deleteRecursively() else false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file '$relativePath': ${e.message}")
            false
        }
    }

    suspend fun createDirectory(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = resolveSafeFile(relativePath)
            dir.mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating directory '$relativePath': ${e.message}")
            false
        }
    }

    /**
     * Streams file transfer with progress callbacks and cancellation check.
     */
    suspend fun streamImportFile(
        sourceStream: InputStream,
        targetRelativePath: String,
        totalBytes: Long,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var written = 0L
        val targetFile = resolveSafeFile(targetRelativePath)
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { fos ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (sourceStream.read(buf).also { n = it } != -1) {
                    if (isCancelled?.invoke() == true) {
                        targetFile.delete()
                        return@withContext false
                    }
                    fos.write(buf, 0, n)
                    written += n
                    onProgress?.invoke(written, totalBytes)
                }
                fos.fd.sync()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming import to '$targetRelativePath': ${e.message}")
            if (targetFile.exists()) targetFile.delete()
            false
        }
    }
}
