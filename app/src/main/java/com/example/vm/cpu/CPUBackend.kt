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
            instructionWord == 0xD4400000.toInt() -> {
                isHalted = true
                disassembled = "HLT #0 (System shutdown requested by guest OS)"
                // MMIO power control register write for graceful shutdown
                deviceManager.powerControllerWrite(0x02) 
            }

            // PAUSE / WFI (0xD503207F / 0xD503201F)
            instructionWord == 0xD503207F.toInt() || instructionWord == 0xD503201F.toInt() -> {
                isPaused = true
                disassembled = "WFI (Wait For Interrupt - Sleep state entered)"
                deviceManager.powerControllerWrite(0x01)
            }

            // MOV (Wide immediate) - MOV X[rd], #imm16
            // Opcode format: 0xD2800000 + (imm16 << 5) + rd
            (instructionWord and 0xFF800000.toInt()) == 0xD2800000.toInt() -> {
                val rd = instructionWord and 0x1F
                val imm16 = (instructionWord shr 5) and 0xFFFF
                registers[rd] = imm16.toLong()
                disassembled = "MOV X$rd, #$imm16"
                pc += 4
            }

            // ADD (Immediate) - ADD X[rd], X[rn], #imm12
            // Opcode format: 0x91000000 + (imm12 << 10) + (rn << 5) + rd
            (instructionWord and 0xFF800000.toInt()) == 0x91000000.toInt() -> {
                val rd = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val imm12 = (instructionWord shr 10) and 0xFFF
                registers[rd] = registers[rn] + imm12
                disassembled = "ADD X$rd, X$rn, #$imm12"
                pc += 4
            }

            // SUB (Immediate) - SUB X[rd], X[rn], #imm12
            (instructionWord and 0xFF800000.toInt()) == 0xD1000000.toInt() -> {
                val rd = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val imm12 = (instructionWord shr 10) and 0xFFF
                registers[rd] = registers[rn] - imm12
                disassembled = "SUB X$rd, X$rn, #$imm12"
                pc += 4
            }

            // STRB (Store byte register) - STRB W[rt], [X[rn], #imm12]
            // If address is UART address (0x09000000), route to Console MMIO.
            (instructionWord and 0xFFC00000.toInt()) == 0x39000000 -> {
                val rt = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val imm12 = (instructionWord shr 10) and 0xFFF
                val address = registers[rn] + imm12
                val byteValue = (registers[rt] and 0xFF).toByte()

                writeHardwareMMIO(address, byteValue, deviceManager, memory)
                disassembled = "STRB W$rt, [X$rn, #$imm12] -> Written $byteValue to 0x${address.toString(16)}"
                pc += 4
            }

            // STR (Store 32-bit Word) - STR W[rt], [X[rn], #imm12]
            (instructionWord and 0xFFC00000.toInt()) == 0xB9000000.toInt() -> {
                val rt = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val imm12 = (instructionWord shr 10) and 0xFFF
                val address = registers[rn] + (imm12 * 4)
                val value = registers[rt].toInt()

                writeWordHardwareMMIO(address, value, deviceManager, memory)
                disassembled = "STR W$rt, [X$rn, #${imm12 * 4}] -> Written $value to 0x${address.toString(16)}"
                pc += 4
            }

            // CBZ (Compare and branch on zero) - CBZ X[rt], offset
            (instructionWord and 0x7F000000) == 0x34000000 -> {
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

            // B (Unconditional Branch) - B offset
            (instructionWord and 0xFC000000.toInt()) == 0x14000000 -> {
                val offset = instructionWord and 0x3FFFFFF
                val signedOffset = if ((offset and 0x2000000) != 0) offset or -0x4000000 else offset
                pc += (signedOffset * 4)
                disassembled = "B Branching directly to 0x${pc.toString(16)}"
            }

            // ADR / ADRP (Form PC-relative address)
            (instructionWord and 0x9F000000.toInt()) == 0x10000000 -> { // ADR
                val rd = instructionWord and 0x1F
                val immlo = (instructionWord shr 29) and 0x3
                val immhi = (instructionWord shr 5) and 0x7FFFF
                var imm21 = (immhi shl 2) or immlo
                if ((imm21 and 0x100000) != 0) imm21 = imm21 or -0x200000
                registers[rd] = pc + imm21
                disassembled = "ADR X$rd, 0x${registers[rd].toString(16)}"
                pc += 4
            }

            (instructionWord and 0x9F000000.toInt()) == 0x90000000.toInt() -> { // ADRP (0x90000000)
                val rd = instructionWord and 0x1F
                val immlo = (instructionWord shr 29) and 0x3
                val immhi = (instructionWord shr 5) and 0x7FFFF
                var imm21 = (immhi shl 2) or immlo
                if ((imm21 and 0x100000) != 0) imm21 = imm21 or -0x200000
                val base = pc and -0x1000L
                registers[rd] = base + (imm21.toLong() shl 12)
                disassembled = "ADRP X$rd, 0x${registers[rd].toString(16)}"
                pc += 4
            }

            // STP 64-bit (Store Pair) - 0xA9000000
            (instructionWord and 0xFE400000.toInt()) == 0xA9000000.toInt() -> {
                val rt1 = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val rt2 = (instructionWord shr 10) and 0x1F
                var imm7 = (instructionWord shr 15) and 0x7F
                if ((imm7 and 0x40) != 0) imm7 = imm7 or -0x80
                val targetAddr = registers[rn] + (imm7 * 8)
                memory.write64(targetAddr, registers[rt1])
                memory.write64(targetAddr + 8, registers[rt2])
                disassembled = "STP X$rt1, X$rt2, [X$rn, #${imm7 * 8}]"
                pc += 4
            }

            // LDP 64-bit (Load Pair) - 0xA9400000
            (instructionWord and 0xFE400000.toInt()) == 0xA9400000.toInt() -> {
                val rt1 = instructionWord and 0x1F
                val rn = (instructionWord shr 5) and 0x1F
                val rt2 = (instructionWord shr 10) and 0x1F
                var imm7 = (instructionWord shr 15) and 0x7F
                if ((imm7 and 0x40) != 0) imm7 = imm7 or -0x80
                val targetAddr = registers[rn] + (imm7 * 8)
                registers[rt1] = memory.read64(targetAddr)
                registers[rt2] = memory.read64(targetAddr + 8)
                disassembled = "LDP X$rt1, X$rt2, [X$rn, #${imm7 * 8}]"
                pc += 4
            }

            // Barriers: ISB / DSB / DMB (0xD503301F)
            (instructionWord and 0xFFFFFFE0.toInt()) == 0xD503301F.toInt() || (instructionWord and 0xFFFFF000.toInt()) == 0xD5033000.toInt() -> {
                disassembled = "BARRIER (ISB/DSB/DMB)"
                pc += 4
            }

            // MRS Xd, CurrentEL (0xD5384240) -> Reports EL1 (value 0x4)
            (instructionWord and 0xFFFFFE00.toInt()) == 0xD5384200.toInt() -> {
                val rd = instructionWord and 0x1F
                registers[rd] = 0x4L // EL1
                disassembled = "MRS X$rd, CurrentEL (EL1)"
                pc += 4
            }

            else -> {
                // Trap unsupported instruction as an actionable error without faking
                isHalted = true
                val hexOp = instructionWord.toLong().and(0xFFFFFFFFL).toString(16).uppercase()
                disassembled = "TRAP: Unsupported ARM64 instruction 0x$hexOp at PC 0x${pc.toString(16).uppercase()}"
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
