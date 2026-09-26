package com.example.vm.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder

interface MemoryBackend {
    val ramSizeMb: Int
    fun read8(address: Long): Byte
    fun write8(address: Long, value: Byte)
    fun read32(address: Long): Int
    fun write32(address: Long, value: Int)
    fun read64(address: Long): Long
    fun write64(address: Long, value: Long)
    fun loadBinary(offset: Long, bytes: ByteArray)
    fun reset()
}

class HostByteBufferMemoryBackend(override val ramSizeMb: Int) : MemoryBackend {
    private val sizeBytes = ramSizeMb.toLong() * 1024L * 1024L
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(sizeBytes.toInt()).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun read8(address: Long): Byte {
        checkBounds(address, 1)
        return buffer.get(address.toInt())
    }

    override fun write8(address: Long, value: Byte) {
        checkBounds(address, 1)
        buffer.put(address.toInt(), value)
    }

    override fun read32(address: Long): Int {
        checkBounds(address, 4)
        return buffer.getInt(address.toInt())
    }

    override fun write32(address: Long, value: Int) {
        checkBounds(address, 4)
        buffer.putInt(address.toInt(), value)
    }

    override fun read64(address: Long): Long {
        checkBounds(address, 8)
        return buffer.getLong(address.toInt())
    }

    override fun write64(address: Long, value: Long) {
        checkBounds(address, 8)
        buffer.putLong(address.toInt(), value)
    }

    override fun loadBinary(offset: Long, bytes: ByteArray) {
        if (offset + bytes.size > sizeBytes) {
            throw IllegalArgumentException("Binary too large for allocated VM RAM")
        }
        buffer.position(offset.toInt())
        buffer.put(bytes)
    }

    override fun reset() {
        buffer.clear()
        // Zero out memory
        for (i in 0 until sizeBytes.toInt()) {
            buffer.put(i, 0)
        }
    }

    private fun checkBounds(address: Long, accessSize: Int) {
        if (address < 0 || address + accessSize > sizeBytes) {
            throw AccessControlException("Access Violation: Attempted to read/write memory out of guest RAM bounds at 0x${address.toString(16)}")
        }
    }
}

class AccessControlException(message: String) : RuntimeException(message)
