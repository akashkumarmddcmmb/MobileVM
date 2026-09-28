# Privacy Policy for Mobile BM

**Effective Date:** January 1, 2026  
**Last Updated:** September 28, 2026  
**Product:** Mobile BM (Bare-Metal & Hypervisor Virtualization Suite for Android)  
**Publisher/Owner:** Akash Kumar / Mobile BM Authors ("we", "us", or "our")

---

## 1. Overview
Mobile BM is designed with a strict privacy-first, local-compute philosophy. We believe your virtual machines, disks, guest operating system data, and computational workflows belong strictly to you. Mobile BM operates offline-first and does not collect or transmit personal user files, disk images, or telemetry.

## 2. Information We Collect and Process
To provide proprietary software licensing, anti-piracy validation, and feature entitlements, the following limited, pseudonymized information is processed:

### A. License Activation & Device Binding Telemetry
When you activate a paid license key (Pro, Premium, Enterprise) or evaluate a trial:
- **Device Fingerprint Hash:** A one-way SHA-256 cryptographic hash derived from hardware identifiers (Android hardware model, board, manufacturer, and app-specific installation identifier). This hash does not reveal your personal identity, phone number, IMEI, or Google Account.
- **License Key:** The alphanumeric code entered by the user.
- **Package Identity Hash:** Verification that the running binary is signed with the genuine developer certificate to prevent tampered or repackaged binaries.
- **Timestamp & Nonce:** Used exclusively to prevent replay attacks during server validation.

### B. Local Virtual Machine Data
All guest OS kernels, root filesystems, virtual disk images (.qcow2, .raw, .img), and memory dumps are stored exclusively in your device's private sandboxed app storage. None of this data is ever transmitted to our servers or third parties.

## 3. How We Use Information
We use the licensing information solely for:
- Validating license key authenticity and determining license tier entitlements (e.g. max vCPU count, memory allocation).
- Enforcing device activation quotas (e.g. limiting simultaneous activations across devices).
- Preventing unauthorized software distribution and piracy.
- Administering offline grace periods (up to 7 days of verified offline operation).

## 4. Third-Party Sharing and Tracking
- We **do not** sell, rent, or monetize user data.
- We **do not** embed third-party advertising SDKs, ad trackers, or behavioral profiling trackers.
- Network communication is restricted strictly to authoritative licensing endpoints over encrypted HTTPS/TLS.

## 5. Data Security
Licensing data stored locally on your device is protected using encrypted SharedPreferences (`OfflineLicenseVault`) with HMAC-SHA256 device-bound integrity checks. On our license servers, license keys are indexed via cryptographic hashes and never stored as raw plaintext.

## 6. Children's Privacy
Mobile BM is a developer and system utility application not directed towards children under the age of 13. We do not knowingly collect personal information from children.

## 7. Contact Information
If you have questions about this Privacy Policy or your license data, please contact:
- **Developer / Publisher:** Akash Kumar
- **Project Repository:** Mobile BM Android Project
- **Support Email:** `[SUPPORT_EMAIL_PLACEHOLDER]`
