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

    uint64_t entryPoint = 0x00080000ULL;
    bool loadSuccess = false;

    const uint8_t* rawHeader = reinterpret_cast<const uint8_t*>(&header);
    bool isElf64 = (rawHeader[0] == 0x7F && rawHeader[1] == 'E' && rawHeader[2] == 'L' && rawHeader[3] == 'F' && rawHeader[4] == 2);

    if (isElf64) {
        // Parse ELF64 (vmlinux) header and program headers
        struct Elf64Header {
            uint8_t e_ident[16];
            uint16_t e_type;
            uint16_t e_machine;
            uint32_t e_version;
            uint64_t e_entry;
            uint64_t e_phoff;
            uint64_t e_shoff;
            uint32_t e_flags;
            uint16_t e_ehsize;
            uint16_t e_phentsize;
            uint16_t e_phnum;
            uint16_t e_shentsize;
            uint16_t e_shnum;
            uint16_t e_shstrndx;
        } __attribute__((packed)) elfHdr;

        lseek(fd, 0, SEEK_SET);
        if (read(fd, &elfHdr, sizeof(elfHdr)) == sizeof(elfHdr)) {
            entryPoint = elfHdr.e_entry & 0x0FFFFFFFULL;
            if (entryPoint == 0) entryPoint = 0x00080000ULL;

            struct Elf64Phdr {
                uint32_t p_type;
                uint32_t p_flags;
                uint64_t p_offset;
                uint64_t p_vaddr;
                uint64_t p_paddr;
                uint64_t p_filesz;
                uint64_t p_memsz;
                uint64_t p_align;
            } __attribute__((packed));

            bool phdrLoaded = false;
            if (elfHdr.e_phnum > 0 && elfHdr.e_phentsize >= sizeof(Elf64Phdr)) {
                lseek(fd, elfHdr.e_phoff, SEEK_SET);
                for (uint16_t i = 0; i < elfHdr.e_phnum; i++) {
                    Elf64Phdr phdr;
                    if (read(fd, &phdr, sizeof(phdr)) != sizeof(phdr)) break;
                    if (phdr.p_type == 1 /* PT_LOAD */ && phdr.p_filesz > 0) {
                        uint64_t physTarget = phdr.p_paddr != 0 ? phdr.p_paddr : (phdr.p_vaddr & 0x0FFFFFFFULL);
                        if (physTarget == 0) physTarget = 0x00080000ULL;

                        if (memory.isValidAddress(physTarget, phdr.p_filesz)) {
                            off_t savedPos = lseek(fd, 0, SEEK_CUR);
                            lseek(fd, phdr.p_offset, SEEK_SET);
                            read(fd, memory.getRawBuffer() + physTarget, phdr.p_filesz);
                            lseek(fd, savedPos, SEEK_SET);
                            phdrLoaded = true;
                        }
                    }
                }
            }

            if (phdrLoaded) {
                loadSuccess = true;
                outLog += "[BOOT] ELF64 Kernel (vmlinux) loaded successfully via Program Headers. Entry: 0x" +
                          std::to_string(entryPoint) + "\n";
            }
        }
    }

    if (!loadSuccess) {
        // Fallback or Raw ARM64 Image Loading
        uint64_t textOffset = (header.magic == 0x644d5241) ? header.text_offset : 0x00080000ULL;
        if (textOffset == 0) textOffset = 0x00080000ULL;
        entryPoint = textOffset;

        if (!memory.isValidAddress(textOffset, fileSize)) {
            outLog += "[KERNEL ERROR] Insufficient guest RAM to load kernel (" + std::to_string(fileSize / (1024*1024)) + " MB).\n";
            close(fd);
            return false;
        }

        lseek(fd, 0, SEEK_SET);
        uint8_t* dest = memory.getRawBuffer() + textOffset;
        ssize_t readLen = read(fd, dest, fileSize);
        if (readLen != static_cast<ssize_t>(fileSize)) {
            outLog += "[KERNEL ERROR] Incomplete read of kernel image.\n";
            close(fd);
            return false;
        }
        outLog += "[BOOT] Raw ARM64 Kernel Image loaded at 0x" + std::to_string(textOffset) +
                  " (Size: " + std::to_string(fileSize / 1024) + " KB, Entry: 0x" + std::to_string(textOffset) + ")\n";
    }

    close(fd);

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
    uint64_t dtbAddress = generateDeviceTreeBlob(config, memory, 0x00040000ULL, initrdStart, initrdSize);

    // Set CPU registers per ARM64 Linux Boot Protocol (arch/arm64/booting.rst):
    // X0 = Physical address of Device Tree (DTB) blob
    // X1, X2, X3 = 0 (reserved for future use)
    // PC = Kernel entry point (text_offset)
    // SP = Stack pointer
    cpu.setRegister(0, dtbAddress);
    cpu.setRegister(1, 0);
    cpu.setRegister(2, 0);
    cpu.setRegister(3, 0);
    cpu.setPC(entryPoint);
    cpu.setSP(0x000FFFF0ULL);

    outLog += "[BOOT] ARM64 Linux Kernel loaded at 0x" + std::to_string(entryPoint) +
              " (Size: " + std::to_string(fileSize / 1024) + " KB, Entry: 0x" + std::to_string(entryPoint) + ")\n" +
              "[BOOT] FDT Device Tree supplied at X0 = 0x00040000\n";
    return true;
}

uint64_t NativeLinuxBootLoader::generateDeviceTreeBlob(
    const LinuxBootConfig& config,
    NativeMemory& memory,
    uint64_t dtbOffset,
    uint64_t initrdStart,
    uint64_t initrdSize
) {
    // Construct real Flattened Device Tree (FDT v17) per Devicetree Specification
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

    static const uint32_t FDT_BEGIN_NODE = 0x00000001;
    static const uint32_t FDT_END_NODE   = 0x00000002;
    static const uint32_t FDT_PROP       = 0x00000003;
    static const uint32_t FDT_END        = 0x00000009;

    auto toBigEndian32 = [](uint32_t val) -> uint32_t {
        return ((val >> 24) & 0xFF) | ((val >> 8) & 0xFF00) | ((val << 8) & 0xFF0000) | ((val << 24) & 0xFF000000);
    };

    auto toBigEndian64 = [](uint64_t val) -> uint64_t {
        return ((val >> 56) & 0xFFULL) |
               ((val >> 40) & 0xFF00ULL) |
               ((val >> 24) & 0xFF0000ULL) |
               ((val >> 8)  & 0xFF000000ULL) |
               ((val << 8)  & 0xFF00000000ULL) |
               ((val << 24) & 0xFF0000000000ULL) |
               ((val << 40) & 0xFF000000000000ULL) |
               ((val << 56) & 0xFF00000000000000ULL);
    };

    std::vector<uint8_t> dtStruct;
    std::vector<uint8_t> dtStrings;

    auto addString = [&](const std::string& str) -> uint32_t {
        for (size_t i = 0; i < dtStrings.size(); ) {
            if (std::strcmp(reinterpret_cast<const char*>(&dtStrings[i]), str.c_str()) == 0) {
                return static_cast<uint32_t>(i);
            }
            i += std::strlen(reinterpret_cast<const char*>(&dtStrings[i])) + 1;
        }
        uint32_t off = static_cast<uint32_t>(dtStrings.size());
        for (char c : str) dtStrings.push_back(static_cast<uint8_t>(c));
        dtStrings.push_back(0);
        return off;
    };

    auto emitBE32 = [&](uint32_t v) {
        uint32_t be = toBigEndian32(v);
        const uint8_t* p = reinterpret_cast<const uint8_t*>(&be);
        dtStruct.insert(dtStruct.end(), p, p + 4);
    };

    auto beginNode = [&](const std::string& name) {
        emitBE32(FDT_BEGIN_NODE);
        for (char c : name) dtStruct.push_back(static_cast<uint8_t>(c));
        dtStruct.push_back(0);
        while (dtStruct.size() % 4 != 0) dtStruct.push_back(0);
    };

    auto endNode = [&]() {
        emitBE32(FDT_END_NODE);
    };

    auto addProp = [&](const std::string& name, const void* data, uint32_t len) {
        emitBE32(FDT_PROP);
        emitBE32(len);
        emitBE32(addString(name));
        if (len > 0 && data != nullptr) {
            const uint8_t* p = static_cast<const uint8_t*>(data);
            dtStruct.insert(dtStruct.end(), p, p + len);
            while (dtStruct.size() % 4 != 0) dtStruct.push_back(0);
        }
    };

    auto addPropU32 = [&](const std::string& name, uint32_t val) {
        uint32_t be = toBigEndian32(val);
        addProp(name, &be, 4);
    };

    auto addPropU64 = [&](const std::string& name, uint64_t val) {
        uint64_t be = toBigEndian64(val);
        addProp(name, &be, 8);
    };

    auto addPropString = [&](const std::string& name, const std::string& str) {
        addProp(name, str.c_str(), static_cast<uint32_t>(str.length() + 1));
    };

    // Build Canonical ARM64 FDT
    beginNode(""); // Root
    addPropU32("#address-cells", 2);
    addPropU32("#size-cells", 2);
    addPropString("model", "linux,dummy-virt");
    addPropString("compatible", "linux,dummy-virt");

    beginNode("chosen");
    std::string cmd = config.cmdline;
    if (cmd.empty()) {
        if (initrdSize > 0) {
            cmd = "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init";
        } else {
            cmd = "console=ttyAMA0,115200 root=/dev/vda1 rw earlycon=pl011,0x09000000 init=/init";
        }
    }
    addPropString("bootargs", cmd);
    addPropString("stdout-path", "/pl011@9000000");
    if (initrdSize > 0) {
        addPropU64("linux,initrd-start", initrdStart);
        addPropU64("linux,initrd-end", initrdStart + initrdSize);
    }
    endNode(); // /chosen

    beginNode("cpus");
    addPropU32("#address-cells", 1);
    addPropU32("#size-cells", 0);
    int coreCount = std::max(1, config.cpuCount);
    for (int i = 0; i < coreCount; ++i) {
        std::string cpuNodeName = "cpu@" + std::to_string(i);
        beginNode(cpuNodeName);
        addPropString("device_type", "cpu");
        addPropString("compatible", "arm,arm-v8");
        addPropU32("reg", static_cast<uint32_t>(i));
        addPropString("enable-method", "psci");
        endNode();
    }
    endNode(); // /cpus

    beginNode("memory@0");
    addPropString("device_type", "memory");
    uint64_t memReg[2] = { toBigEndian64(0x0ULL), toBigEndian64(config.ramSizeBytes) };
    addProp("reg", memReg, 16);
    endNode(); // /memory@0

    beginNode("intc@8000000");
    addPropString("compatible", "arm,cortex-a15-gic");
    addPropU32("#interrupt-cells", 3);
    addProp("interrupt-controller", nullptr, 0);
    uint64_t gicReg[4] = {
        toBigEndian64(0x08000000ULL), toBigEndian64(0x1000ULL),
        toBigEndian64(0x08010000ULL), toBigEndian64(0x1000ULL)
    };
    addProp("reg", gicReg, 32);
    endNode(); // /intc@8000000

    beginNode("timer");
    addPropString("compatible", "arm,armv8-timer");
    uint32_t timerInts[12] = {
        toBigEndian32(1), toBigEndian32(13), toBigEndian32(0xf08),
        toBigEndian32(1), toBigEndian32(14), toBigEndian32(0xf08),
        toBigEndian32(1), toBigEndian32(11), toBigEndian32(0xf08),
        toBigEndian32(1), toBigEndian32(10), toBigEndian32(0xf08)
    };
    addProp("interrupts", timerInts, 48);
    endNode(); // /timer

    beginNode("pl011@9000000");
    const char pl011Comp[] = "arm,pl011\0arm,primecell";
    addProp("compatible", pl011Comp, sizeof(pl011Comp));
    uint64_t uartReg[2] = { toBigEndian64(0x09000000ULL), toBigEndian64(0x1000ULL) };
    addProp("reg", uartReg, 16);
    uint32_t uartInts[3] = { toBigEndian32(0), toBigEndian32(1), toBigEndian32(4) }; // GIC SPI 1 (IRQ 33)
    addProp("interrupts", uartInts, 12);
    endNode(); // /pl011@9000000

    beginNode("virtio_block@a000000");
    addPropString("compatible", "virtio,mmio");
    uint64_t blkReg[2] = { toBigEndian64(0x0a000000ULL), toBigEndian64(0x200ULL) };
    addProp("reg", blkReg, 16);
    uint32_t blkInts[3] = { toBigEndian32(0), toBigEndian32(16), toBigEndian32(4) }; // GIC SPI 16 (IRQ 48)
    addProp("interrupts", blkInts, 12);
    endNode(); // /virtio_block@a000000

    endNode(); // root node
    emitBE32(FDT_END);

    // Assemble final FDT buffer
    size_t headerSize = sizeof(FdtHeader);
    size_t rsvmapSize = 16; // 0, 0 terminated
    size_t totalFdtSize = headerSize + rsvmapSize + dtStruct.size() + dtStrings.size();

    FdtHeader hdr;
    hdr.magic = toBigEndian32(0xd00dfeed);
    hdr.totalsize = toBigEndian32(static_cast<uint32_t>(totalFdtSize));
    hdr.off_mem_rsvmap = toBigEndian32(static_cast<uint32_t>(headerSize));
    hdr.off_dt_struct = toBigEndian32(static_cast<uint32_t>(headerSize + rsvmapSize));
    hdr.off_dt_strings = toBigEndian32(static_cast<uint32_t>(headerSize + rsvmapSize + dtStruct.size()));
    hdr.version = toBigEndian32(17);
    hdr.last_comp_version = toBigEndian32(16);
    hdr.boot_cpuid_phys = 0;
    hdr.size_dt_strings = toBigEndian32(static_cast<uint32_t>(dtStrings.size()));
    hdr.size_dt_struct = toBigEndian32(static_cast<uint32_t>(dtStruct.size()));

    if (memory.isValidAddress(dtbOffset, totalFdtSize)) {
        uint8_t* ptr = memory.getRawBuffer() + dtbOffset;
        std::memset(ptr, 0, totalFdtSize);
        std::memcpy(ptr, &hdr, headerSize);
        // rsvmap remains 16 zeroes
        std::memcpy(ptr + headerSize + rsvmapSize, dtStruct.data(), dtStruct.size());
        std::memcpy(ptr + headerSize + rsvmapSize + dtStruct.size(), dtStrings.data(), dtStrings.size());
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
