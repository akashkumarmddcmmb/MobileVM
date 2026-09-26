#include "native_cpu_arm64.h"
#include <fcntl.h>
#include <unistd.h>
#include <cstring>

NativeCPUARM64::NativeCPUARM64() {
    reset();
}

void NativeCPUARM64::reset() {
    registers.fill(0);
    pc = 0x00000000ULL;
    sp = 0x000FFFF0ULL;
    pstate = 0;
    state = NativeCPUState::READY;
}

uint64_t NativeCPUARM64::getRegister(uint32_t index) const {
    if (index < 32) return registers[index];
    return 0;
}

void NativeCPUARM64::setRegister(uint32_t index, uint64_t value) {
    if (index < 31) { // X31 is zero register in many ARM contexts or SP
        registers[index] = value;
    }
}

void NativeCPUARM64::handleStore8(uint64_t address, uint8_t value, NativeMemory& memory, NativeDeviceManager& devices) {
    if (!devices.handleMMIOWrite8(address, value)) {
        memory.write8(address, value);
    }
}

void NativeCPUARM64::handleStore32(uint64_t address, uint32_t value, NativeMemory& memory, NativeDeviceManager& devices) {
    if (!devices.handleMMIOWrite32(address, value, &memory)) {
        memory.write32(address, value);
    }
}

uint8_t NativeCPUARM64::handleLoad8(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices) {
    uint8_t mmioVal = devices.handleMMIORead8(address);
    if (mmioVal != 0) return mmioVal;
    return memory.read8(address);
}

uint32_t NativeCPUARM64::handleLoad32(uint64_t address, NativeMemory& memory, NativeDeviceManager& devices) {
    uint32_t mmioVal = devices.handleMMIORead32(address);
    if (mmioVal != 0) return mmioVal;
    return memory.read32(address);
}

NativeCPUState NativeCPUARM64::step(NativeMemory& memory, NativeDeviceManager& devices) {
    if (state == NativeCPUState::HALTED || state == NativeCPUState::PAUSED || state == NativeCPUState::TRAP_FAULT) {
        return state;
    }

    if (!memory.isValidAddress(pc, 4)) {
        state = NativeCPUState::TRAP_FAULT;
        return state;
    }

    uint32_t inst = memory.read32(pc);

    // HLT #0 (0xD4400000)
    if (inst == 0xD4400000) {
        state = NativeCPUState::HALTED;
        devices.handleMMIOWrite8(0x08000000ULL, 0x02); // ACPI halt
        return state;
    }

    // WFI / YIELD (0xD503201F or 0xD503207F)
    if (inst == 0xD503201F || inst == 0xD503207F) {
        state = NativeCPUState::PAUSED;
        devices.handleMMIOWrite8(0x08000000ULL, 0x01); // ACPI pause
        pc += 4;
        return state;
    }

    // NOP (0xD503201F)
    if (inst == 0xD503201F) {
        pc += 4;
        return state;
    }

    // MOV immediate (0xD2800000)
    if ((inst & 0xFF800000) == 0xD2800000) {
        uint32_t rd = inst & 0x1F;
        uint64_t imm16 = (inst >> 5) & 0xFFFF;
        uint32_t hw = (inst >> 21) & 0x3;
        setRegister(rd, imm16 << (hw * 16));
        pc += 4;
        return state;
    }

    // ADD immediate (0x91000000)
    if ((inst & 0xFF000000) == 0x91000000) {
        uint32_t rd = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t imm12 = (inst >> 10) & 0xFFF;
        setRegister(rd, getRegister(rn) + imm12);
        pc += 4;
        return state;
    }

    // SUB immediate (0xD1000000)
    if ((inst & 0xFF000000) == 0xD1000000) {
        uint32_t rd = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t imm12 = (inst >> 10) & 0xFFF;
        setRegister(rd, getRegister(rn) - imm12);
        pc += 4;
        return state;
    }

    // STRB register offset (0x39000000)
    if ((inst & 0xFFC00000) == 0x39000000) {
        uint32_t rt = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t addr = getRegister(rn);
        uint8_t val = static_cast<uint8_t>(getRegister(rt) & 0xFF);
        handleStore8(addr, val, memory, devices);
        pc += 4;
        return state;
    }

    // STR 32-bit register (0xB9000000)
    if ((inst & 0xFFC00000) == 0xB9000000) {
        uint32_t rt = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t addr = getRegister(rn);
        uint32_t val = static_cast<uint32_t>(getRegister(rt) & 0xFFFFFFFF);
        handleStore32(addr, val, memory, devices);
        pc += 4;
        return state;
    }

    // LDRB register (0x39400000)
    if ((inst & 0xFFC00000) == 0x39400000) {
        uint32_t rt = inst & 0x1F;
        uint32_t rn = (inst >> 5) & 0x1F;
        uint64_t addr = getRegister(rn);
        uint8_t val = handleLoad8(addr, memory, devices);
        setRegister(rt, val);
        pc += 4;
        return state;
    }

    // CBZ register (0x34000000)
    if ((inst & 0xFF000000) == 0x34000000) {
        uint32_t rt = inst & 0x1F;
        int32_t imm19 = (inst >> 5) & 0x7FFFF;
        if (imm19 & 0x40000) imm19 |= ~0x7FFFF; // Sign extend
        if (getRegister(rt) == 0) {
            pc += (imm19 * 4);
        } else {
            pc += 4;
        }
        return state;
    }

    // CBNZ register (0x35000000)
    if ((inst & 0xFF000000) == 0x35000000) {
        uint32_t rt = inst & 0x1F;
        int32_t imm19 = (inst >> 5) & 0x7FFFF;
        if (imm19 & 0x40000) imm19 |= ~0x7FFFF;
        if (getRegister(rt) != 0) {
            pc += (imm19 * 4);
        } else {
            pc += 4;
        }
        return state;
    }

    // B unconditional branch (0x14000000)
    if ((inst & 0xFC000000) == 0x14000000) {
        int32_t imm26 = inst & 0x3FFFFFF;
        if (imm26 & 0x2000000) imm26 |= ~0x3FFFFFF;
        pc += (imm26 * 4);
        return state;
    }

    // BL branch with link (0x94000000)
    if ((inst & 0xFC000000) == 0x94000000) {
        int32_t imm26 = inst & 0x3FFFFFF;
        if (imm26 & 0x2000000) imm26 |= ~0x3FFFFFF;
        setRegister(30, pc + 4); // Link register LR = X30
        pc += (imm26 * 4);
        return state;
    }

    // RET (0xD65F03C0)
    if (inst == 0xD65F03C0) {
        pc = getRegister(30);
        return state;
    }

    // Undefined Instruction Trap
    state = NativeCPUState::TRAP_FAULT;
    devices.handleMMIOWrite8(0x08000000ULL, 0x03);
    return state;
}

uint64_t NativeCPUARM64::runCycles(NativeMemory& memory, NativeDeviceManager& devices, uint64_t maxCycles) {
    state = NativeCPUState::RUNNING;
    uint64_t executed = 0;
    while (executed < maxCycles && state == NativeCPUState::RUNNING) {
        step(memory, devices);
        executed++;
    }
    return executed;
}
