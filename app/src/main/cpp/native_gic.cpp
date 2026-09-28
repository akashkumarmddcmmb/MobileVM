#include "native_gic.h"
#include <cstring>
#include <algorithm>

NativeGIC::NativeGIC() {
    reset();
}

void NativeGIC::reset() {
    std::lock_guard<std::mutex> lock(gicMutex);
    distCtlr = 0;
    // 64 interrupts supported (ITLinesNumber = 1), 1 CPU interface (0), Security Ext (0)
    distTyper = 0x00000021;
    distIidr = 0x0200043B; // ARM GICv2 Implementer / Arch

    isEnabler.fill(0);
    isPendr.fill(0);
    isActivr.fill(0);
    ipriorityr.fill(0xA0);
    itargetsr.fill(0x01); // Target CPU0
    icfgr.fill(0xAAAAAAAA); // Level-sensitive / 2-bit config

    cpuCtlr = 0;
    cpuPmr = 0xF0; // Accept priority 0x00 to 0xEF
    cpuBpr = 0x02;
    cpuIar = 1023; // Spurious / None
    cpuEoir = 0;
    cpuHppir = 1023;
}

void NativeGIC::setInterruptPending(uint32_t irqId, bool pending) {
    if (irqId >= 64) return;
    std::lock_guard<std::mutex> lock(gicMutex);
    uint32_t word = irqId / 32;
    uint32_t bit = 1u << (irqId % 32);
    if (pending) {
        isPendr[word] |= bit;
    } else {
        isPendr[word] &= ~bit;
    }
}

bool NativeGIC::isInterruptPending(uint32_t irqId) const {
    if (irqId >= 64) return false;
    std::lock_guard<std::mutex> lock(gicMutex);
    uint32_t word = irqId / 32;
    uint32_t bit = 1u << (irqId % 32);
    return (isPendr[word] & bit) != 0;
}

bool NativeGIC::isInterruptEnabled(uint32_t irqId) const {
    if (irqId >= 64) return false;
    std::lock_guard<std::mutex> lock(gicMutex);
    uint32_t word = irqId / 32;
    uint32_t bit = 1u << (irqId % 32);
    return (isEnabler[word] & bit) != 0;
}

bool NativeGIC::hasPendingIRQ() const {
    std::lock_guard<std::mutex> lock(gicMutex);
    if (!(distCtlr & 1) || !(cpuCtlr & 1)) {
        return false;
    }

    for (uint32_t i = 0; i < 64; ++i) {
        uint32_t word = i / 32;
        uint32_t bit = 1u << (i % 32);
        if ((isEnabler[word] & bit) && (isPendr[word] & bit) && !(isActivr[word] & bit)) {
            uint8_t prio = ipriorityr[i];
            if (prio < cpuPmr) {
                return true;
            }
        }
    }
    return false;
}

uint32_t NativeGIC::acknowledgeInterrupt() {
    std::lock_guard<std::mutex> lock(gicMutex);
    if (!(distCtlr & 1) || !(cpuCtlr & 1)) {
        return 1023;
    }

    uint32_t bestIrq = 1023;
    uint8_t bestPrio = cpuPmr;

    for (uint32_t i = 0; i < 64; ++i) {
        uint32_t word = i / 32;
        uint32_t bit = 1u << (i % 32);
        if ((isEnabler[word] & bit) && (isPendr[word] & bit) && !(isActivr[word] & bit)) {
            uint8_t prio = ipriorityr[i];
            if (prio < bestPrio) {
                bestPrio = prio;
                bestIrq = i;
            }
        }
    }

    if (bestIrq < 64) {
        uint32_t word = bestIrq / 32;
        uint32_t bit = 1u << (bestIrq % 32);
        isActivr[word] |= bit;
        isPendr[word] &= ~bit;
        cpuIar = bestIrq;
        return bestIrq;
    }

    return 1023;
}

void NativeGIC::endOfInterrupt(uint32_t irqId) {
    if (irqId >= 64) return;
    std::lock_guard<std::mutex> lock(gicMutex);
    uint32_t word = irqId / 32;
    uint32_t bit = 1u << (irqId % 32);
    isActivr[word] &= ~bit;
}

bool NativeGIC::isGICAddress(uint64_t address) const {
    if (address >= GICD_BASE && address < GICD_BASE + GICD_SIZE) return true;
    if (address >= GICC_BASE && address < GICC_BASE + GICC_SIZE) return true;
    return false;
}

uint32_t NativeGIC::readMMIO32(uint64_t address) {
    std::lock_guard<std::mutex> lock(gicMutex);
    if (address >= GICD_BASE && address < GICD_BASE + GICD_SIZE) {
        uint64_t offset = address - GICD_BASE;
        if (offset == 0x000) return distCtlr;
        if (offset == 0x004) return distTyper;
        if (offset == 0x008) return distIidr;
        if (offset >= 0x100 && offset <= 0x104) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x100) / 4);
            return isEnabler[idx];
        }
        if (offset >= 0x180 && offset <= 0x184) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x180) / 4);
            return isEnabler[idx];
        }
        if (offset >= 0x200 && offset <= 0x204) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x200) / 4);
            return isPendr[idx];
        }
        if (offset >= 0x280 && offset <= 0x284) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x280) / 4);
            return isPendr[idx];
        }
        if (offset >= 0x300 && offset <= 0x304) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x300) / 4);
            return isActivr[idx];
        }
        if (offset >= 0x400 && offset < 0x440) {
            uint32_t idx = static_cast<uint32_t>(offset - 0x400);
            return static_cast<uint32_t>(ipriorityr[idx]) |
                   (static_cast<uint32_t>(ipriorityr[idx + 1]) << 8) |
                   (static_cast<uint32_t>(ipriorityr[idx + 2]) << 16) |
                   (static_cast<uint32_t>(ipriorityr[idx + 3]) << 24);
        }
        if (offset >= 0x800 && offset < 0x840) {
            uint32_t idx = static_cast<uint32_t>(offset - 0x800);
            return static_cast<uint32_t>(itargetsr[idx]) |
                   (static_cast<uint32_t>(itargetsr[idx + 1]) << 8) |
                   (static_cast<uint32_t>(itargetsr[idx + 2]) << 16) |
                   (static_cast<uint32_t>(itargetsr[idx + 3]) << 24);
        }
        if (offset >= 0xC00 && offset <= 0xC0C) {
            uint32_t idx = static_cast<uint32_t>((offset - 0xC00) / 4);
            return icfgr[idx];
        }
        return 0;
    }

    if (address >= GICC_BASE && address < GICC_BASE + GICC_SIZE) {
        uint64_t offset = address - GICC_BASE;
        if (offset == 0x000) return cpuCtlr;
        if (offset == 0x004) return cpuPmr;
        if (offset == 0x008) return cpuBpr;
        if (offset == 0x00C) {
            // Read IAR directly acknowledges
            uint32_t bestIrq = 1023;
            uint8_t bestPrio = cpuPmr;
            for (uint32_t i = 0; i < 64; ++i) {
                uint32_t word = i / 32;
                uint32_t bit = 1u << (i % 32);
                if ((isEnabler[word] & bit) && (isPendr[word] & bit) && !(isActivr[word] & bit)) {
                    uint8_t prio = ipriorityr[i];
                    if (prio < bestPrio) {
                        bestPrio = prio;
                        bestIrq = i;
                    }
                }
            }
            if (bestIrq < 64) {
                uint32_t word = bestIrq / 32;
                uint32_t bit = 1u << (bestIrq % 32);
                isActivr[word] |= bit;
                isPendr[word] &= ~bit;
                return bestIrq;
            }
            return 1023;
        }
        if (offset == 0x018) return cpuHppir;
        return 0;
    }

    return 0;
}

void NativeGIC::writeMMIO32(uint64_t address, uint32_t value) {
    std::lock_guard<std::mutex> lock(gicMutex);
    if (address >= GICD_BASE && address < GICD_BASE + GICD_SIZE) {
        uint64_t offset = address - GICD_BASE;
        if (offset == 0x000) {
            distCtlr = value & 1;
        } else if (offset >= 0x100 && offset <= 0x104) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x100) / 4);
            isEnabler[idx] |= value;
        } else if (offset >= 0x180 && offset <= 0x184) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x180) / 4);
            isEnabler[idx] &= ~value;
        } else if (offset >= 0x200 && offset <= 0x204) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x200) / 4);
            isPendr[idx] |= value;
        } else if (offset >= 0x280 && offset <= 0x284) {
            uint32_t idx = static_cast<uint32_t>((offset - 0x280) / 4);
            isPendr[idx] &= ~value;
        } else if (offset >= 0x400 && offset < 0x440) {
            uint32_t idx = static_cast<uint32_t>(offset - 0x400);
            ipriorityr[idx] = static_cast<uint8_t>(value & 0xFF);
            ipriorityr[idx + 1] = static_cast<uint8_t>((value >> 8) & 0xFF);
            ipriorityr[idx + 2] = static_cast<uint8_t>((value >> 16) & 0xFF);
            ipriorityr[idx + 3] = static_cast<uint8_t>((value >> 24) & 0xFF);
        } else if (offset >= 0x800 && offset < 0x840) {
            uint32_t idx = static_cast<uint32_t>(offset - 0x800);
            itargetsr[idx] = static_cast<uint8_t>(value & 0xFF);
            itargetsr[idx + 1] = static_cast<uint8_t>((value >> 8) & 0xFF);
            itargetsr[idx + 2] = static_cast<uint8_t>((value >> 16) & 0xFF);
            itargetsr[idx + 3] = static_cast<uint8_t>((value >> 24) & 0xFF);
        } else if (offset >= 0xC00 && offset <= 0xC0C) {
            uint32_t idx = static_cast<uint32_t>((offset - 0xC00) / 4);
            icfgr[idx] = value;
        }
        return;
    }

    if (address >= GICC_BASE && address < GICC_BASE + GICC_SIZE) {
        uint64_t offset = address - GICC_BASE;
        if (offset == 0x000) {
            cpuCtlr = value & 1;
        } else if (offset == 0x004) {
            cpuPmr = value & 0xFF;
        } else if (offset == 0x008) {
            cpuBpr = value & 0x07;
        } else if (offset == 0x010) { // EOIR
            uint32_t irqId = value & 0x3FF;
            if (irqId < 64) {
                uint32_t word = irqId / 32;
                uint32_t bit = 1u << (irqId % 32);
                isActivr[word] &= ~bit;
            }
        }
    }
}

uint8_t NativeGIC::readMMIO8(uint64_t address) {
    uint64_t aligned = address & ~3ULL;
    uint32_t val32 = readMMIO32(aligned);
    uint32_t shift = static_cast<uint32_t>((address & 3) * 8);
    return static_cast<uint8_t>((val32 >> shift) & 0xFF);
}

void NativeGIC::writeMMIO8(uint64_t address, uint8_t value) {
    uint64_t aligned = address & ~3ULL;
    uint32_t shift = static_cast<uint32_t>((address & 3) * 8);
    uint32_t mask = 0xFFu << shift;
    uint32_t cur = readMMIO32(aligned);
    uint32_t updated = (cur & ~mask) | (static_cast<uint32_t>(value) << shift);
    writeMMIO32(aligned, updated);
}
