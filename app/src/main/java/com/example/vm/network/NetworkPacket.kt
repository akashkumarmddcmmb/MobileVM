package com.example.vm.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data structures and utility functions for parsing and constructing Ethernet,
 * ARP, IPv4, UDP, TCP, and ICMP network frames safely without buffer overflows.
 */
object NetworkPacketUtils {

    const val ETHERTYPE_IPv4 = 0x0800
    const val ETHERTYPE_ARP  = 0x0806

    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_TCP  = 6
    const val PROTOCOL_UDP  = 17

    const val MIN_ETHERNET_FRAME_SIZE = 14
    const val MAX_ETHERNET_FRAME_SIZE = 1514

    /**
     * Calculates the 16-bit 1's complement Internet Checksum over a byte array segment.
     */
    fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        var remaining = length

        while (remaining > 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
            remaining -= 2
        }

        if (remaining == 1) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Parses a colon-separated MAC address string (e.g. "52:54:00:12:34:56") to 6 bytes.
     */
    fun parseMac(macStr: String): ByteArray {
        val bytes = ByteArray(6)
        val parts = macStr.split(":")
        for (i in 0 until minOf(6, parts.size)) {
            try {
                bytes[i] = parts[i].toInt(16).toByte()
            } catch (e: Exception) {
                bytes[i] = 0
            }
        }
        return bytes
    }

    /**
     * Formats 6 MAC bytes to string "XX:XX:XX:XX:XX:XX".
     */
    fun formatMac(bytes: ByteArray, offset: Int = 0): String {
        if (bytes.size < offset + 6) return "00:00:00:00:00:00"
        return String.format(
            java.util.Locale.US,
            "%02X:%02X:%02X:%02X:%02X:%02X",
            bytes[offset].toInt() and 0xFF,
            bytes[offset + 1].toInt() and 0xFF,
            bytes[offset + 2].toInt() and 0xFF,
            bytes[offset + 3].toInt() and 0xFF,
            bytes[offset + 4].toInt() and 0xFF,
            bytes[offset + 5].toInt() and 0xFF
        )
    }

    /**
     * Converts a 32-bit integer IP to string "A.B.C.D".
     */
    fun formatIp(ipInt: Int): String {
        return String.format(
            java.util.Locale.US,
            "%d.%d.%d.%d",
            (ipInt ushr 24) and 0xFF,
            (ipInt ushr 16) and 0xFF,
            (ipInt ushr 8) and 0xFF,
            ipInt and 0xFF
        )
    }

    /**
     * Parses IP string "A.B.C.D" to 32-bit integer.
     */
    fun parseIp(ipStr: String): Int {
        val parts = ipStr.split(".")
        if (parts.size != 4) return 0
        return try {
            ((parts[0].toInt() and 0xFF) shl 24) or
            ((parts[1].toInt() and 0xFF) shl 16) or
            ((parts[2].toInt() and 0xFF) shl 8) or
            (parts[3].toInt() and 0xFF)
        } catch (e: Exception) {
            0
        }
    }
}
