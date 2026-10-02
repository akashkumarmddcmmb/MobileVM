package com.example.vm.core

enum class VMState {
    CREATED,
    VALIDATING,
    PROVISIONING,
    CONFIGURED,
    READY,
    STARTING,
    FIRMWARE_READY,
    DEVICES_READY,
    BOOTING,
    KERNEL_STARTED,
    INIT_STARTED,
    ROOTFS_MOUNTED,
    USERSPACE_READY,
    RUNNING,
    PAUSED,
    SAVING,
    RESTORING,
    SHUTTING_DOWN,
    REBOOTING,
    CRASH_DETECTED,
    STOPPING,
    STOPPED,
    ERROR,
    FAILED,
    NOT_VERIFIED;

    fun isTerminal(): Boolean = this == STOPPED || this == ERROR || this == FAILED || this == NOT_VERIFIED

    fun isActive(): Boolean = this == RUNNING || this == STARTING || this == BOOTING || 
                              this == FIRMWARE_READY || this == DEVICES_READY ||
                              this == KERNEL_STARTED || this == INIT_STARTED || 
                              this == ROOTFS_MOUNTED || this == USERSPACE_READY || 
                              this == PAUSED || this == SAVING || this == RESTORING ||
                              this == SHUTTING_DOWN || this == REBOOTING

    fun canStart(): Boolean = this == CREATED || this == CONFIGURED || this == READY || 
                              this == STOPPED || this == ERROR || this == FAILED || this == NOT_VERIFIED ||
                              this == CRASH_DETECTED
    fun canStop(): Boolean = this == RUNNING || this == PAUSED || this == STARTING || 
                             this == FIRMWARE_READY || this == DEVICES_READY ||
                             this == BOOTING || this == KERNEL_STARTED || this == INIT_STARTED || 
                             this == ROOTFS_MOUNTED || this == USERSPACE_READY || this == SAVING ||
                             this == SHUTTING_DOWN || this == REBOOTING || this == CRASH_DETECTED
    fun canPause(): Boolean = this == RUNNING
    fun canResume(): Boolean = this == PAUSED
    fun canReset(): Boolean = this == RUNNING || this == PAUSED || this == STARTING || 
                              this == FIRMWARE_READY || this == DEVICES_READY ||
                              this == BOOTING || this == KERNEL_STARTED || this == INIT_STARTED || 
                              this == ROOTFS_MOUNTED || this == USERSPACE_READY ||
                              this == SHUTTING_DOWN || this == REBOOTING
    fun canSaveSnapshot(): Boolean = this == RUNNING || this == PAUSED
    fun canRestoreSnapshot(): Boolean = this == STOPPED || this == PAUSED
}
