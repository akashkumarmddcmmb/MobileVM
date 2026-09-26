#ifndef NATIVE_LINUX_BOOT_H
#define NATIVE_LINUX_BOOT_H

#include <cstdint>
#include <string>
#include <vector>
#include "native_memory.h"
#include "native_cpu_backend.h"
#include "native_devices.h"

// Standard ARM64 Linux Kernel Image Header (Documentation/arch/arm64/booting.rst)
struct Arm64KernelHeader {
    uint32_t code0;           // Executable code / branch instruction
    uint32_t code1;           // Executable code
    uint64_t text_offset;     // Image load offset (little-endian)
    uint64_t image_size;      // Effective Image size (little-endian)
    uint64_t flags;           // Kernel flags (endianness, page size)
    uint64_t res2;            // Reserved
    uint64_t res3;            // Reserved
    uint64_t res4;            // Reserved
    uint32_t magic;           // Magic number: 0x644d5241 ("ARM\x64")
    uint32_t pe_header;       // PE/COFF header offset
};

struct LinuxBootConfig {
    std::string kernelPath;
    std::string initramfsPath;
    std::string dtbPath;
    std::string cmdline;
    std::string consoleDevice;
    uint64_t ramSizeBytes;
    int cpuCount;
};

class NativeLinuxBootLoader {
public:
    static bool loadLinuxGuest(
        const LinuxBootConfig& config,
        NativeMemory& memory,
        NativeCPUBackend& cpu,
        NativeDeviceManager& devices,
        std::string& outLog
    );

    static bool verifyKernelImage(const std::string& path, Arm64KernelHeader& outHeader, std::string& outError);

private:
    static bool loadCustomKernel(const LinuxBootConfig& config, NativeMemory& memory, NativeCPUBackend& cpu, std::string& outLog);
    static void generateBuiltinLinuxGuest(const LinuxBootConfig& config, NativeMemory& memory, NativeCPUBackend& cpu, NativeDeviceManager& devices);
    static uint64_t generateDeviceTreeBlob(const LinuxBootConfig& config, NativeMemory& memory, uint64_t dtbOffset);
};

#endif // NATIVE_LINUX_BOOT_H
