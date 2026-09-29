# MobileVM Final Blockers & Limitations Register

This document tracks all genuine engineering blockers, architectural constraints, and verified limitations of MobileVM.

---

## 1. Resolved Blockers (Phases 1–13)

| Item | Impact | Root Cause | Resolution | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Path Traversal Risk** | Sandbox escaping | Relative `../` tokens in shared folder URIs | Canonical boundary enforcement in `SharedFolderManager` & `OSStorageManager` | **RESOLVED** |
| **Log Credential Leak** | Secrets in logs | Unsanitized logging statements | Automatic regex token & password scrubbing in `VMLogger.sanitize()` | **RESOLVED** |
| **Memory OOM Crashes** | App termination | Unbounded streaming of disk images | 64 KB bounded streaming buffers in `MemoryManager` & Storage backends | **RESOLVED** |
| **CPU Watchdog Stall** | Deadlock / freeze | VM thread hangs without user feedback | 30s timeout watchdog with `VMWatchdog` kick progression | **RESOLVED** |

---

## 2. Verified Architectural Limitations

| Limitation | Impact | Root Cause | Workaround / Strategy | Priority |
| :--- | :--- | :--- | :--- | :--- |
| **x86 / x86_64 Guest Emulation** | Non-ARM guest OS unable to run | Engine interpreter specifically targets 64-bit ARM (AArch64) architecture | Run native ARM64 guest images (Ubuntu ARM64, Alpine ARM64) | **INFO** |
| **Windows 11 ARM64 Graphical Boot** | High memory & CPU requirement | Windows graphical desktop requires substantial RAM (4GB+) and user-supplied ISO | ACPI/TPM 2.0 modeled; user provides legal ARM64 installation media | **INFO** |
| **Hardware KVM on Unrooted Android** | Software interpreter fallback | Android OS restricts access to `/dev/kvm` without root privileges | Optimized C++ interpreter loop provides reliable userspace execution | **INFO** |
