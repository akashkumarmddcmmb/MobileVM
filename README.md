# MobileVM™ — ARM64 Virtual Machine for Android

[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()
[![Tests](https://img.shields.io/badge/Unit%20Tests-109%20Passed-blue.svg)]()
[![Android Min SDK](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-orange.svg)]()
[![License](https://img.shields.io/badge/License-Proprietary%20%2B%20OSS%20Attribution-lightgrey.svg)]()

MobileVM is an advanced, high-performance ARM64 hypervisor and virtualization environment engineered natively for Android devices. It enables users to boot, configure, and execute 64-bit ARM Linux operating systems with full virtual hardware isolation directly on their phone or tablet.

---

## 1. Overview & Core Features

- **ARM64 Virtual Machine Core**: High-speed instruction execution loop, register state (X0–X30, SP, PC, PSTATE), and Generic Interrupt Controller (GICv2).
- **Direct Memory Management**: Bounded guest RAM allocation with physical host RAM headroom checks to eliminate OOM instability.
- **Virtual Storage Manager**: Sparse virtual disks (`.raw`, `.img`), Room SQLite disk persistence, dynamic expansion, and incremental snapshots.
- **Virtio GPU & Console**: Direct framebuffer bitmap rendering with responsive touch/pointer input mapping and PL011 UART serial terminal.
- **Safe Host Integration**: Android Storage Access Framework (SAF) shared folders with strict path traversal protections (`../`) and bidirectional Unicode text clipboard synchronization.
- **USB Passthrough Engine**: Android `UsbManager` routing with explicit user permission prompts for HID Keyboards, HID Mice, USB Storage (BOT), and CDC-ACM serial adapters.
- **SLIRP Userspace Networking**: Virtual NIC with MAC frame parsing, ARP responder, DHCP IP lease allocation, DNS resolver forwarding, and IPv4 TCP/UDP NAT.
- **Thermal & Watchdog Monitoring**: Real-time PowerManager thermal state listener and 30-second progress stall watchdog timer.
- **Admin Dashboard & Diagnostics**: Extensible application registry with system environment metrics and zero-leak diagnostic report export.
- **GitHub Release Update Checker**: Secure HTTPS update checking with semantic version comparison and verified release downloads.

---

## 2. Architecture

```
Android UI (Jetpack Compose / Material 3)
                │
                ▼
VM Management & ViewModel Layer (VMViewModel, ApplicationRegistry)
                │
                ▼
Virtual Machine Engine (VMEngine, CPU Interpreter, MemoryManager)
                │
                ▼
Virtual Hardware Subsystems (GIC, UART, VirtioGPU, Storage, SLIRP, USB)
                │
                ▼
Native C++ Engine & JNI Bridge (NDK 26.1, CMake 3.22.1)
                │
                ▼
Guest Operating System (Linux vmlinuz, Initramfs, User Media)
```

---

## 3. Supported Host & Guest Environments

| Host Environment | Status | Details |
| :--- | :--- | :--- |
| **Android 7.0 – Android 16** | **Supported** | Minimum API 24, Target API 36 |
| **arm64-v8a** | **Native** | Primary 64-bit ARM target architecture |
| **armeabi-v7a / x86 / x86_64** | **Compiled** | Full native library compatibility across all 4 ABIs |

| Guest Architecture | Status | Details |
| :--- | :--- | :--- |
| **Linux ARM64** | **Verified** | Ubuntu, Alpine, Debian, Fedora ARM64 kernel boot |
| **Windows 11 ARM64** | **Profile Mode** | Modeled via ACPI/TPM 2.0; requires user-provided ISO |
| **x86 / x86_64 Guest** | **Not Implemented** | Target is ARM64 guest emulation |

---

## 4. Building & Running

### Prerequisites
- JDK 17 or JDK 21
- Android SDK 36
- Android NDK 26.1.10909125
- CMake 3.22.1

### Build Commands
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

---

## 5. Security & Privacy Policy

- **Zero Telemetry**: MobileVM does not collect or transmit analytics, identifiers, or personal usage data to external servers.
- **Path Traversal Protection**: All filesystem and shared folder operations enforce canonical path boundaries, strictly rejecting path traversal tokens (`../`, `..\`, `\0`).
- **Secret Scrubbing**: In-app logging automatically scrubs authentication tokens and passwords (`password=***`, `token=***`).
- **Local Sandbox Storage**: All disk images, snapshots, and NVRAM files reside entirely within Android's private app sandbox.

---

## 6. Licensing & Legal Compliance

- **MobileVM Architecture & Code**: Copyright © 2026 Akash Kumar / MobileVM Authors. All Rights Reserved.
- **Linux Kernel**: Released under GNU GPLv2. MobileVM operates as an independent emulator and does not statically link proprietary code into the Linux kernel.
- **Third-Party Libraries**: AndroidX, Jetpack Compose, Room, Kotlin Coroutines, and OkHttp are utilized under their respective Apache 2.0, MIT, and BSD licenses (see `THIRD_PARTY_NOTICES`).
