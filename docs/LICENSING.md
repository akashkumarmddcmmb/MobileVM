# Mobile BM Proprietary Licensing System Architecture

## 1. System Overview
Mobile BM implements a secure, hybrid, multi-tier proprietary licensing architecture designed specifically for professional Android virtualization workloads. The architecture guarantees high security against APK tampering and license piracy while offering seamless offline resilience for mobile engineers working in disconnected environments.

---

## 2. Core Licensing Architecture & High-Level Flow

```
+-----------------------------------------------------------+
|                    Mobile BM Android App                  |
|                                                           |
|  +--------------------+         +-----------------------+ |
|  | Hardware & Package |         | Monotonic Clock Guard | |
|  | Fingerprint Engine |         | (TimeTamperDetector)  | |
|  +---------+----------+         +-----------+-----------+ |
|            |                                |             |
|            v                                v             |
|  +---------+--------------------------------+-----------+ |
|  |             LicenseManager (Orchestrator)            | |
|  +--------------------------+---------------------------+ |
|                             |                             |
|           +-----------------+-----------------+           |
|           v                                   v           |
|  +--------+-----------+            +----------+--------+  |
|  | Encrypted Offline  |            | LicenseApiClient  |  |
|  | Vault (HMAC-SHA256)|            | (TLS 1.3 / HTTPS) |  |
|  +--------------------+            +----------+--------+  |
+-----------------------------------------------|-----------+
                                                |
                                    HTTPS / TLS 1.3
                                                |
                                                v
+-----------------------------------------------------------+
|                 Authoritative License Server              |
|                                                           |
|  +-----------------------+    +-------------------------+ |
|  | Anti-Replay Nonce &   |    | Cryptographic Signer    | |
|  | Clock Drift Validator |    | (HMAC-SHA256 Token)     | |
|  +-----------+-----------+    +------------+------------+ |
|              |                             |              |
|              v                             v              |
|  +-----------+-----------------------------+------------+ |
|  |         Server Licensing Engine & Business Logic     | |
|  +---------------------------+--------------------------+ |
|                              |                            |
|                              v                            |
|  +---------------------------+--------------------------+ |
|  |    PostgreSQL / MySQL License Database (Hashed Keys) | |
|  +------------------------------------------------------+ |
+-----------------------------------------------------------+
```

---

## 3. License Status Lifecycle States

1. **ACTIVE:** Validated online by server; signed HMAC token stored locally; all plan entitlements unlocked.
2. **TRIAL:** 14-day full Pro evaluation granted locally/server-side for testing.
3. **OFFLINE-GRACE-PERIOD:** Server was verified recently (< 7 days); device is offline, but user retains plan features until grace period expires.
4. **EXPIRED:** License validity date has passed; app safely downgrades to FREE tier with warning message.
5. **REVOKED:** License key revoked by administrator due to abuse, leakage, or chargeback; immediate block.
6. **INVALID:** Key format invalid, signature mismatch, or device quota exceeded.

---

## 4. API Endpoints Specification

### A. POST `/api/license/activate`
Activates a license key for a specific device.
- **Request Body:**
  ```json
  {
    "licenseKey": "MBM-PRO-2026-TEST-7890-ABCD",
    "deviceId": "MBM-DEV-4F2A1098...",
    "deviceName": "Google Pixel 8 Pro",
    "packageId": "com.aistudio.mobilevm.vmar64",
    "signatureFingerprint": "8A52C0FE...",
    "clientTimestamp": 1790500000000,
    "nonce": "a7b31e05-2d4f-45a1-9a73-619213bc5e90"
  }
  ```
- **Response Body:**
  ```json
  {
    "success": true,
    "status": "ACTIVE",
    "tier": "PRO",
    "issuedTo": "Professional Developer",
    "expiresAt": 1822036000000,
    "entitlements": {
      "cpuCoresMax": 4,
      "ramMaxMb": 4096,
      "canExportVm": true,
      "canCreateSnapshots": true,
      "customDiskImages": true,
      "networkBridgeMode": false,
      "hardwareAccelerationKvm": true,
      "customDtbGeneration": true,
      "prioritySupport": false
    },
    "activeDeviceCount": 1,
    "maxDevices": 3,
    "serverTimestamp": 1790500001200,
    "signature": "E4A560BF921C...",
    "message": "License activated successfully for 3 devices."
  }
  ```

### B. POST `/api/license/validate`
Validates an already-activated license, refreshes entitlements, and resets the 7-day offline grace clock.

### C. POST `/api/license/deactivate`
Releases a device slot from the license server so that the user can activate the license on another device.

### D. POST `/api/license/revoke` (Admin Only)
Immediately revokes a compromised or pirated key across all active devices.

---

## 5. Database Schema (Production Server)

```sql
-- Production Relational Schema (PostgreSQL 15+ / MySQL 8+)

CREATE TABLE licenses (
    license_id VARCHAR(64) PRIMARY KEY,
    license_key_hash VARCHAR(64) UNIQUE NOT NULL,      -- SHA-256 hash of key
    key_masked VARCHAR(32) NOT NULL,                  -- e.g. MBM-PRO-****-ABCD
    tier VARCHAR(20) NOT NULL,                        -- FREE, PRO, PREMIUM, ENTERPRISE
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE, EXPIRED, REVOKED, SUSPENDED
    product VARCHAR(50) NOT NULL DEFAULT 'Mobile BM',
    issued_to VARCHAR(255) NOT NULL,
    customer_email VARCHAR(255),
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL DEFAULT 0,             -- 0 = Perpetual / Lifetime
    max_devices INT NOT NULL DEFAULT 3,
    activation_count INT NOT NULL DEFAULT 0,
    feature_entitlements JSONB NOT NULL,
    revoked_at BIGINT DEFAULT NULL,
    revocation_reason VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_licenses_hash ON licenses(license_key_hash);
CREATE INDEX idx_licenses_status ON licenses(status);

CREATE TABLE license_device_activations (
    activation_id BIGSERIAL PRIMARY KEY,
    license_id VARCHAR(64) REFERENCES licenses(license_id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    device_name VARCHAR(128),
    package_id VARCHAR(128) NOT NULL,
    signing_fingerprint VARCHAR(64),
    activated_at BIGINT NOT NULL,
    last_validation_at BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (license_id, device_id)
);

CREATE TABLE license_audit_logs (
    log_id BIGSERIAL PRIMARY KEY,
    timestamp BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,                      -- ACTIVATE, VALIDATE, DEACTIVATE, REVOKE
    license_id VARCHAR(64),
    device_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,                      -- SUCCESS, FAILED
    error_reason VARCHAR(255),
    client_ip VARCHAR(64),
    user_agent VARCHAR(128)
);
```

---

## 6. Offline Grace Period & Clock Tamper Detection

1. **Monotonic High-Water Mark:** Every server validation records a cryptographically signed wall-clock checkpoint in `TimeTamperDetector`.
2. **Backward Drift Checking:** If `System.currentTimeMillis()` is ever moved backward beyond 60 seconds of tolerance, a tampering event is registered immediately.
3. **Elapsed Monotonic Realtime Differential:** Compares `SystemClock.elapsedRealtime()` against wall-clock advancement. If elapsed realtime progresses without corresponding wall clock change (or moves backward), license offline grace is revoked immediately.
4. **7-Day Grace Limit:** Offline licenses expire after 7 days (`DEFAULT_OFFLINE_GRACE_PERIOD_MS = 604,800,000 ms`). The app gracefully degrades to the FREE tier until connectivity is restored.

---

## 7. Feature Gates & Entitlement Matrix

| Feature | FREE | PRO | PREMIUM | ENTERPRISE |
| :--- | :---: | :---: | :---: | :---: |
| Maximum vCPU Cores | 1 Core | 4 Cores | 8 Cores | 16 Cores |
| Maximum RAM Allocation | 1024 MB | 4096 MB | 8192 MB | 32768 MB |
| Snapshot Creation & Restore | ❌ | ✅ | ✅ | ✅ |
| VM Disk & Config Export | ❌ | ✅ | ✅ | ✅ |
| Custom Rootfs & ISO Images | ❌ | ✅ | ✅ | ✅ |
| Network Bridge Mode | ❌ | ❌ | ✅ | ✅ |
| Custom DTB Hardware Maps | ❌ | ❌ | ✅ | ✅ |
| Unlimited Concurrent VMs | ❌ | ❌ | ❌ | ✅ |
| Max Device Bindings | 1 Device | 3 Devices | 5 Devices | 20 Devices |

Entitlements are strictly enforced inside `VMViewModel.startVM(...)` via `FeatureGate.verifyVmConfig(config, entitlements)`. Any attempt to start a VM that exceeds the active license limit is blocked with an informative upgrade dialog.
