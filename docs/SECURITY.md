# Mobile BM Security & Anti-Piracy Architecture

## 1. Threat Model & Security Objectives

Mobile BM is designed to prevent unauthorized copying, redistribution, reverse engineering, and license circumvention while maintaining high performance and offline usability.

### Threat Vectors Mitigated:
1. **APK Cloning & Repackaging:** Attackers decompiling the APK, removing license checks, re-signing with a different key, and redistributing on pirate app stores.
2. **Key Sharing & Multi-Device Abuse:** A single purchased key shared on forums across thousands of devices.
3. **Clock Rollback Attacks:** Users turning back device time to indefinitely extend the 14-day trial or 7-day offline grace period.
4. **Dynamic Frida / Xposed Hooking:** Runtime manipulation of license state variables or method hooking.
5. **Replay Attacks:** Intercepting previously valid server activation payloads and replaying them on other devices.

---

## 2. Multi-Layered Defense Architecture

```
Layer 1: Binary & Compiler Protection (R8, ProGuard, Obfuscation, Log Stripping)
Layer 2: Runtime Environment Integrity (Package Verification, Signature Fingerprint, Hooking Checks)
Layer 3: Device Fingerprinting & Monotonic Monitored Timers (Hardware-locked Tokens)
Layer 4: Encrypted Local Storage with HMAC-SHA256 Seals (Offline Vault)
Layer 5: Authoritative HTTPS Server Validation with Anti-Replay Nonces
```

### A. Binary Protection & R8 Configuration
- **Code Shrinking & Obfuscation:** R8 minifies classes, fields, and method names into uninformative symbols (`a`, `b`, `c`).
- **Log Stripping:** `android.util.Log.d` and `android.util.Log.v` calls are stripped in release builds (`-assumenosideeffects`).
- **Signature Pinning:** ProGuard rules preserve internal security checks (`ProjectProtectionManager`, `TimeTamperDetector`, `LicenseManager`) to prevent R8 dead-code elimination.

### B. Package Identity & Certificate Fingerprinting
`ProjectProtectionManager.performProtectionAudit(context)`:
- Verifies that `context.packageName` matches `com.example` or the authorized `com.aistudio` prefix.
- Retrieves the active signing certificate SHA-256 fingerprint using Android's `SigningInfo` API.
- Transmits this fingerprint to the licensing server on activation. If the APK was re-signed with a pirate debug key, the server rejects activation immediately.

### C. Anti-Hooking & Debugger Detection
- Checks `Debug.isDebuggerConnected()` and `Debug.waitingForDebugger()`.
- Inspects `/proc/self/maps` and system binaries for Frida (`frida-server`, `gadget`), Xposed, and Substrate hooks.
- Used as a defense-in-depth risk signal in the integrity score calculation.

### D. Device Binding & Monotonic Clock Protection
- `DeviceFingerprintGenerator`: Builds an SHA-256 hash using `Build.BOARD`, `Build.HARDWARE`, `Build.MANUFACTURER`, `Build.DEVICE`, and Android's secure installation identifier.
- `TimeTamperDetector`: Tracks high-water monotonic checkpoints and compares against `SystemClock.elapsedRealtime()`. Reversing wall-clock time invalidates offline grace tokens immediately.

---

## 3. Release Signing & Production Keystore Checklist

1. **Keystore Management:**
   - Never commit `.jks`, `.keystore`, or passwords to version control (`.gitignore` must contain `*.jks` and `*.keystore`).
   - Use environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) in CI/CD build environments.
2. **Build Configuration:**
   - Run `gradle :app:assembleRelease` to produce release APKs.
   - Verify that release APKs are signed using APK Signature Scheme v2/v3.
3. **Secrets Isolation:**
   - API secrets and server signing keys reside strictly on the server backend.
   - Client APK contains only public public-key infrastructure or HMAC validation secrets.

---

## 4. Known Security Limitations & Realistic Threat Posture

1. **Client-Side Vulnerabilities:** Any code running client-side on an Android device is ultimately vulnerable to sufficiently motivated reverse engineers with kernel-level instrumentation or rooted emulators.
2. **Server Authority:** The server remains the ultimate authority. Even if an attacker temporarily patches a local binary, server-dependent features (multi-device syncing, official kernel updates, online VM registries) require authentic server-issued tokens.
3. **Legitimate Rooted Users:** Mobile BM is a virtualization utility popular among advanced Android enthusiasts. Consequently, root detection is implemented as a risk signal rather than a hard crash to avoid alienating legitimate power users.
