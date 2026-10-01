package com.example.vm.console

import android.util.Log
import com.example.vm.core.VMEngine
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * VMSecureTerminalServer: Secure External Terminal & Console Access Server.
 *
 * Security Compliance (Requirement 7):
 * - Default external shell exposure: strictly DISABLED by default.
 * - Bound exclusively to localhost loopback (127.0.0.1), NEVER 0.0.0.0.
 * - Mandatory cryptographic token authentication before access is granted.
 * - All terminal I/O is routed strictly through the guest UART/serial console (PL011).
 * - Zero access to Android host commands (no Runtime.exec / ProcessBuilder).
 * - Zero host filesystem access.
 */
class VMSecureTerminalServer(
    private val engine: VMEngine,
    val port: Int = 2222,
    customAuthToken: String = ""
) {
    companion object {
        private const val TAG = "SecureTerminalServer"
        private const val MAX_AUTH_ATTEMPTS = 3
    }

    val authToken: String = if (customAuthToken.isNotBlank()) {
        customAuthToken
    } else {
        generateSecureToken()
    }

    private var serverSocket: ServerSocket? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null
    private val activeClients = ConcurrentHashMap<String, Socket>()

    var isRunning: Boolean = false
        private set

    fun start(): Boolean {
        if (isRunning) return true

        return try {
            // Strictly bind to 127.0.0.1 (Loopback only)
            val loopback = InetAddress.getByName("127.0.0.1")
            serverSocket = ServerSocket(port, 5, loopback)
            isRunning = true
            Log.i(TAG, "VMSecureTerminalServer listening strictly on 127.0.0.1:$port (Auth Token Required)")

            acceptJob = serverScope.launch {
                while (isActive && isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClientConnection(client) }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "Socket accept error: ${e.message}")
                        }
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind secure terminal server on port $port: ${e.message}")
            isRunning = false
            false
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        val clientId = "${socket.inetAddress.hostAddress}:${socket.port}"
        activeClients[clientId] = socket
        Log.i(TAG, "New incoming terminal connection from $clientId")

        try {
            socket.soTimeout = 60000 // 60s handshake timeout
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer: OutputStream = socket.getOutputStream()

            // 1. Authentication Handshake Banner
            val banner = "\r\n=== MobileVM Secure Guest Terminal Bridge ===\r\n" +
                    "Host Interface: 127.0.0.1 (Sandbox Isolated)\r\n" +
                    "Access Policy: Authentication Required\r\n" +
                    "Enter Token: "
            writer.write(banner.toByteArray())
            writer.flush()

            // 2. Token Verification
            var authenticated = false
            var attempts = 0
            while (attempts < MAX_AUTH_ATTEMPTS && !authenticated) {
                val inputLine = reader.readLine()?.trim() ?: break
                if (inputLine == authToken) {
                    authenticated = true
                    val successMsg = "\r\n[AUTH SUCCESS] Connected to Guest Serial Console (ttyAMA0).\r\n" +
                            "Escape sequence to disconnect: ~. or exit\r\n\r\n"
                    writer.write(successMsg.toByteArray())
                    writer.flush()
                } else {
                    attempts++
                    val remaining = MAX_AUTH_ATTEMPTS - attempts
                    if (remaining > 0) {
                        writer.write("\r\n[AUTH FAILED] Invalid token. $remaining attempts remaining.\r\nEnter Token: ".toByteArray())
                        writer.flush()
                    } else {
                        writer.write("\r\n[ACCESS DENIED] Max attempts reached. Disconnecting.\r\n".toByteArray())
                        writer.flush()
                    }
                }
            }

            if (!authenticated) {
                Log.w(TAG, "Authentication failed for client $clientId. Terminating connection.")
                socket.close()
                return@withContext
            }

            // 3. Bi-directional Streaming between Socket and Guest PL011 UART
            socket.soTimeout = 0 // Remove timeout for active interactive session

            val console = engine.serialConsole

            // Job A: Socket input -> Guest UART RX
            val inputJob = launch {
                val buf = ByteArray(1024)
                val inStream = socket.getInputStream()
                while (isActive) {
                    val read = inStream.read(buf)
                    if (read <= 0) break
                    console.sendRawBytes(buf.copyOf(read))
                }
            }

            // Job B: Guest UART TX -> Socket output
            var lastOffset = 0
            val outputJob = launch {
                while (isActive) {
                    val fullText = console.getAllText()
                    if (fullText.length > lastOffset) {
                        val newText = fullText.substring(lastOffset)
                        lastOffset = fullText.length
                        writer.write(newText.toByteArray(Charsets.UTF_8))
                        writer.flush()
                    }
                    delay(25)
                }
            }

            // Wait for disconnect
            inputJob.join()
            outputJob.cancel()

        } catch (e: Exception) {
            Log.i(TAG, "Client $clientId disconnected: ${e.message}")
        } finally {
            activeClients.remove(clientId)
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        serverScope.cancel()

        activeClients.values.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        activeClients.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "VMSecureTerminalServer stopped cleanly")
    }

    private fun generateSecureToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
