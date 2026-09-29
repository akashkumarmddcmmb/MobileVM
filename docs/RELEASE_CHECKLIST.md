# MobileVM Release Candidate Checklist

Before tagging and releasing any version of MobileVM, verify every item below:

```
================================================================================
RELEASE CANDIDATE VERIFICATION CHECKLIST
================================================================================

1. BUILD & TOOLCHAIN
 [x] Clean Debug Build:            ./gradlew assembleDebug (PASS)
 [x] Clean Release Build:          ./gradlew assembleRelease (PASS)
 [x] Unit Test Suite:              ./gradlew testDebugUnitTest (109 / 109 PASS)
 [x] Applet Compilation:           compile_applet tool (PASS)
 [x] Native CMake Compilation:     All 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64)

2. SECURITY & SECRETS AUDIT
 [x] Zero Hardcoded Secrets:       Scrubbed passwords, tokens, and keys from source
 [x] Log Sanitization:             VMLogger strips sensitive authentication tokens
 [x] Path Traversal Protection:    Canonical verification in SharedFolder & OSStorage
 [x] Minimal Manifest Permissions: Only INTERNET and optional USB Host declared

3. LICENSING & LEGAL
 [x] Proprietary Notice:           LegalLicenseManager.PROPRIETARY_COPYRIGHT_NOTICE
 [x] GPLv2 Linux Kernel:           Clean memory separation compliance
 [x] Canonical Ubuntu Trademarks:  Compliant with trademark and cloud image policy
 [x] User-Provided Windows Model:  No bundled Microsoft binaries; user-provided media

4. HOST INTEGRATION & SUBSYSTEMS
 [x] Shared Folders:               SAF sandboxing with CRUD, streaming, and bounds
 [x] Clipboard Bridge:             Unicode support, 256KB bound, loop prevention
 [x] USB Router:                   Explicit user permission, HID & Storage drivers
 [x] Networking:                   Userspace SLIRP NAT, DHCP, DNS, and packet parser
 [x] Watchdog & Thermal:           30s progress watchdog and PowerManager thermal tracking

5. RELEASE ARTIFACTS & PROVENANCE
 [x] Tag Format:                   v<Major>.<Minor>.<Patch> (e.g. v1.0.0)
 [x] Checksum Generation:          SHA256SUMS.txt for generated APKs
 [x] Update System:                HTTPS GitHub Releases API with semver check
================================================================================
```
