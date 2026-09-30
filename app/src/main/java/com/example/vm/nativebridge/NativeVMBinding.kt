package com.example.vm.nativebridge

import android.util.Log

object NativeVMBinding {
    private const val TAG = "NativeVMBinding"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("mobilevm_native")
            isNativeLoaded = true
            Log.i(TAG, "Native library libmobilevm_native.so successfully loaded.")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not found or running on simulated JVM test environment: ${e.message}")
            isNativeLoaded = false
        }
    }

    fun isLoaded(): Boolean = isNativeLoaded

    external fun nativeCreateVM(
        ramMb: Int,
        diskPath: String,
        numCores: Int,
        guestArchCode: Int,
        useHardwareVirt: Boolean,
        kernelPath: String,
        initramfsPath: String,
        cmdline: String,
        consoleDev: String
    ): Long

    external fun nativeConfigure(handle: Long): Boolean
    external fun nativeStart(handle: Long): Boolean
    external fun nativePause(handle: Long): Boolean
    external fun nativeResume(handle: Long): Boolean
    external fun nativeStop(handle: Long): Boolean
    external fun nativeReset(handle: Long): Boolean
    external fun nativeDestroy(handle: Long)
    external fun nativeStepCycles(handle: Long, maxCycles: Int): Int
    external fun nativeGetState(handle: Long): Int
    external fun nativeGetRegisters(handle: Long): LongArray?
    external fun nativeFetchSerialTx(handle: Long): ByteArray?
    external fun nativeWriteSerialRx(handle: Long, rxByte: Byte)
    external fun nativeCopyFramebuffer(handle: Long, outPixels: IntArray): Boolean
    external fun nativeIsKvmSupported(): Boolean
    external fun nativeGetKvmReason(): String
    external fun nativeGetHostArchitecture(): Int
    external fun nativeIsFallbackEmulation(handle: Long): Boolean
    external fun nativeGetBackendStatus(handle: Long): String
    external fun nativeIsHardwareAccelerated(handle: Long): Boolean
    external fun nativeGetBackendDescription(handle: Long): String

    // Raw Virtual Disk operations
    external fun nativeCreateDiskImage(diskPath: String, sizeBytes: Long, sparse: Boolean): Boolean
    external fun nativeGetDiskSectorCount(handle: Long): Long
    external fun nativeGetDiskReadSectors(handle: Long): Long
    external fun nativeGetDiskWrittenSectors(handle: Long): Long
    external fun nativeReadSectorBytes(diskPath: String, lba: Long, count: Int): ByteArray?

    // Linux Kernel verification
    external fun nativeVerifyKernelImage(kernelPath: String): String

    // Virtual Input (Touch / Mouse / Keyboard)
    external fun nativeSendTouchEvent(handle: Long, action: Int, x: Float, y: Float, pressure: Float, pointerId: Int)
    external fun nativeSendMouseEvent(handle: Long, buttonMask: Int, dx: Int, dy: Int, absX: Int, absY: Int, wheelDelta: Int = 0)
    external fun nativeSendKeyEvent(handle: Long, scanCode: Int, isDown: Boolean)
    external fun nativeGetInputEventCount(handle: Long): Long

    // Virtual Audio (PCM Output)
    external fun nativeFetchAudioPcm(handle: Long): ByteArray?
    external fun nativeWriteAudioPcm(handle: Long, pcmData: ByteArray)
}
