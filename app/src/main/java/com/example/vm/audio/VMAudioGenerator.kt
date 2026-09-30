package com.example.vm.audio

import kotlin.math.sin

object VMAudioGenerator {

    /**
     * Generates 16-bit PCM stereo audio samples for a sine wave tone.
     * @param frequencyHz Tone frequency (e.g., 440Hz for A4, 523Hz for C5)
     * @param durationMs Tone duration in milliseconds
     * @param sampleRate Audio sample rate (e.g. 44100Hz)
     * @param volume Volume level (0.0 to 1.0)
     */
    fun generateTonePcm(
        frequencyHz: Float = 440f,
        durationMs: Int = 200,
        sampleRate: Int = 44100,
        volume: Float = 0.5f
    ): ByteArray {
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val numBytes = numSamples * 4 // 16-bit stereo = 4 bytes per sample (2 left, 2 right)
        val pcmData = ByteArray(numBytes)

        val amplitude = (32767 * volume.coerceIn(0f, 1f)).toInt()
        var byteIdx = 0

        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * i * frequencyHz / sampleRate
            val sampleVal = (sin(angle) * amplitude).toInt().coerceIn(-32768, 32767)

            // Left channel (16-bit little endian)
            pcmData[byteIdx++] = (sampleVal and 0xFF).toByte()
            pcmData[byteIdx++] = ((sampleVal shr 8) and 0xFF).toByte()

            // Right channel (16-bit little endian)
            pcmData[byteIdx++] = (sampleVal and 0xFF).toByte()
            pcmData[byteIdx++] = ((sampleVal shr 8) and 0xFF).toByte()
        }

        return pcmData
    }

    /**
     * Generates a signature MobileVM boot chime sound sequence (C5-E5-G5-C6 harmonic chord).
     */
    fun generateBootChimePcm(sampleRate: Int = 44100): ByteArray {
        val chordFrequencies = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.50f)
        val durationPerNoteMs = 120
        val totalBytesList = mutableListOf<Byte>()

        chordFrequencies.forEach { freq ->
            val noteBytes = generateTonePcm(
                frequencyHz = freq,
                durationMs = durationPerNoteMs,
                sampleRate = sampleRate,
                volume = 0.4f
            )
            for (b in noteBytes) {
                totalBytesList.add(b)
            }
        }

        return totalBytesList.toByteArray()
    }
}
