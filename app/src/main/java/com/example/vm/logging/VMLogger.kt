package com.example.vm.logging

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogCategory(val displayName: String) {
    UI("UI"),
    KOTLIN("Kotlin Core"),
    JNI("JNI Bridge"),
    NATIVE("Native Engine"),
    CPU("CPU Execution"),
    DEVICE("Virtual Devices"),
    STORAGE("Storage / Disk"),
    NETWORK("Networking"),
    BOOT("Linux Boot")
}

data class VMLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val category: LogCategory,
    val level: String, // "INFO", "WARN", "ERROR", "DEBUG"
    val tag: String,
    val message: String
) {
    fun toFormattedString(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return "[${sdf.format(Date(timestamp))}] [${category.displayName}] [$level] $tag: $message"
    }
}

/**
 * VMLogger: Structured, category-separated logging buffer for MobileVM.
 * Thread-safe with circular capacity to prevent memory bloat.
 */
object VMLogger {
    private const val MAX_LOG_ENTRIES = 1000
    private val buffer = ConcurrentLinkedDeque<VMLogEntry>()

    private val _logsFlow = MutableStateFlow<List<VMLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<VMLogEntry>> = _logsFlow.asStateFlow()

    fun log(category: LogCategory, level: String, tag: String, message: String) {
        val entry = VMLogEntry(
            category = category,
            level = level,
            tag = tag,
            message = sanitizeMessage(message)
        )
        buffer.addLast(entry)
        while (buffer.size > MAX_LOG_ENTRIES) {
            buffer.pollFirst()
        }
        _logsFlow.value = buffer.toList()

        when (level) {
            "ERROR" -> Log.e(tag, "[${category.displayName}] $message")
            "WARN" -> Log.w(tag, "[${category.displayName}] $message")
            "DEBUG" -> Log.d(tag, "[${category.displayName}] $message")
            else -> Log.i(tag, "[${category.displayName}] $message")
        }
    }

    fun sanitize(msg: String): String = sanitizeMessage(msg)

    fun sanitizeMessage(msg: String): String {
        // Strip out private keys, authorization tokens or user passwords
        return msg
            .replace("(?i)password=[^&\\s]+".toRegex(), "password=***")
            .replace("(?i)token=[^&\\s]+".toRegex(), "token=***")
    }

    fun getLogs(categoryFilter: LogCategory? = null): List<VMLogEntry> {
        return if (categoryFilter == null) {
            buffer.toList()
        } else {
            buffer.filter { it.category == categoryFilter }
        }
    }

    fun getFormattedLogs(categoryFilter: LogCategory? = null): String {
        return getLogs(categoryFilter).joinToString("\n") { it.toFormattedString() }
    }

    fun clearLogs() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }

    fun exportLogsToFile(context: Context): File {
        val dir = File(context.cacheDir, "exported_logs").apply { mkdirs() }
        val logFile = File(dir, "mobilevm_logs_${System.currentTimeMillis()}.txt")
        logFile.writeText(getFormattedLogs())
        return logFile
    }

    fun shareLogs(context: Context) {
        val file = exportLogsToFile(context)
        val text = getFormattedLogs()
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share MobileVM Diagnostic Logs").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
