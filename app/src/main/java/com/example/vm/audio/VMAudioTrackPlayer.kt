package com.example.vm.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VMAudioTrackPlayer(
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private var channelConfig: Int = DEFAULT_CHANNEL_CONFIG,
    private var audioFormat: Int = DEFAULT_AUDIO_FORMAT
) {
    companion object {
        private const val TAG = "VMAudioTrackPlayer"
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val QUEUE_CAPACITY = 64
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var audioTrack: AudioTrack? = null
    private val pcmQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    private var volume: Float = 1.0f
    private var isMuted: Boolean = false

    @Volatile
    var totalBytesWritten: Long = 0L
        private set

    @Volatile
    var underrunCount: Long = 0L
        private set

    @Volatile
    var overrunCount: Long = 0L
        private set

    fun initialize(): Boolean {
        synchronized(this) {
            if (audioTrack != null) {
                release()
            }

            try {
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBufferSize <= 0) {
                    Log.e(TAG, "Invalid min buffer size: $minBufferSize for sample rate $sampleRate")
                    return false
                }

                val bufferSize = maxOf(minBufferSize * BUFFER_SIZE_FACTOR, 8192)

                audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                    )
                }

                applyVolumeInternal()
                Log.i(TAG, "AudioTrack initialized cleanly: $sampleRate Hz, buffer $bufferSize bytes")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
                return false
            }
        }
    }

    fun start(): Boolean {
        synchronized(this) {
            if (audioTrack == null && !initialize()) {
                return false
            }

            if (isPlaying.get()) return true

            try {
                audioTrack?.play()
                isPlaying.set(true)
                isPaused.set(false)

                playbackThread = Thread({
                    runPlaybackLoop()
                }, "MobileVM-AudioThread").apply {
                    priority = Thread.MAX_PRIORITY - 1
                    start()
                }

                Log.i(TAG, "Audio playback started successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error starting AudioTrack playback: ${e.message}", e)
                return false
            }
        }
    }

    fun writePcm(pcmChunk: ByteArray): Boolean {
        if (!isPlaying.get() || pcmChunk.isEmpty()) return false

        val offerSuccess = pcmQueue.offer(pcmChunk)
        if (!offerSuccess) {
            // Buffer overrun: Queue is full, drop oldest frame to maintain low latency
            overrunCount++
            pcmQueue.poll()
            pcmQueue.offer(pcmChunk)
        }
        return true
    }

    private fun runPlaybackLoop() {
        while (isPlaying.get()) {
            try {
                if (isPaused.get()) {
                    Thread.sleep(20)
                    continue
                }

                val chunk = pcmQueue.poll(50, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    val track = audioTrack ?: break
                    val currentVolume = if (isMuted) 0.0f else volume
                    if (currentVolume == 0.0f) {
                        // Volume muted or 0: Skip writing to hardware speaker to save energy, but count bytes
                        totalBytesWritten += chunk.size
                        continue
                    }

                    val bytesWritten = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        track.write(chunk, 0, chunk.size)
                    }

                    if (bytesWritten > 0) {
                        totalBytesWritten += bytesWritten
                    } else if (bytesWritten < 0) {
                        Log.w(TAG, "AudioTrack write error code: $bytesWritten")
                        underrunCount++
                    }
                } else {
                    // Buffer underrun: no PCM data received within timeout
                    underrunCount++
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Audio playback loop interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Exception in audio playback loop: ${e.message}", e)
            }
        }
    }

    fun pause() {
        isPaused.set(true)
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing AudioTrack: ${e.message}")
        }
    }

    fun resume() {
        isPaused.set(false)
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming AudioTrack: ${e.message}")
        }
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0.0f, 1.0f)
        applyVolumeInternal()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyVolumeInternal()
    }

    fun getVolume(): Float = volume
    fun isMuted(): Boolean = isMuted

    private fun applyVolumeInternal() {
        val targetVol = if (isMuted) 0.0f else volume
        try {
            audioTrack?.let { track ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    track.setVolume(targetVol)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(targetVol, targetVol)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting volume on AudioTrack: ${e.message}")
        }
    }

    fun flush() {
        pcmQueue.clear()
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
        }
    }

    fun release() {
        isPlaying.set(false)
        isPaused.set(false)

        playbackThread?.interrupt()
        playbackThread = null

        pcmQueue.clear()

        synchronized(this) {
            try {
                audioTrack?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        track.stop()
                        track.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }
        }
        Log.i(TAG, "AudioTrack resources cleanly released")
    }

    fun getStatus(): String {
        val stateStr = when {
            audioTrack == null -> "UNINITIALIZED"
            isPaused.get() -> "PAUSED"
            isPlaying.get() -> "PLAYING"
            else -> "STOPPED"
        }
        return "$stateStr | ${sampleRate}Hz | Vol: ${(volume * 100).toInt()}% | Muted: $isMuted | Played: ${totalBytesWritten / 1024} KB"
    }
}
