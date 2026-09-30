package com.example.vm.audio

import com.example.vm.devices.VirtualDevice

class VirtualAudioDevice(
    private val audioEngine: VMAudioEngine
) : VirtualDevice {

    override fun getDeviceName(): String {
        return "Virtual Sound Card (Intel HDA / AC97 PCM - 44100Hz Stereo)"
    }

    override fun getDeviceStatus(): String {
        return audioEngine.getDetailedStatus()
    }

    override fun reset() {
        audioEngine.flush()
    }
}
