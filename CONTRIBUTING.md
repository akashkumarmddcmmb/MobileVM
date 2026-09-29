# Contributing to MobileVM

Thank you for your interest in contributing to MobileVM!

---

## 1. Development Prerequisites

- **JDK**: Java Development Kit 17 or 21.
- **Android SDK**: Compile SDK 36, Target SDK 36, Min SDK 24.
- **Android NDK**: Version 26.1.10909125 or newer.
- **CMake**: Version 3.22.1.
- **Gradle**: Gradle 8.9+ with Android Gradle Plugin (AGP) 8.9.1.

---

## 2. Building and Testing

### Run All Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Build Release APK
```bash
./gradlew assembleRelease
```

---

## 3. Code Standards & Architecture Guidelines

1. **Security-First**: Never disable path traversal checks or memory safety bounds. All file I/O operations touching external storage must use bounded streams.
2. **No Hardcoded Secrets**: Secrets, keys, and tokens must never be committed into the repository or printed into logging outputs.
3. **Jetpack Compose UI**: Maintain Material Design 3 guidelines with test tags on primary interactive controls.
4. **Clean Concurrency**: Never perform disk I/O, CPU execution, or network packet parsing on Android's main UI thread. Always utilize `Dispatchers.IO` or `Dispatchers.Default`.

---

## 4. Submitting Pull Requests

1. Ensure `./gradlew testDebugUnitTest` passes cleanly with zero errors.
2. Verify both `./gradlew assembleDebug` and `./gradlew assembleRelease` complete successfully.
3. Include descriptive explanations of architectural changes and test coverage.
