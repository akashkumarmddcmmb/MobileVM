package com.example.vm.core

enum class VMState {
    CREATED,
    CONFIGURED,
    STARTING,
    RUNNING,
    PAUSED,
    SAVING,
    RESTORING,
    STOPPING,
    STOPPED,
    ERROR,
    NOT_VERIFIED;

    fun isTerminal(): Boolean = this == STOPPED || this == ERROR || this == NOT_VERIFIED

    fun canStart(): Boolean = this == CREATED || this == CONFIGURED || this == STOPPED || this == ERROR || this == NOT_VERIFIED
    fun canStop(): Boolean = this == RUNNING || this == PAUSED || this == STARTING || this == SAVING
    fun canPause(): Boolean = this == RUNNING
    fun canResume(): Boolean = this == PAUSED
    fun canReset(): Boolean = this == RUNNING || this == PAUSED || this == STARTING
    fun canSaveSnapshot(): Boolean = this == RUNNING || this == PAUSED
    fun canRestoreSnapshot(): Boolean = this == STOPPED || this == PAUSED
}
