# MobileVM — Application Build Workflow Guide

Is document me **MobileVM Android Application** ko compile, test, aur build (APK / AAB) karne ka poora workflow code aur guide diya gaya hai.

---

## 🛠️ 1. Project Requirements (आवश्यकताएँ)

- **JDK Version**: Java 17 (OpenJDK 17 ya Temurin 17)
- **Android SDK**: API 34 (Android 14) / Min SDK 26 (Android 8.0)
- **NDK Version**: NDK 28 (C++ Native Core Build ke liye)
- **Gradle Version**: 8.x + Kotlin 2.0+

---

## 🚀 2. Local Terminal / Shell Workflow Commands

### A. Environment Check (वातावरण जाँच)
```bash
java -version
javac -version
```

### B. Clean Project (प्रोजेक्ट साफ़ करें)
```bash
./gradlew clean
```

### C. Compile & Verify Kotlin/Java Code (कोड कम्पाइल करें)
```bash
./gradlew :app:compileDebugKotlin
```

### D. Run Unit Tests & Robolectric Tests (टेस्ट चलाएं)
```bash
./gradlew :app:testDebugUnitTest
```

### E. Build Debug APK (टेस्टिंग के लिए APK बनाएं)
```bash
./gradlew :app:assembleDebug
```
> **Output Path:** `app/build/outputs/apk/debug/app-debug.apk`

### F. Build Release APK (प्रोडक्शन APK)
```bash
./gradlew :app:assembleRelease
```
> **Output Path:** `app/build/outputs/apk/release/app-release-unsigned.apk`

### G. Build Release Android App Bundle (Play Store AAB)
```bash
./gradlew :app:bundleRelease
```
> **Output Path:** `app/build/outputs/bundle/release/app-release.aab`

---

## 🤖 3. GitHub Actions CI/CD Workflow (`.github/workflows/android_build.yml`)

Aapke repo me automatic build ke liye GitHub Actions workflow include kar diya gaya hai:

```yaml
name: Android MobileVM CI/CD Build Workflow

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    name: Build & Test MobileVM Android App
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Setup Android SDK & NDK
        uses: android-actions/setup-android@v3

      - name: Grant Execute Permission for Gradle
        run: chmod +x gradlew || true

      - name: Run Unit & Robolectric Tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: Build Debug APK
        run: ./gradlew assembleDebug --stacktrace --no-daemon

      - name: Upload Debug APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: MobileVM-Debug-APK
          path: app/build/outputs/apk/debug/app-debug.apk
```

---

## 💻 4. Android Studio Build Steps

1. **Android Studio** me project open karein (`File > Open > MobileVM`).
2. Gradle sync complete hone ka wait karein.
3. Top Menu se **Build > Build Bundle(s) / APK(s) > Build APK(s)** par click karein.
4. Testing ke liye connected physical device ya Emulator select karke **Run (Shift + F10)** karein.
