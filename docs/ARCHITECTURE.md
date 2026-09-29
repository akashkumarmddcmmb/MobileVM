# MobileVM Architecture & System Design

## 1. High-Level Architecture

```
+-------------------------------------------------------------------------+
|                          Jetpack Compose UI                             |
|    (VMHomeView, VMCreatorWizard, StorageManager, AdminDashboard)        |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                    VM Management & ViewModel Layer                      |
|       (VMViewModel, ApplicationRegistry, MemoryManager, VMLogger)       |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                            VMEngine Layer                               |
|   (VMEngine, VMConfig, VirtioGPUBitmapDisplay, UartPL011ConsoleBackend) |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                  Virtual Hardware & Subsystem Layer                     |
|  - CPU: ARM64 Interpreter & Register State (X0-X30, SP, PC, PSTATE)    |
|  - Memory: Direct Memory Manager with Host Physical Headroom Bounds     |
|  - Storage: Sparse Virtual Disks, Raw Images, Room Database Persistence |
|  - Shared Folders: SAF Android Sandbox with Path Traversal Protection   |
|  - Clipboard: Bidirectional Unicode Bridge with Loop Protection        |
|  - USB Passthrough: UsbManager Router & Drivers (HID, BOT, CDC-ACM)     |
|  - Network: Userspace SLIRP NAT Engine, DHCP, DNS, Virtual NIC          |
|  - Firmware & ACPI: UEFI NVRAM, RSDP/MADT/FADT, Device Tree Generators  |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                     Native C++ Engine & JNI Bridge                      |
|                  (CMake 3.22.1, NDK 26.1, CMakeLists.txt)               |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
|                     Guest Operating System Layer                        |
|        (Linux Kernel vmlinuz, Initramfs, User-provided Media)           |
+-------------------------------------------------------------------------+
```

---

## 2. Subsystem Implementations

### A. ARM64 CPU & Interrupt Controller
- **Interpreter Engine**: Step-by-step instruction execution loop running on a dedicated background coroutine (`Dispatchers.Default`).
- **GIC & Timers**: Generic Interrupt Controller v2 (GICv2) distributor and CPU interfaces paired with ARM generic virtual timers.
- **State Transitions**: `CREATED`, `STARTING`, `RUNNING`, `PAUSED`, `STOPPING`, `STOPPED`, `ERROR`.

### B. Storage & Virtual Disks
- **Database Persistence**: Room SQLite database tracking VM configurations, virtual disks, snapshots, and backups.
- **Bounded Streaming**: 64 KB buffered I/O chunks (`BUFFER_SIZE = 65536`) to prevent memory exhaustion and Android Heap OOM.
- **Path Security**: Canonical path resolution strictly preventing `../` or root escaping.

### C. Host Integration (Shared Folders & Clipboard)
- **SAF Integration**: Android Storage Access Framework with persisted URI permissions.
- **Unicode Clipboard**: Bidirectional text clipboard with length bounds (256 KB) and state reflection loop suppression.

### D. USB Passthrough Subsystem
- **Android UsbManager Router**: Device discovery with explicit runtime user permission requests.
- **Supported Device Types**: HID Keyboard, HID Mouse, USB Mass Storage (SCSI BOT), and Serial CDC-ACM.

### E. SLIRP Userspace Networking
- **Virtual NIC**: Layer-2 MAC frame generation, ARP responses, DHCP IP lease allocation, DNS forwarding, and IPv4 TCP/UDP NAT.
