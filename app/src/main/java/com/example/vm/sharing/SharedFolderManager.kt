package com.example.vm.sharing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
 * SharedFolderManager: Implements real Android ↔ Guest shared folder bridge.
 *
 * Provides safe file operations (read, write, list, create, delete, rename)
 * between the Android host storage and the virtual machine with strict path-traversal prevention.
 */
class SharedFolderManager(private val context: Context) {
    companion object {
        private const val TAG = "SharedFolderManager"
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

    private fun resolveSafeFile(relativePath: String): File {
        val root = getSharedFolderRoot().canonicalFile
        // Reject directory traversal attempts
        if (relativePath.contains("..")) {
            throw SecurityException("Path traversal attempt detected: $relativePath")
        }
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw SecurityException("Access outside shared folder boundary is forbidden: $relativePath")
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
}
