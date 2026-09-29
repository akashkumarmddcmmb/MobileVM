package com.example.vm.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VMClipboardManager: Manages bidirectional, secure clipboard exchange between the Android host
 * and guest virtual machine with loop prevention, payload size bounding, and full Unicode support.
 */
class VMClipboardManager(private val context: Context) {
    companion object {
        private const val TAG = "VMClipboardManager"
        const val MAX_CLIPBOARD_CHARS = 262144 // 256 KB max text payload bound
    }

    private val androidClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _guestClipboardText = MutableStateFlow("")
    val guestClipboardText: StateFlow<String> = _guestClipboardText.asStateFlow()

    private val _hostClipboardText = MutableStateFlow("")
    val hostClipboardText: StateFlow<String> = _hostClipboardText.asStateFlow()

    // Loop prevention state
    private var lastSentToGuest: String = ""
    private var lastSentToHost: String = ""

    /**
     * Reads current text from Android system clipboard and stages it for guest ingestion.
     * Prevents infinite bouncing and enforces max payload limit.
     */
    fun syncFromAndroidToGuest(): String {
        return try {
            val clip = androidClipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                var text = clip.getItemAt(0).coerceToText(context).toString()
                if (text.length > MAX_CLIPBOARD_CHARS) {
                    text = text.substring(0, MAX_CLIPBOARD_CHARS)
                }

                // Loop prevention: do not re-send what the guest just sent to host
                if (text == lastSentToHost) {
                    return ""
                }

                lastSentToGuest = text
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
        var safeText = guestText
        if (safeText.length > MAX_CLIPBOARD_CHARS) {
            safeText = safeText.substring(0, MAX_CLIPBOARD_CHARS)
        }

        // Loop prevention: do not re-send what host just sent to guest
        if (safeText == lastSentToGuest) {
            return true
        }

        return try {
            lastSentToHost = safeText
            _guestClipboardText.value = safeText
            val clip = ClipData.newPlainText("MobileVM Guest Clipboard", safeText)
            androidClipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy guest text to Android clipboard: ${e.message}")
            false
        }
    }

    fun clear() {
        lastSentToGuest = ""
        lastSentToHost = ""
        _guestClipboardText.value = ""
        _hostClipboardText.value = ""
    }
}
