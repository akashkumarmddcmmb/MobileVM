# Changelog

All notable changes to the MobileVM project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-09-29

### Added
- **ARM64 Virtual Machine Interpreter**: Core 64-bit ARM CPU engine, register state (X0–X30, SP, PC, PSTATE), GICv2 interrupt distributor, and virtual timers.
- **Virtio GPU & Console**: Framebuffer rendering with scale/zoom/touch coordinate mapping and PL011 UART serial console.
- **Direct Memory Management**: Host RAM headroom validation, bounded memory allocation, and zero-leak lifecycle.
- **Storage Subsystem**: Sparse virtual disks, raw disk image mounting, Room SQLite database, disk expansion, and incremental snapshot persistence.
- **Host Integration (Shared Folders & Clipboard)**: Android SAF shared folders with strict path traversal protection (`../`), streaming I/O, and bidirectional Unicode clipboard sync.
- **USB Passthrough Engine**: Android `UsbManager` routing with explicit user permission prompts for HID Keyboards, HID Mice, USB Storage (BOT), and CDC-ACM serial adapters.
- **SLIRP Userspace Networking**: Virtual NIC MAC frame processor, ARP engine, DHCP lease server, DNS forwarder, and IPv4 TCP/UDP NAT.
- **Thermal & Watchdog Monitoring**: PowerManager thermal state listener and 30-second stall watchdog timer.
- **Application & Feature Registry**: Extensible administration dashboard for system telemetry and diagnostic export.
- **GitHub Releases Update Checker**: HTTPS semver comparator with direct navigation to verified release downloads.

### Security
- Comprehensive path traversal protections across all file sharing, disk mounting, and OS manifest identifiers.
- Automatic log scrubbing of passwords, authorization tokens, and private keys via `VMLogger.sanitize()`.
- Zero telemetry and zero third-party analytics data collection.

### Known Limitations
- Windows 11 ARM64 requires user-provided installation media; full graphical desktop boot performance depends on host hardware capabilities.
- x86/x86_64 guest emulation is not supported; the engine specifically targets ARM64 architectures.
