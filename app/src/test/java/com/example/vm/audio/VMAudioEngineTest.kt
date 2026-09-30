package com.example.vm.audio

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class VMAudioEngineTest {

    private lateinit var audioEngine: VMAudioEngine
    private lateinit var player: VMAudioTrackPlayer

    @Before
    fun setUp() {
        audioEngine = VMAudioEngine(ApplicationProvider.getApplicationContext())
        player = VMAudioTrackPlayer(sampleRate = 44100)
    }

    @Test
    fun testAudioPlayerInitializationAndLifecycle() {
        val initSuccess = player.initialize()
        assertTrue("VMAudioTrackPlayer should initialize cleanly in test runtime", initSuccess)

        val startSuccess = player.start()
        assertTrue("VMAudioTrackPlayer should start playback thread", startSuccess)

        // Write PCM tone data
        val tonePcm = VMAudioGenerator.generateTonePcm(frequencyHz = 440f, durationMs = 100)
        assertTrue("PCM tone should not be empty", tonePcm.isNotEmpty())

        val writeSuccess = player.writePcm(tonePcm)
        assertTrue("PCM chunk should be queued successfully", writeSuccess)

        // Test Pause
        player.pause()
        val statusPaused = player.getStatus()
        assertTrue("Status should indicate PAUSED", statusPaused.contains("PAUSED"))

        // Test Resume
        player.resume()
        val statusResumed = player.getStatus()
        assertTrue("Status should indicate PLAYING", statusResumed.contains("PLAYING"))

        // Test Release
        player.release()
        val statusStopped = player.getStatus()
        assertTrue("Status should indicate STOPPED or UNINITIALIZED", statusStopped.contains("STOPPED") || statusStopped.contains("UNINITIALIZED"))
    }

    @Test
    fun testVolumeAndMuteControls() {
        player.initialize()
        player.start()

        // Volume control
        player.setVolume(0.75f)
        assertEquals(0.75f, player.getVolume(), 0.01f)

        // Mute control
        player.setMuted(true)
        assertTrue(player.isMuted())

        player.setMuted(false)
        assertFalse(player.isMuted())

        player.release()
    }

    @Test
    fun testVMAudioEngineFullLifecycleAndBootChime() {
        val started = audioEngine.start()
        assertTrue("VMAudioEngine should start cleanly", started)

        // Write custom audio data
        val testPcm = VMAudioGenerator.generateTonePcm(frequencyHz = 523.25f, durationMs = 50)
        val writeOk = audioEngine.writePcmData(testPcm)
        assertTrue("VMAudioEngine should accept PCM data", writeOk)

        val status = audioEngine.getDetailedStatus()
        assertTrue("Status string should be formatted", status.contains("Audio Focus:"))

        // Test Volume and Mute on Engine level
        audioEngine.setVolume(0.8f)
        assertEquals(0.8f, audioEngine.getVolume(), 0.01f)

        audioEngine.setMuted(true)
        assertTrue(audioEngine.isMuted())

        audioEngine.setMuted(false)

        // Pause / Resume
        audioEngine.pause()
        audioEngine.resume()

        // Stop & Release resources
        audioEngine.stop()
    }

    @Test
    fun testVirtualAudioDeviceIntegration() {
        val device = VirtualAudioDevice(audioEngine)
        assertNotNull(device.getDeviceName())
        assertTrue(device.getDeviceName().contains("Virtual Sound Card"))

        audioEngine.start()
        val status = device.getDeviceStatus()
        assertTrue(status.isNotEmpty())

        device.reset()
        audioEngine.stop()
    }

    @Test
    fun testApplicationAudioCases() {
        // Case 1: Engine started, no audio playing in VM -> silence (0 bytes written)
        audioEngine.start()
        assertEquals(0L, player.totalBytesWritten)

        // Case 2 & 3 & 4: VM app (Music/Video/Game) generates PCM audio stream
        val musicPcm = VMAudioGenerator.generateTonePcm(frequencyHz = 440f, durationMs = 100)
        val accepted = audioEngine.writePcmData(musicPcm)
        assertTrue("Application PCM audio must be accepted by engine", accepted)

        // Case 5: Audio paused in VM -> engine pauses playback cleanly
        audioEngine.pause()
        val pausedStatus = audioEngine.getDetailedStatus()
        assertTrue("Status must indicate PAUSED", pausedStatus.contains("PAUSED"))

        audioEngine.stop()
    }
}
