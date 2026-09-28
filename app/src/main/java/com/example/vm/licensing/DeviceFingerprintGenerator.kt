package com.example.vm.licensing

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Mobile BM Device Hardware Fingerprint Generator.
 *
 * Produces a stable, privacy-preserving cryptographic device identifier
 * bound to the physical hardware and package namespace.
 *
 * Used for:
 *  - Machine-locking license activations.
 *  - Preventing multi-device license cloning.
 *  - Encrypting local offline license vault data.
 */
object DeviceFingerprintGenerator {

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown_android_id"

            // Gather immutable hardware attributes
            val hardwareSeed = buildString {
                append(androidId)
                append(":")
                append(Build.BOARD)
                append(":")
                append(Build.BRAND)
                append(":")
                append(Build.DEVICE)
                append(":")
                append(Build.HARDWARE)
                append(":")
                append(Build.MANUFACTURER)
                append(":")
                append(Build.MODEL)
                append(":")
                append(context.packageName)
            }

            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(hardwareSeed.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02X".format(it) }

            // Format into clean MBM hardware device ID: MBM-DEV-1234ABCD-5678EF01
            "MBM-DEV-${hex.substring(0, 8)}-${hex.substring(8, 16)}"
        } catch (e: Exception) {
            "MBM-DEV-GENERIC-${context.packageName.hashCode().toLong() and 0xFFFFFFFFL}"
        }
    }

    fun getDeviceFriendlyName(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        val model = Build.MODEL ?: "Device"
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
}
