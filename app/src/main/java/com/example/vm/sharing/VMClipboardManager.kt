package com.example.vm.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VMClipboardManager: Manages bidirectional clipboard exchange between the Android host
 * and guest virtual machine.
 */
class VMClipboardManager(private val context: Context) {
    companion object {
        private const val TAG = "VMClipboardManager"
    }

    private val androidClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _guestClipboardText = MutableStateFlow("")
    val guestClipboardText: StateFlow<String> = _guestClipboardText.asStateFlow()

    private val _hostClipboardText = MutableStateFlow("")
    val hostClipboardText: StateFlow<String> = _hostClipboardText.asStateFlow()

    /**
     * Reads current text from Android system clipboard and stages it for guest ingestion.
     */
    fun syncFromAndroidToGuest(): String {
        return try {
            val clip = androidClipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                _hostClipboardText.value = text
                text
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Android host clipboard: ${e.message}")
            ""
        }
    }

    /**
     * Receives clipboard payload emitted by guest OS agent or terminal selection
     * and sets it onto the Android host clipboard.
     */
    fun syncFromGuestToAndroid(guestText: String): Boolean {
        if (guestText.isEmpty()) return false
        return try {
            _guestClipboardText.value = guestText
            val clip = ClipData.newPlainText("MobileVM Guest Clipboard", guestText)
            androidClipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy guest text to Android clipboard: ${e.message}")
            false
        }
    }
}
