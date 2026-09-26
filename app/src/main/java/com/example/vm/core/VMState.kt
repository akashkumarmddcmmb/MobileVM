package com.example.vm.core

enum class VMState {
    CREATED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    STOPPED,
    ERROR;

    fun isTerminal(): Boolean = this == STOPPED || this == ERROR

    fun canStart(): Boolean = this == CREATED || this == STOPPED || this == ERROR
    fun canStop(): Boolean = this == RUNNING || this == PAUSED
    fun canPause(): Boolean = this == RUNNING
    fun canResume(): Boolean = this == PAUSED
    fun canReset(): Boolean = this == RUNNING || this == PAUSED
}
