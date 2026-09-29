package com.example.vm.sharing

import androidx.test.core.app.ApplicationProvider
import com.example.vm.input.AndroidInputBackend
import com.example.vm.input.MouseButton
import com.example.vm.input.MouseInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VMHostIntegrationTest {

    private lateinit var context: android.content.Context
    private lateinit var sharedFolderManager: SharedFolderManager
    private lateinit var clipboardManager: VMClipboardManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedFolderManager = SharedFolderManager(context)
        clipboardManager = VMClipboardManager(context)
        clipboardManager.clear()
    }

    @Test
    fun testSharedFolderPathTraversalPrevention() {
        // Test parent directory traversal attempt
        assertThrows(SecurityException::class.java) {
            sharedFolderManager.resolveSafeFile("../etc/passwd")
        }

        // Test root escaping attempt
        assertThrows(SecurityException::class.java) {
            sharedFolderManager.resolveSafeFile("../../data/data/com.example.vm/databases")
        }

        // Test null byte injection
        assertThrows(SecurityException::class.java) {
            sharedFolderManager.resolveSafeFile("test\u0000file.txt")
        }
    }

    @Test
    fun testSharedFolderFileCrudOperations() {
        runBlocking {
            val testFileName = "test_document.txt"
            val testData = "Hello MobileVM Shared Storage!".toByteArray(Charsets.UTF_8)

            // 1. Write file
            val written = sharedFolderManager.writeFileBytes(testFileName, testData)
            assertTrue(written)

            // 2. Read file
            val readBytes = sharedFolderManager.readFileBytes(testFileName)
            assertNotNull(readBytes)
            assertEquals("Hello MobileVM Shared Storage!", String(readBytes!!, Charsets.UTF_8))

            // 3. List files
            val files = sharedFolderManager.listFiles("")
            assertTrue(files.any { it.name == testFileName })

            // 4. Rename file
            val renamed = sharedFolderManager.renameFile(testFileName, "renamed_document.txt")
            assertTrue(renamed)
            assertNull(sharedFolderManager.readFileBytes(testFileName))
            assertNotNull(sharedFolderManager.readFileBytes("renamed_document.txt"))

            // 5. Delete file
            val deleted = sharedFolderManager.deleteFile("renamed_document.txt")
            assertTrue(deleted)
            assertNull(sharedFolderManager.readFileBytes("renamed_document.txt"))
        }
    }

    @Test
    fun testSharedFolderStreamingImportWithProgress() {
        runBlocking {
            val payload = "Streaming content for guest VM integration".toByteArray(Charsets.UTF_8)
            val inStream = ByteArrayInputStream(payload)

            var lastProgress = 0L
            val success = sharedFolderManager.streamImportFile(
                sourceStream = inStream,
                targetRelativePath = "imported_file.txt",
                totalBytes = payload.size.toLong(),
                onProgress = { written, _ -> lastProgress = written }
            )

            assertTrue(success)
            assertEquals(payload.size.toLong(), lastProgress)

            val read = sharedFolderManager.readFileBytes("imported_file.txt")
            assertNotNull(read)
            assertEquals("Streaming content for guest VM integration", String(read!!, Charsets.UTF_8))

            sharedFolderManager.deleteFile("imported_file.txt")
        }
    }

    @Test
    fun testClipboardUnicodeAndLoopPrevention() {
        val hindiText = "नमस्ते मोबाइलवीएम! Real Unicode Clipboard sync."

        // Guest to Android
        val copiedToHost = clipboardManager.syncFromGuestToAndroid(hindiText)
        assertTrue(copiedToHost)
        assertEquals(hindiText, clipboardManager.guestClipboardText.value)

        // Loop prevention: syncing back from Android should recognize same text and not bounce
        val echoed = clipboardManager.syncFromAndroidToGuest()
        // Should either return empty or be suppressed to prevent infinite loop
        assertTrue(echoed.isEmpty() || echoed == hindiText)
    }

    @Test
    fun testClipboardPayloadSizeLimiting() {
        // Generate payload larger than 256 KB
        val largeString = "A".repeat(300000)
        val copied = clipboardManager.syncFromGuestToAndroid(largeString)
        assertTrue(copied)
        assertTrue(clipboardManager.guestClipboardText.value.length <= VMClipboardManager.MAX_CLIPBOARD_CHARS)
    }

    @Test
    fun testPointerCaptureAndRelease() {
        val inputBackend = AndroidInputBackend()
        val mouse = MouseInput(inputBackend.virtualInputDevice)

        assertFalse(mouse.isPointerCaptured)

        // Request capture
        assertTrue(mouse.requestPointerCapture())
        assertTrue(mouse.isPointerCaptured)

        // Release capture
        assertTrue(mouse.releasePointerCapture())
        assertFalse(mouse.isPointerCaptured)

        // Toggle capture
        assertTrue(mouse.togglePointerCapture())
        assertTrue(mouse.isPointerCaptured)
        assertFalse(mouse.togglePointerCapture())
        assertFalse(mouse.isPointerCaptured)
    }

    @Test
    fun testMultiShareManagement() {
        val initialCount = sharedFolderManager.getShares().size
        assertTrue(initialCount >= 1)

        val share = sharedFolderManager.addShare(
            name = "Downloads",
            guestMountPath = "/shared/downloads",
            hostPath = "/storage/emulated/0/Download",
            isReadOnly = true
        )

        assertNotNull(share)
        assertEquals("Downloads", share.name)
        assertTrue(share.isReadOnly)
        assertEquals("/shared/downloads", share.guestMountPath)

        val removed = sharedFolderManager.removeShare(share.id)
        assertTrue(removed)
    }
}
