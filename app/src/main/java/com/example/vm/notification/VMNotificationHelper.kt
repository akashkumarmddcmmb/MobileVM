package com.example.vm.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.vm.core.VMState

/**
 * VMNotificationHelper: Posts and manages user-facing status notifications
 * for active virtual machine sessions without leaving orphan foreground tasks.
 */
object VMNotificationHelper {
    const val CHANNEL_ID = "mobilevm_status_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MobileVM Engine Status"
            val descriptionText = "Displays real-time hypervisor and guest operating system execution status."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateVMStatusNotification(
        context: Context,
        vmName: String,
        state: VMState,
        cpuBackend: String = "ARM64 Core"
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel notification if VM is in terminal state
        if (state == VMState.STOPPED || state == VMState.NOT_VERIFIED) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        createNotificationChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (statusTitle, statusText) = when (state) {
            VMState.RUNNING -> Pair("VM Running: $vmName", "Backend: $cpuBackend • Console active")
            VMState.STARTING -> Pair("VM Starting: $vmName", "Initializing CPU registers and guest memory...")
            VMState.PAUSED -> Pair("VM Paused: $vmName", "Virtual CPU cycles halted • Memory preserved")
            VMState.STOPPING -> Pair("VM Stopping: $vmName", "Syncing disks and releasing hypervisor memory...")
            VMState.ERROR -> Pair("VM Error: $vmName", "Execution halted due to kernel/memory exception")
            else -> Pair("MobileVM: $vmName", state.name)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(statusTitle)
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(state == VMState.RUNNING || state == VMState.STARTING)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun clearNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
