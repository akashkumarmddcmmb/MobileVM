# MobileVM Long-Term Maintenance, Stability & Release Policy

---

## 1. Architectural Layering & Maintenance Boundaries

When modifying or extending MobileVM, strictly adhere to the designated layer boundaries:

```
+-------------------------------------------------------------------------+
| Layer 1: Presentation & Jetpack Compose UI                              |
| (VMHomeView, VMCreatorWizard, AdminDashboard, DiagnosticsScreen)        |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
| Layer 2: Application State & ViewModel                                  |
| (VMViewModel, ApplicationRegistry, MemoryManager, VMLogger)             |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
| Layer 3: Virtual Machine Engine & Core Abstractions                     |
| (VMEngine, VMConfig, VirtioGPUBitmapDisplay, UartPL011ConsoleBackend)  |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
| Layer 4: Virtual Hardware & Device Subsystems                           |
| (CPU Interpreter, GIC, Timers, SLIRP Network, UsbManager Router)        |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
| Layer 5: Native C++ Engine & JNI Bridge                                 |
| (mobilevm_native.cpp, CMake 3.22.1, NDK 26.1)                           |
+-------------------------------------------------------------------------+
                                    │
                                    ▼
+-------------------------------------------------------------------------+
| Layer 6: Guest OS Execution                                             |
| (Linux vmlinuz, Initramfs, DTB memory mapping)                          |
+-------------------------------------------------------------------------+
```

---

## 2. Semantic Versioning Policy (Semver 2.0.0)

MobileVM follows `MAJOR.MINOR.PATCH`:
- **MAJOR (X.0.0)**: Breaking architectural changes, incompatible virtual disk or snapshot format revisions, or major Android framework minimum SDK baseline bumps.
- **MINOR (1.X.0)**: Backward-compatible feature additions (e.g., new virtual device emulation, additional OS cloud manifests, new UI dashboards).
- **PATCH (1.0.X)**: Bug fixes, security patches, performance optimizations, and documentation revisions.

*Synchronization Rule*: `versionCode`, `versionName` in `app/build.gradle.kts`, Git tags (`v1.0.0`), GitHub Releases, and `AppUpdateManager` must remain strictly aligned.

---

## 3. Change Management & Classification

Every change must be categorized under one of the following tags:
- `[FEATURE]`: New user-facing capability.
- `[BUGFIX]`: Correction of unintended behavior or crash.
- `[SECURITY]`: Remediation of memory safety, sandbox isolation, or path traversal vulnerabilities.
- `[PERFORMANCE]`: Latency reduction, throughput improvement, or memory footprint reduction.
- `[REFACTOR]`: Internal restructuring without behavior modification.
- `[BUILD]`: Gradle, CMake, NDK, or CI/CD workflow updates.

---

## 4. Regression Testing & Release Gates

Before tagging any candidate release, the following cycle is strictly mandatory:
```bash
./gradlew clean
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```
- **Native C++ changes**: Must be compiled and verified across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- **Storage changes**: Must verify sparse disk read/write bounds and path traversal checks (`../`).
- **Database changes**: Room migrations must never delete or recreate existing virtual disk files without explicit user consent.

---

## 5. Rollback & Data Preservation Strategy

If an update introduces regressions:
1. **Never delete user data**: VM virtual disks, snapshots, and NVRAM variables in the app private sandbox (`context.filesDir`) must remain intact across updates and rollbacks.
2. **Backward-compatible database**: Room schema changes must use explicit migrations rather than `fallbackToDestructiveMigration()`.
3. **Graceful Update Recovery**: If a GitHub update download is interrupted or malformed, `AppUpdateManager` retains the currently installed working version without state corruption.

---

## 6. Logging, Privacy & Zero-Secret Policy

- Production logging must pass through `VMLogger.sanitize()`.
- Passwords, private keys, authorization tokens, and API credentials are automatically scrubbed (`password=***`, `token=***`).
- MobileVM operates with zero remote telemetry, zero analytics tracking, and zero background profiling.
