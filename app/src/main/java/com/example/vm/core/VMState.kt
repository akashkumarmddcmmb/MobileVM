package com.example.vm.core

enum class VMState {
    READY,
    BOOTING,
    RUNNING,
    PAUSED,
    STOPPED,
    ERROR,
    NOT_VERIFIED;

    fun isTerminal(): Boolean = this == STOPPED || this == ERROR || this == NOT_VERIFIED

    fun canStart(): Boolean = this == READY || this == STOPPED || this == ERROR || this == NOT_VERIFIED
    fun canStop(): Boolean = this == RUNNING || this == PAUSED || this == BOOTING
    fun canPause(): Boolean = this == RUNNING
    fun canResume(): Boolean = this == PAUSED
    fun canReset(): Boolean = this == RUNNING || this == PAUSED || this == BOOTING
}
