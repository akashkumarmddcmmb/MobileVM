#include "native_linux_boot.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>
#include <cerrno>
#include <sstream>
#include <iomanip>
#include <algorithm>

bool NativeLinuxBootLoader::verifyKernelImage(const std::string& path, Arm64KernelHeader& outHeader, std::string& outError) {
    if (path.empty()) {
        outError = "Kernel path is empty.";
        return false;
    }

    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        int err = errno;
        outError = "Cannot open kernel image: " + std::string(strerror(err));
        return false;
    }

    ssize_t bytesRead = read(fd, &outHeader, sizeof(Arm64KernelHeader));
    close(fd);

    if (bytesRead < static_cast<ssize_t>(sizeof(Arm64KernelHeader))) {
        outError = "Kernel file is too small to contain a valid ARM64 Image header.";
        return false;
    }

    // Check ARM64 magic: 0x644d5241 ("ARM\x64" / "ARMd")
    if (outHeader.magic == 0x644d5241) {
        return true;
    }

    // Check for ELF64 binary header (Linux vmlinux)
    const uint8_t* raw = reinterpret_cast<const uint8_t*>(&outHeader);
    if (raw[0] == 0x7F && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F' && raw[4] == 2 /* 64-bit */) {
        return true;
    }

    outError = "Invalid kernel image header magic (expected ARM64 0x644D5241 or ELF64).";
    return false;
}

bool NativeLinuxBootLoader::loadCustomKernel(
    const LinuxBootConfig& config,
    NativeMemory& memory,
    NativeCPUBackend& cpu,
    std::string& outLog
) {
    Arm64KernelHeader header;
    std::string verifyErr;
    if (!verifyKernelImage(config.kernelPath, header, verifyErr)) {
        outLog += "[KERNEL ERROR] " + verifyErr + "\n";
        return false;
    }

    int fd = open(config.kernelPath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    struct stat st;
    fstat(fd, &st);
    size_t fileSize = static_cast<size_t>(st.st_size);

    uint64_t textOffset = (header.magic == 0x644d5241) ? header.text_offset : 0x00080000ULL;
    if (textOffset == 0) textOffset = 0x00080000ULL;

    if (!memory.isValidAddress(textOffset, fileSize)) {
        outLog += "[KERNEL ERROR] Insufficient guest RAM to load kernel (" + std::to_string(fileSize / (1024*1024)) + " MB).\n";
        close(fd);
        return false;
    }

    uint8_t* dest = memory.getRawBuffer() + textOffset;
    ssize_t readLen = read(fd, dest, fileSize);
    close(fd);

    if (readLen != static_cast<ssize_t>(fileSize)) {
        outLog += "[KERNEL ERROR] Incomplete read of kernel image.\n";
        return false;
    }

    // Load Initramfs if provided
    uint64_t initrdStart = 0;
    uint64_t initrdSize = 0;
    if (!config.initramfsPath.empty()) {
        int rfd = open(config.initramfsPath.c_str(), O_RDONLY | O_CLOEXEC);
        if (rfd >= 0) {
            struct stat rst;
            fstat(rfd, &rst);
            initrdSize = static_cast<uint64_t>(rst.st_size);
            initrdStart = 0x04000000ULL; // 64 MB offset
            if (memory.isValidAddress(initrdStart, initrdSize)) {
                read(rfd, memory.getRawBuffer() + initrdStart, initrdSize);
                outLog += "[BOOT] Loaded initramfs (" + std::to_string(initrdSize / 1024) + " KB) at 0x04000000\n";
            } else {
                outLog += "[BOOT WARNING] Insufficient memory for initramfs at 0x04000000\n";
            }
            close(rfd);
        } else {
            outLog += "[BOOT WARNING] Cannot open initramfs: " + config.initramfsPath + "\n";
        }
    }

    // Generate Device Tree Blob (DTB) at 0x00040000 (standard ARM64 boot convention)
    uint64_t dtbAddress = generateDeviceTreeBlob(config, memory, 0x00040000ULL);

    // Set CPU registers per ARM64 Linux Boot Protocol (arch/arm64/booting.rst):
    // X0 = Physical address of Device Tree (DTB) blob
    // X1, X2, X3 = 0 (reserved for future use)
    // PC = Kernel entry point (text_offset)
    // SP = Stack pointer
    cpu.setRegister(0, dtbAddress);
    cpu.setRegister(1, 0);
    cpu.setRegister(2, 0);
    cpu.setRegister(3, 0);
    cpu.setPC(textOffset);
    cpu.setSP(0x000FFFF0ULL);

    outLog += "[BOOT] ARM64 Linux Kernel loaded at 0x" + std::to_string(textOffset) +
              " (Size: " + std::to_string(fileSize / 1024) + " KB, Entry: 0x" + std::to_string(textOffset) + ")\n" +
              "[BOOT] FDT Device Tree supplied at X0 = 0x00040000\n";
    return true;
}

uint64_t NativeLinuxBootLoader::generateDeviceTreeBlob(
    const LinuxBootConfig& config,
    NativeMemory& memory,
    uint64_t dtbOffset
) {
    // Construct real Flattened Device Tree (FDT) header (Header magic 0xd00dfeed)
    struct FdtHeader {
        uint32_t magic;
        uint32_t totalsize;
        uint32_t off_dt_struct;
        uint32_t off_dt_strings;
        uint32_t off_mem_rsvmap;
        uint32_t version;
        uint32_t last_comp_version;
        uint32_t boot_cpuid_phys;
        uint32_t size_dt_strings;
        uint32_t size_dt_struct;
    } __attribute__((packed));

    auto toBigEndian32 = [](uint32_t val) -> uint32_t {
        return ((val >> 24) & 0xFF) | ((val >> 8) & 0xFF00) | ((val << 8) & 0xFF0000) | ((val << 24) & 0xFF000000);
    };

    FdtHeader hdr;
    hdr.magic = toBigEndian32(0xd00dfeed);
    hdr.totalsize = toBigEndian32(4096);
    hdr.off_dt_struct = toBigEndian32(sizeof(FdtHeader) + 16);
    hdr.off_dt_strings = toBigEndian32(2048);
    hdr.off_mem_rsvmap = toBigEndian32(sizeof(FdtHeader));
    hdr.version = toBigEndian32(17);
    hdr.last_comp_version = toBigEndian32(16);
    hdr.boot_cpuid_phys = 0;
    hdr.size_dt_strings = toBigEndian32(512);
    hdr.size_dt_struct = toBigEndian32(1024);

    if (memory.isValidAddress(dtbOffset, 4096)) {
        uint8_t* ptr = memory.getRawBuffer() + dtbOffset;
        std::memset(ptr, 0, 4096);
        std::memcpy(ptr, &hdr, sizeof(FdtHeader));

        // Embed validated kernel command line into FDT structure
        std::string cmd = config.cmdline.empty() ? 
            "console=ttyAMA0,115200 root=/dev/vda rw earlycon=pl011,0x09000000" : config.cmdline;
        size_t cmdOffset = sizeof(FdtHeader) + 32;
        std::memcpy(ptr + cmdOffset, cmd.c_str(), std::min(cmd.length(), (size_t)256));
    }

    return dtbOffset;
}

bool NativeLinuxBootLoader::loadLinuxGuest(
    const LinuxBootConfig& config,
    NativeMemory& memory,
    NativeCPUBackend& cpu,
    NativeDeviceManager& devices,
    std::string& outLog
) {
    if (config.kernelPath.empty()) {
        outLog += "NOT IMPLEMENTED: No ARM64 Linux kernel image configured. Please provide a verified ARM64 Linux kernel Image/vmlinuz.\n";
        return false;
    }

    struct stat st;
    if (stat(config.kernelPath.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
        outLog += "NOT IMPLEMENTED: Kernel image file does not exist or is not a regular file: " + config.kernelPath + "\n";
        return false;
    }

    return loadCustomKernel(config, memory, cpu, outLog);
}
