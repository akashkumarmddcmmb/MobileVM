package com.example.vm.cpu

import com.example.vm.devices.DeviceManager
import com.example.vm.memory.MemoryBackend

interface CPUBackend {
    var pc: Long
    var sp: Long
    val registers: LongArray // X0 to X31
    var isHalted: Boolean
    var isPaused: Boolean
    
    fun stepInstruction(memory: MemoryBackend, deviceManager: DeviceManager): String
    fun getBackendName(): String
    fun isHardwareAccelerated(): Boolean
    fun reset()
}

class InterpreterArm64CPUBackend : CPUBackend {
    override var pc: Long = 0L
    override var sp: Long = 0xFFFFFFF0L
    override val registers = LongArray(32)
    override var isHalted = false
    override var isPaused = false

    override fun getBackendName(): String = "Instruction-level ARM64 Interpreter"

    override fun isHardwareAccelerated(): Boolean = false

    override fun reset() {
        pc = 0L
        sp = 0xFFFFFFF0L
        registers.fill(0L)
        isHalted = false
        isPaused = false
    }

    /**
     * Fetches, decodes, and executes a single 32-bit instruction word from memory.
     * Returns a disassembly or status description of the executed instruction.
     */
    override fun stepInstruction(memory: MemoryBackend, deviceManager: DeviceManager): String {
        if (isHalted) return "HALTED"
        if (isPaused) return "PAUSED"

        val instructionWord = try {
            memory.read32(pc)
        } catch (e: Exception) {
            isHalted = true
            return "TRAP: Instruction Page Fault at 0x${pc.toString(16)}"
        }

        val opcode = instructionWord and -0x1000000 // Upper 8 bits
        val disassembled: String

        when {
            // HLT (0xD4400000)
            instructionWord == -0x2bc00000.toInt() -> { // 0xD4400000 signed
                isHalted = true
                disassembled = "HLT #0 (System shutdown requested by guest OS)"
                // MMIO power control register write for graceful shutdown
                deviceManager.powerControllerWrite(0x02) 
            }

            // PAUSE (0xD503201F - WFI / NOP style halt)
            instructionWord == -0x2afcdfe1.toInt() -> { // 0xD503201F
                isPaused = true
                disassembled = "WFI (Wait For Interrupt - Sleep state entered)"
                deviceManager.powerControllerWrite(0x01)
            }

            // MOV (Wide immediate) - Simulating: MOV X[rd], #imm16
            // Opcode format: 0xD2800000 + (imm16 << 5) + rd
            (instructionWord and -0x7f80000) == -0x2d800000.toInt() -> {
                val rd = instructionWord and 0x1F
                val imm16 = (instructionWord shr 5) and 0xFFFF
                registers[rd] = imm16.toLong()
                disassembled = "MOV X$rd, #$imm16"
                pc += 4
            }

            // ADD (Immediate) - Simulating: ADD X[rd], X[rn], #imm12
            // Opcode format: 0x91000000 + (imm12 << 10) + (rn << 5) + rd
            (instructionWord and -0xff8000) == 0x91000000.toInt() -> {
                val rd = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val imm12 = (instructionWord shr 10) and 0xFFF
                registers[rd] = registers[rn] + imm12
                disassembled = "ADD X$rd, X$rn, #$imm12"
                pc += 4
            }

            // SUB (Immediate) - Simulating: SUB X[rd], X[rn], #imm12
            (instructionWord and -0xff8000) == -0x6f000000.toInt() -> { // 0xD1000000
                val rd = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val imm12 = (instructionWord shr 10) and 0xFFF
                registers[rd] = registers[rn] - imm12
                disassembled = "SUB X$rd, X$rn, #$imm12"
                pc += 4
            }

            // STRB (Store byte register) - Simulating: STRB W[rt], [X[rn]]
            // If address is UART address (0x09000000), route to Console MMIO.
            // Opcode format: 0x39000000 + (rn << 5) + rt
            (instructionWord and -0x3f00000) == 0x39000000.toInt() -> {
                val rt = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val address = registers[rn]
                val byteValue = (registers[rt] and 0xFF).toByte()

                writeHardwareMMIO(address, byteValue, deviceManager, memory)
                disassembled = "STRB W$rt, [X$rn] -> Written $byteValue to 0x${address.toString(16)}"
                pc += 4
            }

            // STR (Store 32-bit Word) - Simulating: STR W[rt], [X[rn], #imm] (specifically MMIO display range)
            (instructionWord and -0x3f00000) == -0x41000000.toInt() -> { // 0xB9000000
                val rt = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val address = registers[rn]
                val value = registers[rt].toInt()

                writeWordHardwareMMIO(address, value, deviceManager, memory)
                disassembled = "STR W$rt, [X$rn] -> Written $value to 0x${address.toString(16)}"
                pc += 4
            }

            // CBZ (Compare and branch on zero) - Simulating: CBZ X[rt], offset
            // Opcode format: 0x34000000 + (offset << 5) + rt
            (instructionWord and -0x7f00000) == 0x34000000.toInt() -> {
                val rt = instructionWord and 0x1F
                val offset = (instructionWord shr 5) and 0x7FFFF
                val signedOffset = if ((offset and 0x40000) != 0) offset or -0x80000 else offset
                
                if (registers[rt] == 0L) {
                    pc += (signedOffset * 4)
                    disassembled = "CBZ X$rt, Branching to 0x${pc.toString(16)}"
                } else {
                    disassembled = "CBZ X$rt, No branch"
                    pc += 4
                }
            }

            // B (Unconditional Branch) - Simulating: B offset
            // Opcode format: 0x14000000 + offset
            (instructionWord and -0x4000000) == 0x14000000.toInt() -> {
                val offset = instructionWord and 0x3FFFFFF
                val signedOffset = if ((offset and 0x2000000) != 0) offset or -0x4000000 else offset
                pc += (signedOffset * 4)
                disassembled = "B Branching directly to 0x${pc.toString(16)}"
            }

            else -> {
                // Trap unknown instruction as a CPU invalid execution error
                isHalted = true
                disassembled = "TRAP: Undefined Instruction Opcode 0x${instructionWord.toString(16)} at PC 0x${pc.toString(16)}"
                deviceManager.powerControllerWrite(0x03)
            }
        }

        return disassembled
    }

    private fun writeHardwareMMIO(address: Long, value: Byte, deviceManager: DeviceManager, memory: MemoryBackend) {
        when (address) {
            // UART base address TX register
            0x09000000L -> {
                deviceManager.serialConsole.writeTxChar(value.toInt().toChar())
            }
            else -> {
                // Regular memory save
                try {
                    memory.write8(address, value)
                } catch (e: Exception) {
                    isHalted = true
                }
            }
        }
    }

    private fun writeWordHardwareMMIO(address: Long, value: Int, deviceManager: DeviceManager, memory: MemoryBackend) {
        when {
            // Display framebuffer base range (e.g. 0x10000000 to 0x10100000)
            address >= 0x10000000L && address < 0x10100000L -> {
                val index = ((address - 0x10000000L) / 4).toInt()
                deviceManager.displayDevice.writePixel(index, value)
            }
            else -> {
                try {
                    memory.write32(address, value)
                } catch (e: Exception) {
                    isHalted = true
                }
            }
        }
    }
}
