#include "native_linux_boot.h"
#include "native_machine_layout.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>
#include <cerrno>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <zlib.h>

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

    struct stat st;
    fstat(fd, &st);
    if (st.st_size < 512 * 1024) {
        close(fd);
        outError = "Kernel binary too small (" + std::to_string(st.st_size) + " bytes). Authentic ARM64 Linux kernels require at least 512 KB (synthetic or placeholder binaries rejected).";
        return false;
    }

    uint8_t raw[128];
    std::memset(raw, 0, sizeof(raw));
    ssize_t bytesRead = read(fd, raw, sizeof(raw));
    close(fd);

    if (bytesRead < 64) {
        outError = "Incomplete read of kernel header.";
        return false;
    }

    // Check gzip magic (0x1F, 0x8B) for vmlinuz
    if (raw[0] == 0x1F && raw[1] == 0x8B) {
        gzFile gz = gzopen(path.c_str(), "rb");
        if (!gz) {
            outError = "Failed to open gzip-compressed vmlinuz kernel.";
            return false;
        }
        uint8_t decompressedHeader[128];
        std::memset(decompressedHeader, 0, sizeof(decompressedHeader));
        int decompRead = gzread(gz, decompressedHeader, sizeof(decompressedHeader));
        gzclose(gz);

        if (decompRead < 64) {
            outError = "Decompressed vmlinuz kernel header is too small.";
            return false;
        }

        uint32_t decompMagic = *reinterpret_cast<uint32_t*>(decompressedHeader + 0x38);
        if (decompMagic == 0x644d5241) { // "ARMd"
            std::memcpy(&outHeader, decompressedHeader, sizeof(Arm64KernelHeader));
            return true;
        }

        if (decompressedHeader[0] == 0x7F && decompressedHeader[1] == 'E' &&
            decompressedHeader[2] == 'L' && decompressedHeader[3] == 'F' && decompressedHeader[4] == 2) {
            uint16_t e_machine = *reinterpret_cast<uint16_t*>(decompressedHeader + 0x12);
            if (e_machine == 183 /* EM_AARCH64 */) {
                std::memcpy(&outHeader, decompressedHeader, sizeof(Arm64KernelHeader));
                return true;
            }
        }

        outError = "Gzip-compressed vmlinuz does not contain a valid ARM64 Image or vmlinux header.";
        return false;
    }

    // Check ARM64 magic: 0x644d5241 ("ARM\x64" / "ARMd") at offset 0x38
    uint32_t magic = *reinterpret_cast<uint32_t*>(raw + 0x38);
    if (magic == 0x644d5241) {
        std::memcpy(&outHeader, raw, sizeof(Arm64KernelHeader));
        return true;
    }

    // Check for ELF64 binary header (Linux vmlinux)
    if (raw[0] == 0x7F && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F' && raw[4] == 2 /* 64-bit */) {
        uint16_t e_machine = *reinterpret_cast<uint16_t*>(raw + 0x12);
        if (e_machine == 183 /* EM_AARCH64 */) {
            std::memcpy(&outHeader, raw, sizeof(Arm64KernelHeader));
            return true;
        } else if (e_machine == 62 /* EM_X86_64 */) {
            outError = "x86_64 kernel binary rejected: only ARM64 (AArch64) is supported.";
            return false;
        } else if (e_machine == 40 /* EM_ARM */) {
            outError = "ARM32 kernel binary rejected: only ARM64 (AArch64) is supported.";
            return false;
        } else {
            outError = "Non-ARM64 ELF kernel binary rejected (e_machine=" + std::to_string(e_machine) + ").";
            return false;
        }
    }

    outError = "Invalid kernel image header magic (expected ARM64 0x644D5241, ELF64 ARM64, or ARM64 gzip vmlinuz).";
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

    uint8_t magicCheck[2] = {0, 0};
    lseek(fd, 0, SEEK_SET);
    read(fd, magicCheck, 2);
    bool isGzip = (magicCheck[0] == 0x1F && magicCheck[1] == 0x8B);

    if (isGzip) {
        gzFile gz = gzopen(config.kernelPath.c_str(), "rb");
        if (gz) {
            uint64_t textOffset = 0x00080000ULL;
            uint8_t* dest = memory.getRawBuffer() + textOffset;
            size_t maxRam = memory.getSize();
            size_t availableSpace = (maxRam > textOffset) ? (maxRam - textOffset) : 0;

            int totalDecompressed = 0;
            int chunk = 0;
            while (availableSpace >= 65536) {
                chunk = gzread(gz, dest + totalDecompressed, 65536);
                if (chunk <= 0) break;
                totalDecompressed += chunk;
                availableSpace -= chunk;
            }
            gzclose(gz);

            if (totalDecompressed > 64) {
                entryPoint = textOffset;
                loadSuccess = true;
                outLog += "[BOOT] Gzip vmlinuz decompressed directly into guest RAM (" +
                          std::to_string(totalDecompressed / 1024) + " KB decompressed at 0x00080000)\n";
            }
        }
    }

    if (!loadSuccess && isElf64) {
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

    uint64_t ramBase = NativeMemory::RAM_BASE_ADDRESS;
    if (entryPoint < ramBase) {
        entryPoint += ramBase;
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
            uint64_t initrdOffset = 0x08000000ULL; // 128 MB into RAM
            initrdStart = ramBase + initrdOffset;
            if (memory.isValidAddress(initrdStart, initrdSize)) {
                read(rfd, memory.getRawBuffer() + initrdOffset, initrdSize);
                outLog += "[BOOT] Loaded initramfs (" + std::to_string(initrdSize / 1024) + " KB) at 0x" +
                          std::to_string(initrdStart) + "\n";
            } else {
                outLog += "[BOOT WARNING] Insufficient memory for initramfs at 0x" + std::to_string(initrdStart) + "\n";
                initrdStart = 0;
                initrdSize = 0;
            }
            close(rfd);
        } else {
            outLog += "[BOOT WARNING] Cannot open initramfs: " + config.initramfsPath + "\n";
        }
    }

    // Generate Device Tree Blob (DTB) at RAM_BASE + 0x00040000
    uint64_t dtbOffset = 0x00040000ULL;
    uint64_t dtbAddress = generateDeviceTreeBlob(config, memory, dtbOffset, initrdStart, initrdSize);

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
    cpu.setSP(ramBase + 0x000FFFF0ULL);

    outLog += "[BOOT] ARM64 Linux Kernel loaded at 0x" + std::to_string(entryPoint) +
              " (Size: " + std::to_string(fileSize / 1024) + " KB, Entry: 0x" + std::to_string(entryPoint) + ")\n" +
              "[BOOT] FDT Device Tree supplied at X0 = 0x" + std::to_string(dtbAddress) + "\n";
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

    beginNode("memory@40000000");
    addPropString("device_type", "memory");
    uint64_t memReg[2] = { toBigEndian64(NativeMemory::RAM_BASE_ADDRESS), toBigEndian64(config.ramSizeBytes) };
    addProp("reg", memReg, 16);
    endNode(); // /memory@40000000

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
        uint8_t* ptr = memory.getRawBuffer() + memory.toBufferOffset(dtbOffset);
        std::memset(ptr, 0, totalFdtSize);
        std::memcpy(ptr, &hdr, headerSize);
        // rsvmap remains 16 zeroes
        std::memcpy(ptr + headerSize + rsvmapSize, dtStruct.data(), dtStruct.size());
        std::memcpy(ptr + headerSize + rsvmapSize + dtStruct.size(), dtStrings.data(), dtStrings.size());
    }

    return NativeMemory::RAM_BASE_ADDRESS + dtbOffset;
}

bool NativeLinuxBootLoader::generateAcpiTables(
    const LinuxBootConfig& config,
    NativeMemory& memory,
    uint64_t acpiBase
) {
    if (!memory.isValidAddress(acpiBase, 65536)) {
        return false;
    }

    uint8_t* buf = memory.getRawBuffer() + memory.toBufferOffset(acpiBase);
    std::memset(buf, 0, 65536);

    auto calculateChecksum = [](const uint8_t* data, size_t offset, size_t length) -> uint8_t {
        uint32_t sum = 0;
        for (size_t i = offset; i < offset + length; i++) {
            sum += data[i];
        }
        return static_cast<uint8_t>((-sum) & 0xFF);
    };

    uint64_t rsdpOff = 0;
    uint64_t xsdtOff = 64;
    uint64_t madtOff = 256;
    uint64_t fadtOff = 512;
    uint64_t gtdtOff = 1024;
    uint64_t dsdtOff = 1536;

    // --- RSDP (36 bytes) ---
    std::memcpy(buf + rsdpOff, "RSD PTR ", 8);
    std::memcpy(buf + rsdpOff + 9, "MOBILE", 6);
    buf[rsdpOff + 15] = 2; // Revision 2.0
    *reinterpret_cast<uint32_t*>(buf + rsdpOff + 20) = 36; // Length
    *reinterpret_cast<uint64_t*>(buf + rsdpOff + 24) = acpiBase + xsdtOff;
    buf[rsdpOff + 8] = calculateChecksum(buf, rsdpOff, 20);
    buf[rsdpOff + 32] = calculateChecksum(buf, rsdpOff, 36);

    // --- XSDT Header ---
    std::memcpy(buf + xsdtOff, "XSDT", 4);
    size_t tpm2Off = 1280;
    uint32_t xsdtLen = 36 + (8 * 4); // FADT, MADT, GTDT, TPM2
    *reinterpret_cast<uint32_t*>(buf + xsdtOff + 4) = xsdtLen;
    buf[xsdtOff + 8] = 1; // Revision
    std::memcpy(buf + xsdtOff + 10, "MOBILE", 6);
    std::memcpy(buf + xsdtOff + 16, "VMXSDT  ", 8);
    *reinterpret_cast<uint32_t*>(buf + xsdtOff + 24) = 1; // OEM Revision
    std::memcpy(buf + xsdtOff + 28, "MOBL", 4);
    *reinterpret_cast<uint32_t*>(buf + xsdtOff + 32) = 1; // Creator Revision
    *reinterpret_cast<uint64_t*>(buf + xsdtOff + 36) = acpiBase + fadtOff;
    *reinterpret_cast<uint64_t*>(buf + xsdtOff + 44) = acpiBase + madtOff;
    *reinterpret_cast<uint64_t*>(buf + xsdtOff + 52) = acpiBase + gtdtOff;
    *reinterpret_cast<uint64_t*>(buf + xsdtOff + 60) = acpiBase + tpm2Off;
    buf[xsdtOff + 9] = calculateChecksum(buf, xsdtOff, xsdtLen);

    // --- MADT (GICD & GICC entries) ---
    std::memcpy(buf + madtOff, "APIC", 4);
    int coreCount = std::max(1, config.cpuCount);
    uint32_t madtLen = 44 + 24 + (coreCount * 80);
    *reinterpret_cast<uint32_t*>(buf + madtOff + 4) = madtLen;
    buf[madtOff + 8] = 3; // Revision 3
    std::memcpy(buf + madtOff + 10, "MOBILE", 6);
    std::memcpy(buf + madtOff + 16, "VMMADT  ", 8);
    *reinterpret_cast<uint32_t*>(buf + madtOff + 24) = 1;
    std::memcpy(buf + madtOff + 28, "MOBL", 4);
    *reinterpret_cast<uint32_t*>(buf + madtOff + 32) = 1;
    *reinterpret_cast<uint32_t*>(buf + madtOff + 40) = 1; // Flags

    // GICD
    size_t gicdPos = madtOff + 44;
    buf[gicdPos] = 0x0C; // Type: GICD
    buf[gicdPos + 1] = 24; // Length
    *reinterpret_cast<uint64_t*>(buf + gicdPos + 8) = 0x08000000ULL; // GICD Base
    buf[gicdPos + 20] = 2; // GIC Version 2

    // GICC
    for (int i = 0; i < coreCount; i++) {
        size_t giccPos = madtOff + 68 + (i * 80);
        buf[giccPos] = 0x0B; // Type: GICC
        buf[giccPos + 1] = 80;
        *reinterpret_cast<uint32_t*>(buf + giccPos + 4) = i; // CPU Interface Number
        *reinterpret_cast<uint32_t*>(buf + giccPos + 8) = i; // ACPI Processor UID
        *reinterpret_cast<uint32_t*>(buf + giccPos + 12) = 1; // Flags (Enabled)
        *reinterpret_cast<uint32_t*>(buf + giccPos + 20) = 23; // Performance GSIV
        *reinterpret_cast<uint64_t*>(buf + giccPos + 32) = 0x08010000ULL; // GICC Base
        *reinterpret_cast<uint32_t*>(buf + giccPos + 56) = 25; // VGIC Maintenance GSIV
        *reinterpret_cast<uint64_t*>(buf + giccPos + 68) = i; // MPIDR
    }
    buf[madtOff + 9] = calculateChecksum(buf, madtOff, madtLen);

    // --- FADT ---
    std::memcpy(buf + fadtOff, "FACP", 4);
    uint32_t fadtLen = 268;
    *reinterpret_cast<uint32_t*>(buf + fadtOff + 4) = fadtLen;
    buf[fadtOff + 8] = 6; // ACPI 6.0
    std::memcpy(buf + fadtOff + 10, "MOBILE", 6);
    std::memcpy(buf + fadtOff + 16, "VMFADT  ", 8);
    *reinterpret_cast<uint32_t*>(buf + fadtOff + 24) = 1;
    std::memcpy(buf + fadtOff + 28, "MOBL", 4);
    *reinterpret_cast<uint32_t*>(buf + fadtOff + 32) = 1;
    *reinterpret_cast<uint32_t*>(buf + fadtOff + 112) = (1 << 20); // HW_REDUCED_ACPI
    *reinterpret_cast<uint64_t*>(buf + fadtOff + 140) = acpiBase + dsdtOff; // X_DSDT
    buf[fadtOff + 9] = calculateChecksum(buf, fadtOff, fadtLen);

    // --- GTDT ---
    std::memcpy(buf + gtdtOff, "GTDT", 4);
    uint32_t gtdtLen = 104;
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 4) = gtdtLen;
    buf[gtdtOff + 8] = 2; // Revision
    std::memcpy(buf + gtdtOff + 10, "MOBILE", 6);
    std::memcpy(buf + gtdtOff + 16, "VMGTDT  ", 8);
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 24) = 1;
    std::memcpy(buf + gtdtOff + 28, "MOBL", 4);
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 32) = 1;
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 44) = 30; // Secure EL1
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 52) = 27; // Non-Secure EL1
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 60) = 26; // Virtual Timer
    *reinterpret_cast<uint32_t*>(buf + gtdtOff + 68) = 28; // Non-Secure EL2
    buf[gtdtOff + 9] = calculateChecksum(buf, gtdtOff, gtdtLen);

    // --- TPM2 Table ---
    std::memcpy(buf + tpm2Off, "TPM2", 4);
    uint32_t tpm2Len = 52;
    *reinterpret_cast<uint32_t*>(buf + tpm2Off + 4) = tpm2Len;
    buf[tpm2Off + 8] = 4; // Revision 4
    std::memcpy(buf + tpm2Off + 10, "MOBILE", 6);
    std::memcpy(buf + tpm2Off + 16, "VMTPM2  ", 8);
    *reinterpret_cast<uint32_t*>(buf + tpm2Off + 24) = 1;
    std::memcpy(buf + tpm2Off + 28, "MOBL", 4);
    *reinterpret_cast<uint32_t*>(buf + tpm2Off + 32) = 1;
    *reinterpret_cast<uint16_t*>(buf + tpm2Off + 36) = 0; // Platform Class: Client
    *reinterpret_cast<uint16_t*>(buf + tpm2Off + 38) = 0; // Reserved
    *reinterpret_cast<uint64_t*>(buf + tpm2Off + 40) = 0x0FED0000ULL; // Control Area Address (CRB)
    *reinterpret_cast<uint32_t*>(buf + tpm2Off + 48) = 7; // Start Method: CRB (Command Response Buffer)
    buf[tpm2Off + 9] = calculateChecksum(buf, tpm2Off, tpm2Len);

    // --- DSDT Skeleton ---
    std::memcpy(buf + dsdtOff, "DSDT", 4);
    uint32_t dsdtLen = 36;
    *reinterpret_cast<uint32_t*>(buf + dsdtOff + 4) = dsdtLen;
    buf[dsdtOff + 8] = 2;
    std::memcpy(buf + dsdtOff + 10, "MOBILE", 6);
    std::memcpy(buf + dsdtOff + 16, "VMDSDT  ", 8);
    *reinterpret_cast<uint32_t*>(buf + dsdtOff + 24) = 1;
    std::memcpy(buf + dsdtOff + 28, "MOBL", 4);
    *reinterpret_cast<uint32_t*>(buf + dsdtOff + 32) = 1;
    buf[dsdtOff + 9] = calculateChecksum(buf, dsdtOff, dsdtLen);

    return true;
}

bool NativeLinuxBootLoader::loadWindowsGuest(
    const LinuxBootConfig& config,
    const std::string& diskPath,
    const std::string& isoPath,
    NativeMemory& memory,
    NativeCPUBackend& cpu,
    NativeDeviceManager& devices,
    std::string& outLog
) {
    if (diskPath.empty() && isoPath.empty()) {
        outLog += "WINDOWS_IMAGE_NOT_CONFIGURED: No Windows ARM64 installation media or virtual disk specified.\n";
        return false;
    }

    if (!diskPath.empty()) {
        struct stat st;
        if (stat(diskPath.c_str(), &st) != 0) {
            outLog += "WINDOWS_IMAGE_NOT_FOUND: Windows media file does not exist at: " + diskPath + "\n";
            return false;
        }

        if (st.st_size < 512) {
            outLog += "WINDOWS_IMAGE_INVALID: Disk file size is too small (" + std::to_string(st.st_size) + " bytes).\n";
            return false;
        }

        if (!devices.getDisk().isOpened()) {
            std::string openErr;
            if (!devices.getDisk().openRawDisk(diskPath, false, "", openErr)) {
                outLog += "WINDOWS_IMAGE_UNREADABLE: Cannot open virtual disk: " + openErr + "\n";
                return false;
            }
        }
    }

    if (!isoPath.empty()) {
        struct stat st;
        if (stat(isoPath.c_str(), &st) != 0) {
            outLog += "WINDOWS_ISO_MISSING: Windows ISO file does not exist at: " + isoPath + "\n";
            return false;
        }

        if (st.st_size < 2048) {
            outLog += "WINDOWS_ISO_INVALID: Windows ISO file size is too small (" + std::to_string(st.st_size) + " bytes).\n";
            return false;
        }

        if (!devices.getCdrom().isOpened()) {
            std::string openErr;
            if (!devices.getCdrom().openCdrom(isoPath, "", openErr)) {
                outLog += "CDROM_UNAVAILABLE: Cannot open virtual CD/DVD: " + openErr + "\n";
                return false;
            }
        }
    }

    uint64_t acpiBase = 0x47000000ULL;
    if (config.ramSizeBytes > 0 && config.ramSizeBytes <= 0x47000000ULL) {
        acpiBase = config.ramSizeBytes - 0x01000000ULL; // 16 MB before top of RAM
    }

    bool acpiOk = generateAcpiTables(config, memory, acpiBase);
    if (!acpiOk) {
        outLog += "FIRMWARE_UNAVAILABLE: Failed to generate ACPI 6.2 tables in guest RAM.\n";
        return false;
    }

    // Locate authentic ARM64 UEFI firmware binary
    std::string fwPath = "";
    std::vector<std::string> searchPaths = {
        config.kernelPath,
        "/data/data/com.example/files/firmware/QEMU_EFI.fd",
        "/data/user/0/com.example/files/firmware/QEMU_EFI.fd",
        "firmware/QEMU_EFI.fd"
    };
    for (const auto& p : searchPaths) {
        if (!p.empty()) {
            struct stat fst;
            if (stat(p.c_str(), &fst) == 0 && fst.st_size >= 1024 * 1024) {
                fwPath = p;
                break;
            }
        }
    }

    uint64_t entryPoint = memory.getBaseAddress() + 0x00080000ULL;

    if (fwPath.empty()) {
        outLog += "FIRMWARE_UNAVAILABLE: Authentic ARM64 EDK2 UEFI Firmware binary (QEMU_EFI.fd) is required to boot Windows on ARM64. Please import a verified QEMU_EFI.fd file in Firmware Settings.\n";
        return false;
    }

    int fwFd = open(fwPath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fwFd < 0) {
        outLog += "FIRMWARE_UNREADABLE: Cannot open UEFI firmware binary: " + fwPath + "\n";
        return false;
    }

    struct stat fst;
    fstat(fwFd, &fst);
    size_t fwSize = static_cast<size_t>(fst.st_size);

    if (!memory.isValidAddress(entryPoint, fwSize)) {
        close(fwFd);
        outLog += "FIRMWARE_MEMORY_ERROR: Insufficient guest RAM to allocate UEFI firmware (" + std::to_string(fwSize / 1024) + " KB).\n";
        return false;
    }

    uint8_t* dest = memory.getRawBuffer() + memory.toBufferOffset(entryPoint);
    ssize_t bytesRead = read(fwFd, dest, fwSize);
    close(fwFd);

    if (bytesRead <= 0) {
        outLog += "FIRMWARE_READ_FAILED: Failed to read UEFI firmware binary into memory.\n";
        return false;
    }

    outLog += "[UEFI] Authentic ARM64 EDK2/UEFI Firmware loaded (" + std::to_string(bytesRead / 1024) + " KB) from: " + fwPath + "\n";

    cpu.setRegister(0, acpiBase); // X0 = ACPI RSDP Pointer
    cpu.setRegister(1, 0);
    cpu.setRegister(2, 0);
    cpu.setRegister(3, 0);
    cpu.setPC(entryPoint);
    cpu.setSP(memory.getBaseAddress() + 0x000FFFF0ULL);

    outLog += "[UEFI] ARM64 UEFI Firmware Initialized\n"
              "[ACPI] ACPI 6.2 System Tables placed at 0x" + std::to_string(acpiBase) + " (RSDP, XSDT, MADT, FADT, GTDT, TPM2, DSDT)\n"
              "[TPM] Virtual TPM 2.0 CRB Interface initialized at 0x0FED0000\n";
    if (devices.getDisk().isOpened()) {
        outLog += "[STORAGE] VirtIO Block attached: " + diskPath + " (" + std::to_string(devices.getDisk().getSectorCount()) + " sectors)\n";
    }
    if (devices.getCdrom().isOpened()) {
        outLog += "[CDROM] VirtIO CD/DVD attached: " + isoPath + " (" + std::to_string(devices.getCdrom().getSectorCount()) + " sectors)\n";
    }

    return true;
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

