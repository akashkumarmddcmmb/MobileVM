package com.example.vm.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbPermissionManager"
        const val ACTION_USB_PERMISSION = "com.example.vm.USB_PERMISSION"
    }

    private val pendingCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private var isReceiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(recvContext: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this@UsbPermissionManager) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let { dev ->
                        val devKey = dev.deviceName
                        Log.i(TAG, "Permission result for device $devKey: granted=$granted")
                        val callback = pendingCallbacks.remove(devKey)
                        callback?.invoke(granted)
                    }
                }
            }
        }
    }

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permissionReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (hasPermission(device)) {
            Log.d(TAG, "Device ${device.deviceName} already has permission granted.")
            onResult(true)
            return
        }

        synchronized(this) {
            pendingCallbacks[device.deviceName] = onResult
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)

        Log.i(TAG, "Requesting USB permission for ${device.deviceName} (Vendor: 0x${device.vendorId.toString(16)}, Product: 0x${device.productId.toString(16)})")
        usbManager.requestPermission(device, permissionIntent)
    }

    fun cleanup() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering USB permission receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
        synchronized(this) {
            pendingCallbacks.clear()
        }
    }
}
