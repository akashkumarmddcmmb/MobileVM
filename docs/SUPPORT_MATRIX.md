# MobileVM Master Support & Compatibility Matrix

---

## 1. Host Platform & Architecture Matrix

| Platform / OS | ABI Architecture | Native Build | JNI Symbols | App Execution | Status |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **Android 14–16 (API 34–36)** | `arm64-v8a` | **PASS** | **PASS** | **PASS** | **VERIFIED** (Primary 64-bit ARM hypervisor target) |
| **Android 12–13 (API 31–33)** | `arm64-v8a` | **PASS** | **PASS** | **PASS** | **VERIFIED** (Full SAF & Evdev support) |
| **Android 10–11 (API 29–30)** | `arm64-v8a` | **PASS** | **PASS** | **PASS** | **VERIFIED** (Scoped storage & UsbManager) |
| **Android 7.0–9.0 (API 24–28)**| `arm64-v8a` | **PASS** | **PASS** | **PASS** | **VERIFIED** (Base sandbox execution) |
| **Android 7.0–16** | `armeabi-v7a` | **PASS** | **PASS** | **PASS** | **VERIFIED** (32-bit ARM compatibility) |
| **Android 7.0–16** | `x86_64` | **PASS** | **PASS** | **PASS** | **VERIFIED** (Desktop/Emulator testing target) |
| **Android 7.0–16** | `x86` | **PASS** | **PASS** | **PASS** | **VERIFIED** (32-bit x86 compatibility) |

---

## 2. Guest OS & Subsystem Compatibility Matrix

| Subsystem / Feature | Guest OS Target | Implementation | Runtime Tested | Status | Limitation / Notes |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **ARM64 CPU Emulation** | Linux (Ubuntu, Alpine, Debian) | Yes | Yes | **VERIFIED** | Interpreter loop, GICv2, Timers, X0–X30 registers |
| **Virtio GPU Display** | Framebuffer / Direct Bitmap | Yes | Yes | **VERIFIED** | Software bitmap rendering with touch/pointer mapping |
| **PL011 UART Console** | Serial Console (`ttyAMA0`) | Yes | Yes | **VERIFIED** | High-throughput scrolling terminal buffer |
| **Sparse Disk Storage** | Raw / Sparse Disks | Yes | Yes | **VERIFIED** | 64KB bounded streaming, Room SQLite persistence |
| **Shared Folders (SAF)** | Host Filesystem Integration | Yes | Yes | **VERIFIED** | Canonical path traversal protection (`../`) |
| **Unicode Clipboard** | Bidirectional Text Sync | Yes | Yes | **VERIFIED** | 256KB bounded buffer with reflection suppression |
| **USB Passthrough** | HID, Storage, CDC-ACM | Yes | Yes | **VERIFIED** | UsbManager router with explicit user permission |
| **SLIRP Userspace NAT** | IPv4 TCP/UDP Networking | Yes | Yes | **VERIFIED** | Virtual NIC, ARP, DHCP, and DNS forwarder |
| **Thermal & Watchdog** | PowerManager & Watchdog | Yes | Yes | **VERIFIED** | Real-time thermal classification & 30s stall watchdog |
| **Admin Dashboard** | Diagnostics & Registry | Yes | Yes | **VERIFIED** | Extensible application registry & zero-leak export |
| **In-App Update Checker**| GitHub Releases API | Yes | Yes | **VERIFIED** | Secure HTTPS semver comparison & verified download link |
| **Windows 11 ARM64** | Windows 11 ARM64 | Modeled | Partial | **PARTIALLY VERIFIED** | ACPI tables, EFI NVRAM & TPM 2.0 modeled; user supplies ISO |
| **x86 / x86_64 Guest** | x86 / x86_64 OS | No | No | **NOT IMPLEMENTED** | Engine targets 64-bit ARM (AArch64) guest architecture |

---

## 3. Security, Privacy & Compliance Matrix

| Security Domain | Verification Standard | Result | Evidence |
| :--- | :--- | :---: | :--- |
| **Secret Management** | No embedded keys or credentials | **PASS** | Source-wide regex audit and `VMLogger.sanitize()` |
| **Filesystem Sandboxing**| No path traversal (`../`, `..\`, `\0`) | **PASS** | `VMSecurityHardeningTest` canonical path checks |
| **Memory Headroom** | No OOM heap exhaustion | **PASS** | `VMPerformanceAndStabilityTest` physical RAM validation |
| **Zero Telemetry** | No remote telemetry servers | **PASS** | Zero analytics libraries declared or invoked |
| **License Compliance** | GPLv2, MIT, Apache 2.0 attribution | **PASS** | `LegalLicenseManager` and `THIRD_PARTY_NOTICES` |
