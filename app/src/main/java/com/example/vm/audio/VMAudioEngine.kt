package com.example.vm.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class VMAudioEngine(
    private val context: Context,
    private val sampleRate: Int = VMAudioTrackPlayer.DEFAULT_SAMPLE_RATE
) {
    companion object {
        private const val TAG = "VMAudioEngine"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val player = VMAudioTrackPlayer(sampleRate = sampleRate)

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isEngineStarted = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i(TAG, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck volume to 20%
                player.setVolume(0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                player.setVolume(1.0f)
                player.resume()
            }
        }
    }

    fun start(): Boolean {
        synchronized(this) {
            requestAudioFocus()

            val playerStarted = player.start()
            if (playerStarted) {
                isEngineStarted = true
                Log.i(TAG, "VMAudioEngine started cleanly (passive listening for application PCM stream)")
            } else {
                Log.e(TAG, "VMAudioEngine failed to start audio player")
            }
            return playerStarted
        }
    }

    fun playBootChime() {
        val bootPcm = VMAudioGenerator.generateBootChimePcm(sampleRate)
        player.writePcm(bootPcm)
    }

    fun writePcmData(pcmData: ByteArray): Boolean {
        if (!isEngineStarted) return false
        return player.writePcm(pcmData)
    }

    fun pause() {
        synchronized(this) {
            player.pause()
            Log.i(TAG, "VMAudioEngine paused")
        }
    }

    fun resume() {
        synchronized(this) {
            requestAudioFocus()
            player.resume()
            Log.i(TAG, "VMAudioEngine resumed")
        }
    }

    fun stop() {
        synchronized(this) {
            player.flush()
            player.release()
            abandonAudioFocus()
            isEngineStarted = false
            Log.i(TAG, "VMAudioEngine stopped and resources released")
        }
    }

    fun setVolume(volume: Float) {
        player.setVolume(volume)
    }

    fun setMuted(muted: Boolean) {
        player.setMuted(muted)
    }

    fun getVolume(): Float = player.getVolume()
    fun isMuted(): Boolean = player.isMuted()

    fun flush() {
        player.flush()
    }

    private fun requestAudioFocus() {
        if (audioManager == null) return

        try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()

                audioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.i(TAG, "Audio focus requested result: $result (hasFocus: $hasAudioFocus)")
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        if (audioManager == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
            hasAudioFocus = false
            Log.i(TAG, "Audio focus abandoned cleanly")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    fun getDetailedStatus(): String {
        return "Audio Focus: $hasAudioFocus | " + player.getStatus()
    }
}
